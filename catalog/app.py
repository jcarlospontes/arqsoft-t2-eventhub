"""Catalog Service (Python / FastAPI).

Dono de: eventos/atividades (RF02, RF03, RF04) e emissao de tokens de demonstracao (recorte de RF01).
Dados no schema `catalog`. Nenhum outro servico acessa estas tabelas.
"""
import os
import time
from datetime import datetime, timedelta, timezone

import jwt
import psycopg
from fastapi import Depends, FastAPI, Header, HTTPException
from psycopg.rows import dict_row
from pydantic import BaseModel, Field

JWT_SECRET = os.environ["JWT_SECRET"]
DEMO_PASSWORD = os.environ.get("DEMO_PASSWORD", "demo123")
DSN = (
    f"host={os.environ.get('DB_HOST', 'localhost')} port={os.environ.get('DB_PORT', '5432')} "
    f"dbname={os.environ.get('DB_NAME', 'eventhub')} user={os.environ['DB_USER']} "
    f"password={os.environ['DB_PASSWORD']}"
)

# Usuarios de demonstracao (dados de exemplo; senha vem do .env)
DEMO_USERS = {
    "ana": {"role": "participant", "orgs": []},
    "bruno": {"role": "participant", "orgs": []},
    "org-uece": {"role": "organizer", "orgs": ["uece"]},
    "org-ufc": {"role": "organizer", "orgs": ["ufc"]},
}

SEED = [
    ("act-workshop", "uece", "Semana de Computacao UECE", "Workshop de Microsservicos", "2026-11-20T14:00:00Z", 50.00, 30),
    ("act-palestra", "uece", "Semana de Computacao UECE", "Palestra de abertura", "2026-11-20T09:00:00Z", 0.00, 100),
    ("act-minicurso", "ufc", "Encontro de Arquitetura UFC", "Minicurso de Kubernetes", "2026-11-25T08:00:00Z", 80.00, 5),
]

app = FastAPI(title="EventHub - Catalog Service", version="1.0.0")


def db():
    return psycopg.connect(DSN, row_factory=dict_row, autocommit=True)


@app.on_event("startup")
def init_db():
    for attempt in range(30):
        try:
            with db() as conn:
                conn.execute(
                    """CREATE TABLE IF NOT EXISTS activities (
                         id TEXT PRIMARY KEY,
                         organization_id TEXT NOT NULL,
                         event_name TEXT NOT NULL,
                         title TEXT NOT NULL,
                         starts_at TIMESTAMPTZ NOT NULL,
                         price NUMERIC(10,2) NOT NULL DEFAULT 0,
                         capacity INT NOT NULL CHECK (capacity > 0),
                         status TEXT NOT NULL DEFAULT 'PUBLISHED')"""
                )
                conn.execute("ALTER TABLE activities ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now()")
                for row in SEED:
                    conn.execute(
                        """INSERT INTO activities (id, organization_id, event_name, title, starts_at, price, capacity)
                           VALUES (%s,%s,%s,%s,%s,%s,%s) ON CONFLICT (id) DO NOTHING""",
                        row,
                    )
            return
        except psycopg.OperationalError:
            time.sleep(1)
    raise RuntimeError("Banco indisponivel")


# ---------- Autenticacao minima (recorte de RF01 / RQ07) ----------
class LoginRequest(BaseModel):
    username: str
    password: str


@app.post("/auth/token", tags=["auth"])
def login(body: LoginRequest):
    user = DEMO_USERS.get(body.username)
    if not user or body.password != DEMO_PASSWORD:
        raise HTTPException(401, "credenciais invalidas")
    claims = {
        "sub": body.username,
        "role": user["role"],
        "orgs": user["orgs"],
        "exp": datetime.now(timezone.utc) + timedelta(hours=8),
    }
    return {"access_token": jwt.encode(claims, JWT_SECRET, algorithm="HS256"), "token_type": "bearer"}


def current_user(authorization: str = Header(default="")):
    if not authorization.startswith("Bearer "):
        raise HTTPException(401, "token ausente")
    try:
        return jwt.decode(authorization[7:], JWT_SECRET, algorithms=["HS256"])
    except jwt.PyJWTError:
        raise HTTPException(401, "token invalido")


# ---------- Atividades ----------
class ActivityIn(BaseModel):
    id: str = Field(pattern=r"^[a-z0-9-]{3,40}$")
    organization_id: str
    event_name: str
    title: str
    starts_at: datetime
    price: float = Field(ge=0)
    capacity: int = Field(gt=0)


def to_json(row):
    row["price"] = float(row["price"])
    row["starts_at"] = row["starts_at"].isoformat()
    if row.get("created_at"):
        row["created_at"] = row["created_at"].isoformat()
    return row


@app.get("/activities", tags=["activities"])
def list_activities():
    with db() as conn:
        rows = conn.execute("SELECT * FROM activities WHERE status='PUBLISHED' ORDER BY starts_at").fetchall()
    return [to_json(r) for r in rows]


@app.get("/activities/{activity_id}", tags=["activities"])
def get_activity(activity_id: str):
    with db() as conn:
        row = conn.execute("SELECT * FROM activities WHERE id=%s", (activity_id,)).fetchone()
    if not row:
        raise HTTPException(404, "atividade nao encontrada")
    return to_json(row)


@app.post("/activities", status_code=201, tags=["activities"])
def create_activity(body: ActivityIn, user=Depends(current_user)):
    # RN07: organizador so administra eventos da propria organizacao
    if user.get("role") != "organizer" or body.organization_id not in user.get("orgs", []):
        raise HTTPException(403, "organizador sem permissao para esta organizacao")
    with db() as conn:
        try:
            conn.execute(
                """INSERT INTO activities (id, organization_id, event_name, title, starts_at, price, capacity)
                   VALUES (%s,%s,%s,%s,%s,%s,%s)""",
                (body.id, body.organization_id, body.event_name, body.title, body.starts_at, body.price, body.capacity),
            )
        except psycopg.errors.UniqueViolation:
            raise HTTPException(409, "atividade ja existe")
    return get_activity(body.id)


@app.get("/health")
def health():
    return {"status": "UP"}
