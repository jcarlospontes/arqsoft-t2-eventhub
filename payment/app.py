"""Payment Service (Python / FastAPI).

Dono de: pagamentos e tentativas (RF07, RN02, RN03). Schema `payment`.
Inclui um provedor de pagamento externo SIMULADO, controlado pelo `card_token`:
  tok_ok        -> aprovado
  tok_declined  -> recusado
  tok_slow      -> provedor demora 5 s (forca timeout no chamador)
Idempotencia: um pagamento por registration_id (chave natural).
"""
import os
import time
import uuid

import psycopg
from fastapi import FastAPI, Header, HTTPException
from psycopg.rows import dict_row
from pydantic import BaseModel, Field

DSN = (
    f"host={os.environ.get('DB_HOST', 'localhost')} port={os.environ.get('DB_PORT', '5432')} "
    f"dbname={os.environ.get('DB_NAME', 'eventhub')} user={os.environ['DB_USER']} "
    f"password={os.environ['DB_PASSWORD']}"
)
SLOW_SECONDS = float(os.environ.get("PROVIDER_SLOW_SECONDS", "5"))

app = FastAPI(title="EventHub - Payment Service", version="1.0.0")


def db():
    return psycopg.connect(DSN, row_factory=dict_row, autocommit=True)


@app.on_event("startup")
def init_db():
    for _ in range(30):
        try:
            with db() as conn:
                conn.execute(
                    """CREATE TABLE IF NOT EXISTS payments (
                         id UUID PRIMARY KEY,
                         registration_id UUID NOT NULL UNIQUE,
                         amount NUMERIC(10,2) NOT NULL,
                         status TEXT NOT NULL,          -- PENDING | APPROVED | DECLINED | CANCELLED | REFUNDED
                         provider_ref TEXT,
                         correlation_id TEXT,
                         created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                         updated_at TIMESTAMPTZ NOT NULL DEFAULT now())"""
                )
            return
        except psycopg.OperationalError:
            time.sleep(1)
    raise RuntimeError("Banco indisponivel")


class PaymentIn(BaseModel):
    registration_id: uuid.UUID
    amount: float = Field(gt=0)
    card_token: str


def simulated_provider(token: str) -> str:
    """Representa o sistema externo de pagamento."""
    if token == "tok_slow":
        time.sleep(SLOW_SECONDS)
        return "APPROVED"
    if token == "tok_declined":
        return "DECLINED"
    return "APPROVED"


def as_json(row):
    row["amount"] = float(row["amount"])
    for k in ("created_at", "updated_at"):
        row[k] = row[k].isoformat()
    return row


@app.post("/payments", tags=["payments"])
def create_payment(body: PaymentIn, x_correlation_id: str = Header(default="")):
    with db() as conn:
        existing = conn.execute("SELECT * FROM payments WHERE registration_id=%s", (body.registration_id,)).fetchone()
        if existing:  # repeticao da mesma chamada: devolve o resultado original
            print(f"[payment] corr={x_correlation_id} pagamento repetido reg={body.registration_id} -> {existing['status']}", flush=True)
            return as_json(existing)
        pid = uuid.uuid4()
        conn.execute(
            "INSERT INTO payments (id, registration_id, amount, status, correlation_id) VALUES (%s,%s,%s,'PENDING',%s)",
            (pid, body.registration_id, body.amount, x_correlation_id),
        )

    result = simulated_provider(body.card_token)

    with db() as conn:
        # So conclui se ninguem cancelou enquanto o provedor processava (compensacao do chamador)
        row = conn.execute(
            """UPDATE payments SET status=%s, provider_ref=%s, updated_at=now()
               WHERE id=%s AND status='PENDING' RETURNING *""",
            (result, f"prov-{uuid.uuid4().hex[:10]}", pid),
        ).fetchone()
        if row is None:
            row = conn.execute("SELECT * FROM payments WHERE id=%s", (pid,)).fetchone()
    print(f"[payment] corr={x_correlation_id} reg={body.registration_id} provider={result} final={row['status']}", flush=True)
    return as_json(row)


@app.post("/payments/{registration_id}/cancel", tags=["payments"])
def cancel_payment(registration_id: uuid.UUID, x_correlation_id: str = Header(default="")):
    """Compensacao: chamada pela Inscricao quando ela desistiu do pagamento (timeout)."""
    with db() as conn:
        row = conn.execute(
            """UPDATE payments SET status = CASE status WHEN 'APPROVED' THEN 'REFUNDED'
                                                       WHEN 'PENDING'  THEN 'CANCELLED'
                                                       ELSE status END,
                                   updated_at = now()
               WHERE registration_id=%s RETURNING *""",
            (registration_id,),
        ).fetchone()
    if row is None:  # a criacao nem chegou ao servico: nada a compensar
        return {"registration_id": str(registration_id), "status": "NOT_FOUND"}
    print(f"[payment] corr={x_correlation_id} compensacao reg={registration_id} -> {row['status']}", flush=True)
    return as_json(row)


@app.get("/payments/{registration_id}", tags=["payments"])
def get_payment(registration_id: uuid.UUID):
    with db() as conn:
        row = conn.execute("SELECT * FROM payments WHERE registration_id=%s", (registration_id,)).fetchone()
    if not row:
        raise HTTPException(404, "pagamento nao encontrado")
    return as_json(row)


@app.get("/health")
def health():
    return {"status": "UP"}
