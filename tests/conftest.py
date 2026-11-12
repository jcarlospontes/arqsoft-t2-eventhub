"""Configuracao comum dos testes de validacao (Parte III).

Os testes falam com os servicos de fora, pelas APIs publicas, como um cliente faria.
URLs e comandos de parada/partida podem ser trocados por variaveis de ambiente.
"""
import os
import subprocess
import time
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

import httpx
import jwt
import pytest


def _load_env_file():
    env = Path(__file__).resolve().parent.parent / ".env"
    if env.exists():
        for line in env.read_text().splitlines():
            if "=" in line and not line.strip().startswith("#"):
                k, v = line.split("=", 1)
                os.environ.setdefault(k.strip(), v.strip())


_load_env_file()

CATALOG = os.environ.get("CATALOG_URL", "http://localhost:8001")
PAYMENT = os.environ.get("PAYMENT_URL", "http://localhost:8002")
NOTIFICATION = os.environ.get("NOTIFICATION_URL", "http://localhost:8003")
REGISTRATION = os.environ.get("REGISTRATION_URL", "http://localhost:8080")
JWT_SECRET = os.environ["JWT_SECRET"]
DEMO_PASSWORD = os.environ.get("DEMO_PASSWORD", "demo123")

# Comandos para simular falhas. Padrao: Docker Compose. Ex.: STOP_CMD="docker compose stop {svc}"
STOP_CMD = os.environ.get("STOP_CMD", "docker compose stop {svc}")
START_CMD = os.environ.get("START_CMD", "docker compose start {svc}")


def token_for(username: str, role: str = "participant", orgs=None) -> str:
    """Emite um token como o provedor de identidade faria (usado para criar muitos participantes)."""
    claims = {"sub": username, "role": role, "orgs": orgs or [],
              "exp": datetime.now(timezone.utc) + timedelta(hours=1)}
    return jwt.encode(claims, JWT_SECRET, algorithm="HS256")


def new_participant() -> tuple[str, dict]:
    user = f"part-{uuid.uuid4().hex[:8]}"
    return user, {"Authorization": f"Bearer {token_for(user)}"}


def register(headers: dict, activity_id: str, card: str | None = "tok_ok", **extra_headers):
    body = {"activityId": activity_id}
    if card:
        body["cardToken"] = card
    return httpx.post(f"{REGISTRATION}/registrations", json=body, headers={**headers, **extra_headers}, timeout=15)


def seats(activity_id: str) -> dict:
    return httpx.get(f"{REGISTRATION}/activities/{activity_id}/seats", timeout=5).json()


def create_activity(price: float, capacity: int, org: str = "uece", title: str | None = None) -> str:
    act_id = f"act-{uuid.uuid4().hex[:8]}"
    org_token = token_for(f"org-{org}", "organizer", [org])
    r = httpx.post(f"{CATALOG}/activities", headers={"Authorization": f"Bearer {org_token}"}, json={
        "id": act_id, "organization_id": org, "event_name": "Teste", "title": title or f"Atividade {act_id}",
        "starts_at": "2026-12-01T10:00:00Z", "price": price, "capacity": capacity}, timeout=5)
    assert r.status_code == 201, r.text
    return act_id


def wait_notification(participant: str, event_type: str, timeout: float = 20.0) -> dict | None:
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            for n in httpx.get(f"{NOTIFICATION}/notifications", params={"recipient": participant}, timeout=3).json():
                if n["event_type"] == event_type:
                    return n
        except httpx.HTTPError:
            pass
        time.sleep(0.5)
    return None


def run(cmd_template: str, svc: str):
    cmd = cmd_template.format(svc=svc)
    print(f"\n$ {cmd}")
    subprocess.run(cmd, shell=True, check=True)


@pytest.fixture
def participant():
    return new_participant()
