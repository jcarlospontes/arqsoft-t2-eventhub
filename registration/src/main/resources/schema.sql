-- Schema "registration": dono exclusivo das inscricoes e das vagas (ADR-01)

CREATE TABLE IF NOT EXISTS activity_seats (
    activity_id TEXT PRIMARY KEY,
    capacity    INT NOT NULL,
    reserved    INT NOT NULL DEFAULT 0,
    confirmed   INT NOT NULL DEFAULT 0,
    price       NUMERIC(10,2) NOT NULL,
    -- segunda barreira: o proprio banco recusa qualquer estado com overbooking
    CONSTRAINT seats_invariant CHECK (reserved >= 0 AND confirmed >= 0 AND reserved + confirmed <= capacity)
);

CREATE TABLE IF NOT EXISTS registrations (
    id              UUID PRIMARY KEY,
    activity_id     TEXT NOT NULL REFERENCES activity_seats(activity_id),
    participant_id  TEXT NOT NULL,
    status          TEXT NOT NULL,
    amount          NUMERIC(10,2) NOT NULL,
    failure_reason  TEXT,
    idempotency_key TEXT UNIQUE,
    correlation_id  TEXT,
    hold_expires_at TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- um participante nao pode ter duas inscricoes ativas na mesma atividade
CREATE UNIQUE INDEX IF NOT EXISTS uq_active_registration
    ON registrations (activity_id, participant_id)
    WHERE status IN ('PENDING_PAYMENT', 'CONFIRMED');

-- Transactional Outbox (ADR-04)
CREATE TABLE IF NOT EXISTS outbox (
    id           BIGSERIAL PRIMARY KEY,
    event_id     UUID NOT NULL UNIQUE,
    event_type   TEXT NOT NULL,
    routing_key  TEXT NOT NULL,
    payload      TEXT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS ix_outbox_pending ON outbox (id) WHERE published_at IS NULL;
