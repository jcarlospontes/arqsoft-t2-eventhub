"""Notification Service (Python).

Consome eventos de inscricao do RabbitMQ (RF08) e "envia" o e-mail (simulado: grava e registra em log).
- Consumidor idempotente: event_id e chave primaria; evento repetido e ignorado.
- Fila duravel: se o servico estiver fora, as mensagens esperam (RN05 / RQ03).
- Falha de processamento vai para a DLQ (notification.dlq) em vez de travar a fila.
Expoe GET /notifications apenas para a demonstracao.
"""
import json
import os
import threading
import time

import pika
import psycopg
from fastapi import FastAPI
from psycopg.rows import dict_row

DSN = (
    f"host={os.environ.get('DB_HOST', 'localhost')} port={os.environ.get('DB_PORT', '5432')} "
    f"dbname={os.environ.get('DB_NAME', 'eventhub')} user={os.environ['DB_USER']} "
    f"password={os.environ['DB_PASSWORD']}"
)
RABBIT = pika.ConnectionParameters(
    host=os.environ.get("RABBITMQ_HOST", "localhost"),
    credentials=pika.PlainCredentials(os.environ["RABBITMQ_USER"], os.environ["RABBITMQ_PASSWORD"]),
    heartbeat=30,
)
EXCHANGE, QUEUE, DLX, DLQ = "eventhub.events", "notification.registration", "eventhub.dlx", "notification.dlq"

TEMPLATES = {
    "RegistrationConfirmed": "Sua inscricao em {activityId} esta confirmada.",
    "RegistrationCancelled": "Sua inscricao em {activityId} foi cancelada.",
    "RegistrationReleased": "Nao foi possivel concluir sua inscricao em {activityId} ({reason}). A vaga foi liberada.",
}

app = FastAPI(title="EventHub - Notification Service", version="1.0.0")


def db():
    return psycopg.connect(DSN, row_factory=dict_row, autocommit=True)


def init_db():
    for _ in range(30):
        try:
            with db() as conn:
                conn.execute(
                    """CREATE TABLE IF NOT EXISTS notifications (
                         event_id UUID PRIMARY KEY,
                         event_type TEXT NOT NULL,
                         recipient TEXT NOT NULL,
                         message TEXT NOT NULL,
                         correlation_id TEXT,
                         sent_at TIMESTAMPTZ NOT NULL DEFAULT now())"""
                )
            return
        except psycopg.OperationalError:
            time.sleep(1)
    raise RuntimeError("Banco indisponivel")


def handle(ch, method, props, body):
    try:
        event = json.loads(body)
        payload = event["payload"]
        template = TEMPLATES.get(event["type"])
        if template is None:
            ch.basic_ack(method.delivery_tag)
            return
        message = template.format(**payload)
        with db() as conn:
            cur = conn.execute(
                """INSERT INTO notifications (event_id, event_type, recipient, message, correlation_id)
                   VALUES (%s,%s,%s,%s,%s) ON CONFLICT (event_id) DO NOTHING""",
                (event["eventId"], event["type"], payload["participantId"], message, event.get("correlationId")),
            )
        if cur.rowcount == 0:
            print(f"[notification] corr={event.get('correlationId')} evento duplicado {event['eventId']} ignorado", flush=True)
        else:
            print(f"[notification] corr={event.get('correlationId')} e-mail para {payload['participantId']}: {message}", flush=True)
        ch.basic_ack(method.delivery_tag)
    except Exception as exc:  # mensagem envenenada -> DLQ
        print(f"[notification] falha ao processar mensagem: {exc!r} -> DLQ", flush=True)
        ch.basic_nack(method.delivery_tag, requeue=False)


def consume_forever():
    while True:
        try:
            conn = pika.BlockingConnection(RABBIT)
            ch = conn.channel()
            ch.exchange_declare(EXCHANGE, "topic", durable=True)
            ch.exchange_declare(DLX, "fanout", durable=True)
            ch.queue_declare(DLQ, durable=True)
            ch.queue_bind(DLQ, DLX)
            ch.queue_declare(QUEUE, durable=True, arguments={"x-dead-letter-exchange": DLX})
            ch.queue_bind(QUEUE, EXCHANGE, routing_key="registration.#")
            ch.basic_qos(prefetch_count=10)
            ch.basic_consume(QUEUE, handle)
            print("[notification] consumindo fila notification.registration", flush=True)
            ch.start_consuming()
        except Exception as exc:
            print(f"[notification] broker indisponivel ({exc!r}); nova tentativa em 3 s", flush=True)
            time.sleep(3)


@app.on_event("startup")
def start():
    init_db()
    threading.Thread(target=consume_forever, daemon=True).start()


@app.get("/notifications")
def list_notifications(recipient: str | None = None):
    with db() as conn:
        if recipient:
            rows = conn.execute("SELECT * FROM notifications WHERE recipient=%s ORDER BY sent_at DESC", (recipient,)).fetchall()
        else:
            rows = conn.execute("SELECT * FROM notifications ORDER BY sent_at DESC LIMIT 100").fetchall()
    for r in rows:
        r["sent_at"] = r["sent_at"].isoformat()
        r["event_id"] = str(r["event_id"])
    return rows


@app.get("/health")
def health():
    return {"status": "UP"}
