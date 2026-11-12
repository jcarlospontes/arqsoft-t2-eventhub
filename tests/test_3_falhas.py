"""Simulacao de falhas de dependencias (Parte III, item 3).

Por padrao usa `docker compose stop/start <servico>`. Rode na raiz do repositorio.
"""
import time

import httpx

from conftest import (NOTIFICATION, PAYMENT, REGISTRATION, new_participant, START_CMD, STOP_CMD, create_activity, register, run, seats,
                      wait_notification)


def _wait_up(url: str, timeout: float = 60):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            if httpx.get(f"{url}/health", timeout=2).status_code == 200:
                return
        except httpx.HTTPError:
            pass
        time.sleep(1)
    raise AssertionError(f"{url} nao voltou")


def test_notificacao_fora_nao_bloqueia_inscricao(participant):
    """RN05 / RQ03: Notificacao parada -> inscricao confirma; o e-mail sai quando ela volta."""
    user, headers = participant
    act = create_activity(price=50, capacity=5, title="Falha: Notificação fora do ar")
    run(STOP_CMD, "notification")
    try:
        r = register(headers, act, "tok_ok")
        print("inscricao com Notificacao fora ->", r.status_code, r.json()["status"])
        assert r.status_code == 201 and r.json()["status"] == "CONFIRMED"
        time.sleep(3)
        assert httpx.get(f"{REGISTRATION}/registrations/{r.json()['id']}", headers=headers).json()["status"] == "CONFIRMED"
    finally:
        run(START_CMD, "notification")
    _wait_up(NOTIFICATION)
    n = wait_notification(user, "RegistrationConfirmed", timeout=40)
    print("notificacao entregue apos o retorno ->", n)
    assert n is not None


def test_broker_fora_eventos_ficam_na_outbox(participant):
    """ADR-04: RabbitMQ parado -> inscricao confirma; evento e publicado quando o broker volta."""
    user, headers = participant
    act = create_activity(price=0, capacity=5, title="Falha: RabbitMQ fora do ar")
    run(STOP_CMD, "rabbitmq")
    try:
        r = register(headers, act, None)
        print("inscricao com RabbitMQ fora ->", r.status_code, r.json()["status"])
        assert r.status_code == 201
        time.sleep(3)
    finally:
        run(START_CMD, "rabbitmq")
    n = wait_notification(user, "RegistrationConfirmed", timeout=90)
    print("evento publicado pela outbox apos o retorno do broker ->", n is not None)
    assert n is not None


def test_pagamento_fora_abre_circuit_breaker():
    """Pagamento parado -> 503 rapido, vaga liberada; apos 5 falhas o circuito abre (falha sem chamar)."""
    act = create_activity(price=50, capacity=50, title="Falha: Pagamento fora do ar")
    run(STOP_CMD, "payment")
    try:
        codes = []
        for _ in range(6):
            _, h = new_participant()
            t0 = time.time()
            r = register(h, act, "tok_ok")
            codes.append((r.status_code, round(time.time() - t0, 2)))
        state = httpx.get(f"{REGISTRATION}/internal/circuit-breaker").json()["payment"]
        print("respostas (status, segundos):", codes, "| circuito:", state)
        assert all(c == 503 for c, _ in codes)
        assert state == "OPEN"
        assert seats(act)["available"] == 50  # nenhuma vaga presa
    finally:
        run(START_CMD, "payment")
    _wait_up(PAYMENT)
    time.sleep(16)  # tempo do circuito aberto
    _, h = new_participant()
    r = register(h, act, "tok_ok")
    state = httpx.get(f"{REGISTRATION}/internal/circuit-breaker").json()["payment"]
    print("apos o retorno do Pagamento ->", r.status_code, "| circuito:", state)
    assert r.status_code == 201 and state == "CLOSED"
