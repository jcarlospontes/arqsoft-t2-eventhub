"""Teste de concorrencia (Parte III, item 2): 200 pessoas disputando 30 vagas ao mesmo tempo.

Criterio: nunca mais de 30 inscricoes confirmadas (RN01 / RQ04).
"""
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
import threading
import time

import httpx

from conftest import REGISTRATION, create_activity, new_participant, seats

ATTEMPTS, CAPACITY = 200, 30


def _run(price: float, card: str | None):
    act = create_activity(price=price, capacity=CAPACITY,
                          title=f"Concorrência: {ATTEMPTS} pessoas, {CAPACITY} vagas ({'paga' if price else 'gratuita'})")
    seats(act)  # aquece a projecao local de vagas
    participants = [new_participant()[1] for _ in range(ATTEMPTS)]
    start = threading.Barrier(ATTEMPTS)
    client = httpx.Client(timeout=30, limits=httpx.Limits(max_connections=ATTEMPTS))

    def attempt(headers):
        start.wait()  # todas as threads disparam juntas
        body = {"activityId": act, **({"cardToken": card} if card else {})}
        return client.post(f"{REGISTRATION}/registrations", json=body, headers=headers).status_code

    t0 = time.time()
    with ThreadPoolExecutor(max_workers=ATTEMPTS) as pool:
        codes = list(pool.map(attempt, participants))
    elapsed = time.time() - t0
    final = seats(act)
    print(f"\n[{'paga' if price else 'gratuita'}] {ATTEMPTS} tentativas para {CAPACITY} vagas em {elapsed:.1f}s")
    print("   respostas HTTP:", dict(Counter(codes)))
    print("   estado final das vagas:", final)
    return codes, final


def test_200_tentativas_simultaneas_atividade_paga():
    codes, final = _run(price=50, card="tok_ok")
    assert codes.count(201) == CAPACITY
    assert codes.count(409) == ATTEMPTS - CAPACITY
    assert final["confirmed"] == CAPACITY and final["reserved"] == 0 and final["available"] == 0


def test_200_tentativas_simultaneas_atividade_gratuita():
    codes, final = _run(price=0, card=None)
    assert codes.count(201) == CAPACITY
    assert final["confirmed"] == CAPACITY and final["available"] == 0
