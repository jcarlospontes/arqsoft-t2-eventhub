"""Roteiro da demonstracao em sala. Uso: python demo.py   (com os servicos no ar)

Cada passo espera ENTER para o apresentador comentar o que aconteceu.
"""
import json
import time
import uuid

import os
from pathlib import Path

import httpx

for _line in (Path(__file__).parent / ".env").read_text().splitlines() if (Path(__file__).parent / ".env").exists() else []:
    if "=" in _line and not _line.startswith("#"):
        os.environ.setdefault(*[p.strip() for p in _line.split("=", 1)])
DEMO_PASSWORD = os.environ.get("DEMO_PASSWORD", "demo123")

CATALOG, PAYMENT, NOTIF, REG = "http://localhost:8001", "http://localhost:8002", "http://localhost:8003", "http://localhost:8080"


def step(title):
    input(f"\n\033[1m=== {title} \033[0m[ENTER]")


def show(label, resp):
    body = resp.json() if resp.headers.get("content-type", "").startswith("application/json") else resp.text
    print(f"{label} -> HTTP {resp.status_code}")
    print(json.dumps(body, indent=2, ensure_ascii=False)[:1200])


def login(user):
    r = httpx.post(f"{CATALOG}/auth/token", json={"username": user, "password": DEMO_PASSWORD})
    r.raise_for_status()
    return {"Authorization": f"Bearer {r.json()['access_token']}"}


step("1. Catalogo (Python): atividades publicadas")
show("GET /activities", httpx.get(f"{CATALOG}/activities"))

step("2. Login da participante 'ana' (JWT)")
ana = login("ana")
print("token recebido:", ana["Authorization"][:60], "...")

step("3. Inscricao PAGA da ana no workshop: Inscricao (Java) -> Catalogo -> Pagamento (Python)")
corr = f"demo-{uuid.uuid4().hex[:6]}"
r = httpx.post(f"{REG}/registrations", headers={**ana, "X-Correlation-Id": corr},
               json={"activityId": "act-workshop", "cardToken": "tok_ok"}, timeout=15)
show("POST /registrations", r)
reg_id = r.json().get("id")
print(f"\ncorrelationId desta requisicao: {corr}  (procure nos logs: docker compose logs | grep {corr})")

step("4. Estado autoritativo das vagas e pagamento registrado")
show("GET /activities/act-workshop/seats", httpx.get(f"{REG}/activities/act-workshop/seats"))
if reg_id:
    show("GET /payments/{id}", httpx.get(f"{PAYMENT}/payments/{reg_id}"))

step("5. Evento RegistrationConfirmed consumido pela Notificacao (assincrono)")
time.sleep(1)
show("GET /notifications?recipient=ana", httpx.get(f"{NOTIF}/notifications", params={"recipient": "ana"}))

step("6. Mesma participante tenta de novo -> regra de negocio")
show("POST /registrations (repetida)", httpx.post(f"{REG}/registrations", headers=ana,
     json={"activityId": "act-workshop", "cardToken": "tok_ok"}, timeout=15))

step("7. Pagamento RECUSADO do bruno no minicurso -> vaga liberada (RN03)")
bruno = login("bruno")
show("vagas antes", httpx.get(f"{REG}/activities/act-minicurso/seats"))
show("POST /registrations (tok_declined)", httpx.post(f"{REG}/registrations", headers=bruno,
     json={"activityId": "act-minicurso", "cardToken": "tok_declined"}, timeout=15))
show("vagas depois", httpx.get(f"{REG}/activities/act-minicurso/seats"))

step("8. Provedor LENTO -> timeout de 2 s, vaga liberada e pagamento compensado")
t0 = time.time()
r = httpx.post(f"{REG}/registrations", headers=bruno, json={"activityId": "act-minicurso", "cardToken": "tok_slow"}, timeout=15)
show(f"POST /registrations (tok_slow) em {time.time() - t0:.1f}s", r)

step("9. Seguranca (RN07): bruno tenta ver a inscricao da ana")
if reg_id:
    show("GET /registrations/{id da ana} com token do bruno", httpx.get(f"{REG}/registrations/{reg_id}", headers=bruno))

step("10. Limpeza: ana cancela -> vaga devolvida (RN04)")
if reg_id:
    show("DELETE /registrations/{id}", httpx.delete(f"{REG}/registrations/{reg_id}", headers=ana))
    show("vagas", httpx.get(f"{REG}/activities/act-workshop/seats"))

print("\nFim. Testes de concorrencia e de falhas: pytest tests -v -s")
