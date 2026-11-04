-- Promise Graph schema, version 1.
-- Postgres is the system of record for what promises exist and how they connect.
-- Temporal holds what each promise is currently doing.

CREATE TABLE IF NOT EXISTS parties (
    tenant      TEXT NOT NULL,
    id          TEXT NOT NULL,
    kind        TEXT NOT NULL CHECK (kind IN ('person', 'team', 'customer')),
    name        TEXT NOT NULL,
    team        TEXT,
    timezone    TEXT NOT NULL DEFAULT 'UTC',
    lead_id     TEXT,
    PRIMARY KEY (tenant, id)
);

CREATE TABLE IF NOT EXISTS promises (
    tenant      TEXT NOT NULL,
    id          TEXT NOT NULL,
    owner_id    TEXT NOT NULL,
    recipient   TEXT,
    what        TEXT NOT NULL,
    due         DATE,
    status      TEXT NOT NULL CHECK (status IN
                  ('detected', 'active', 'at_risk', 'kept', 'broken', 'dismissed', 'cancelled')),
    confidence  REAL NOT NULL CHECK (confidence >= 0 AND confidence <= 1),
    channel     TEXT,
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL,
    version     INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant, id)
);

CREATE INDEX IF NOT EXISTS promises_open_by_owner
    ON promises (tenant, owner_id)
    WHERE status IN ('detected', 'active', 'at_risk');

CREATE TABLE IF NOT EXISTS evidence (
    tenant      TEXT NOT NULL,
    promise_id  TEXT NOT NULL,
    message_id  TEXT NOT NULL,
    channel     TEXT,
    quote       TEXT NOT NULL,
    PRIMARY KEY (tenant, promise_id, message_id),
    FOREIGN KEY (tenant, promise_id) REFERENCES promises (tenant, id) ON DELETE CASCADE
);

-- Dependency links between promises (used from Phase 2).
CREATE TABLE IF NOT EXISTS links (
    tenant        TEXT NOT NULL,
    from_promise  TEXT NOT NULL,
    to_promise    TEXT NOT NULL,
    kind          TEXT NOT NULL CHECK (kind IN ('blocks', 'part_of')),
    confirmed_by  TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant, from_promise, to_promise)
);

CREATE INDEX IF NOT EXISTS links_downstream ON links (tenant, from_promise);

-- Append-only history of every change.
CREATE TABLE IF NOT EXISTS events (
    id          BIGSERIAL PRIMARY KEY,
    tenant      TEXT NOT NULL,
    promise_id  TEXT NOT NULL,
    kind        TEXT NOT NULL,
    actor       TEXT NOT NULL,
    at          TIMESTAMPTZ NOT NULL,
    detail      JSONB NOT NULL DEFAULT '{}'::jsonb,
    idem_key    TEXT NOT NULL,
    UNIQUE (tenant, idem_key)
);

-- Ingestion dedupe: a message is claimed by the first batch that sees it.
CREATE TABLE IF NOT EXISTS seen_messages (
    tenant      TEXT NOT NULL,
    message_id  TEXT NOT NULL,
    claimed_by  TEXT NOT NULL,
    seen_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant, message_id)
);

-- Notification dedupe: one send per idempotency key.
CREATE TABLE IF NOT EXISTS sent_notifications (
    tenant      TEXT NOT NULL,
    idem_key    TEXT NOT NULL,
    recipient   TEXT NOT NULL,
    sent_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant, idem_key)
);

-- Handoffs (used from Phase 2).
CREATE TABLE IF NOT EXISTS handoffs (
    tenant      TEXT NOT NULL,
    id          TEXT NOT NULL,
    person_id   TEXT NOT NULL,
    reason      TEXT NOT NULL,
    starts_on   DATE NOT NULL,
    ends_on     DATE,
    status      TEXT NOT NULL,
    PRIMARY KEY (tenant, id)
);
