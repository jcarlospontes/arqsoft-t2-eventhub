"""Fluxos funcionais e estados da inscricao (Parte III, item 1) + integracao Java <-> Python (item 4)."""
import time
import uuid

import httpx

from conftest import (CATALOG, DEMO_PASSWORD, PAYMENT, REGISTRATION, create_activity, new_participant,
                      register, seats, token_for, wait_notification)


def test_login_emite_token_e_lista_atividades():
    r = httpx.post(f"{CATALOG}/auth/token", json={"username": "ana", "password": DEMO_PASSWORD})
    assert r.status_code == 200
    assert httpx.get(f"{CATALOG}/activities").status_code == 200
    assert httpx.post(f"{CATALOG}/auth/token", json={"username": "ana", "password": "errada"}).status_code == 401


def test_inscricao_paga_confirmada_ponta_a_ponta(participant):
    """Catalogo (Python) -> Inscricao (Java) -> Pagamento (Python) -> evento -> Notificacao (Python)."""
    user, headers = participant
    act = create_activity(price=50, capacity=10)
    corr = f"demo-{uuid.uuid4().hex[:6]}"

    r = register(headers, act, "tok_ok", **{"X-Correlation-Id": corr})
    print("\nPOST /registrations ->", r.status_code, r.json())
    assert r.status_code == 201
    reg = r.json()
    assert reg["status"] == "CONFIRMED"
    assert r.headers["X-Correlation-Id"] == corr

    pay = httpx.get(f"{PAYMENT}/payments/{reg['id']}").json()
    print("Pagamento ->", pay["status"], "corr:", pay["correlation_id"])
    assert pay["status"] == "APPROVED" and pay["correlation_id"] == corr

    s = seats(act)
    print("Vagas ->", s)
    assert s["confirmed"] == 1 and s["reserved"] == 0

    n = wait_notification(user, "RegistrationConfirmed")
    print("Notificacao ->", n)
    assert n is not None and n["correlation_id"] == corr


def test_inscricao_gratuita_confirma_sem_pagamento(participant):
    user, headers = participant
    act = create_activity(price=0, capacity=5)
    r = register(headers, act, card=None)
    assert r.status_code == 201 and r.json()["status"] == "CONFIRMED"
    assert httpx.get(f"{PAYMENT}/payments/{r.json()['id']}").status_code == 404  # nenhum pagamento criado
    assert seats(act)["confirmed"] == 1


def test_pagamento_recusado_libera_a_vaga(participant):
    """RN03: recusa -> PAYMENT_FAILED e a vaga volta."""
    user, headers = participant
    act = create_activity(price=50, capacity=1)
    r = register(headers, act, "tok_declined")
    print("\nrecusa ->", r.status_code, r.json())
    assert r.status_code == 402
    assert r.json()["status"] == "PAYMENT_FAILED" and r.json()["failureReason"] == "DECLINED"
    assert seats(act)["available"] == 1
    assert wait_notification(user, "RegistrationReleased") is not None

    # a vaga liberada pode ser usada por outra pessoa
    _, other = new_participant()
    assert register(other, act, "tok_ok").status_code == 201
    assert seats(act)["available"] == 0


def test_timeout_no_pagamento_libera_vaga_e_compensa(participant):
    """Provedor lento (5 s) > timeout da Inscricao (2 s): libera a vaga e cancela o pagamento (compensacao)."""
    user, headers = participant
    act = create_activity(price=50, capacity=1)
    t0 = time.time()
    r = register(headers, act, "tok_slow")
    elapsed = time.time() - t0
    print(f"\ntimeout -> {r.status_code} em {elapsed:.1f}s", r.json())
    assert r.status_code == 503 and r.json()["failureReason"] == "PAYMENT_UNAVAILABLE"
    assert elapsed < 4.5
    assert seats(act)["available"] == 1

    time.sleep(5)  # o provedor termina de processar depois
    pay = httpx.get(f"{PAYMENT}/payments/{r.json()['id']}").json()
    print("Pagamento apos o provedor responder ->", pay["status"])
    assert pay["status"] in ("CANCELLED", "REFUNDED")  # nunca APPROVED para uma inscricao nao confirmada


def test_idempotency_key_nao_duplica_inscricao(participant):
    user, headers = participant
    act = create_activity(price=50, capacity=5)
    key = str(uuid.uuid4())
    r1 = register(headers, act, "tok_ok", **{"Idempotency-Key": key})
    r2 = register(headers, act, "tok_ok", **{"Idempotency-Key": key})
    assert r1.json()["id"] == r2.json()["id"]
    assert seats(act)["confirmed"] == 1


def test_mesmo_participante_nao_inscreve_duas_vezes(participant):
    user, headers = participant
    act = create_activity(price=0, capacity=5)
    assert register(headers, act, None).status_code == 201
    r = register(headers, act, None)
    assert r.status_code == 409 and r.json()["error"] == "ALREADY_REGISTERED"


def test_cancelamento_devolve_vaga(participant):
    """RN04: cancelamento atualiza a disponibilidade."""
    user, headers = participant
    act = create_activity(price=50, capacity=1)
    reg = register(headers, act, "tok_ok").json()
    assert seats(act)["available"] == 0
    r = httpx.delete(f"{REGISTRATION}/registrations/{reg['id']}", headers=headers)
    assert r.status_code == 200 and r.json()["status"] == "CANCELLED"
    assert seats(act)["available"] == 1
    assert wait_notification(user, "RegistrationCancelled") is not None


def test_seguranca_rn07(participant):
    user, headers = participant
    act = create_activity(price=0, capacity=5)
    reg = register(headers, act, None).json()

    _, other = new_participant()
    assert httpx.get(f"{REGISTRATION}/registrations/{reg['id']}", headers=other).status_code == 403
    assert httpx.get(f"{REGISTRATION}/registrations/{reg['id']}").status_code == 401
    assert httpx.get(f"{REGISTRATION}/registrations/{reg['id']}",
                     headers={"Authorization": "Bearer token.forjado.aqui"}).status_code == 401

    # organizador da UFC nao cria atividade em evento da UECE
    ufc = token_for("org-ufc", "organizer", ["ufc"])
    r = httpx.post(f"{CATALOG}/activities", headers={"Authorization": f"Bearer {ufc}"}, json={
        "id": f"act-{uuid.uuid4().hex[:8]}", "organization_id": "uece", "event_name": "X", "title": "X",
        "starts_at": "2026-12-01T10:00:00Z", "price": 0, "capacity": 1})
    assert r.status_code == 403
