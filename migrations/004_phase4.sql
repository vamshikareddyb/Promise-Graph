-- Promise Graph schema, version 4: dead letters and the audit log.
-- On an existing local database, apply it with:
--   docker compose exec -T app-db psql -U promise -d promise_graph < migrations/004_phase4.sql

CREATE TABLE IF NOT EXISTS dead_letters (
    tenant      TEXT NOT NULL,
    id          TEXT NOT NULL,
    kind        TEXT NOT NULL,
    reference   TEXT NOT NULL,
    error       TEXT,
    messages    JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at  TIMESTAMPTZ NOT NULL,
    status      TEXT NOT NULL CHECK (status IN ('open', 'retried', 'dismissed', 'auto_fixed')),
    PRIMARY KEY (tenant, id)
);

CREATE INDEX IF NOT EXISTS dead_letters_recent ON dead_letters (tenant, created_at DESC);

CREATE TABLE IF NOT EXISTS audit_log (
    seq     BIGSERIAL PRIMARY KEY,
    tenant  TEXT NOT NULL,
    at      TIMESTAMPTZ NOT NULL,
    actor   TEXT NOT NULL,
    action  TEXT NOT NULL,
    target  TEXT,
    detail  TEXT
);

CREATE INDEX IF NOT EXISTS audit_recent ON audit_log (tenant, seq DESC);
