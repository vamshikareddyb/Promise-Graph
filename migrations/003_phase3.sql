-- Promise Graph schema, version 3: negotiations (recovery plans and their approval).
-- On an existing local database, apply it with:
--   docker compose exec -T app-db psql -U promise -d promise_graph < migrations/003_phase3.sql

CREATE TABLE IF NOT EXISTS negotiations (
    tenant             TEXT NOT NULL,
    id                 TEXT NOT NULL,
    source_promise_id  TEXT NOT NULL,
    status             TEXT NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL,
    updated_at         TIMESTAMPTZ NOT NULL,
    -- The workflow's snapshot: plans, votes and history. The workflow history is the source of truth.
    doc                JSONB NOT NULL,
    PRIMARY KEY (tenant, id)
);

CREATE INDEX IF NOT EXISTS negotiations_by_source ON negotiations (tenant, source_promise_id);
CREATE INDEX IF NOT EXISTS negotiations_recent ON negotiations (tenant, created_at DESC);
