-- Promise Graph schema, version 2: dependency links, the web inbox, absences and handoffs.
-- On an existing local database, apply it with:
--   docker compose exec -T app-db psql -U promise -d promise_graph < migrations/002_phase2.sql

ALTER TABLE links ADD COLUMN IF NOT EXISTS status TEXT NOT NULL DEFAULT 'suggested';
ALTER TABLE links ADD COLUMN IF NOT EXISTS reason TEXT;
ALTER TABLE links ADD COLUMN IF NOT EXISTS suggested_by TEXT;
ALTER TABLE links ADD COLUMN IF NOT EXISTS decided_by TEXT;
ALTER TABLE links DROP CONSTRAINT IF EXISTS links_status_check;
ALTER TABLE links ADD CONSTRAINT links_status_check CHECK (status IN ('suggested', 'confirmed', 'rejected'));
CREATE INDEX IF NOT EXISTS links_upstream ON links (tenant, to_promise);

CREATE TABLE IF NOT EXISTS inbox (
    tenant           TEXT NOT NULL,
    key              TEXT NOT NULL,
    recipient        TEXT NOT NULL,
    text             TEXT NOT NULL,
    actions          JSONB NOT NULL DEFAULT '[]'::jsonb,
    promise_id       TEXT,
    subject          TEXT,
    created_at       TIMESTAMPTZ NOT NULL,
    status           TEXT NOT NULL CHECK (status IN ('open', 'done')),
    resolved_action  TEXT,
    PRIMARY KEY (tenant, key)
);

CREATE INDEX IF NOT EXISTS inbox_by_recipient ON inbox (tenant, recipient, created_at DESC);

CREATE TABLE IF NOT EXISTS absences (
    tenant     TEXT NOT NULL,
    person     TEXT NOT NULL,
    starts_on  DATE NOT NULL,
    ends_on    DATE,
    reason     TEXT NOT NULL,
    source     TEXT NOT NULL,
    PRIMARY KEY (tenant, person, starts_on)
);

ALTER TABLE handoffs ADD COLUMN IF NOT EXISTS planner TEXT;
ALTER TABLE handoffs ADD COLUMN IF NOT EXISTS items JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE handoffs ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ;
