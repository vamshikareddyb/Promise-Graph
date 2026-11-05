-- Reply drafts written by the draft agent for each email that produced a promise.
-- Nothing is sent from here: the person copies the draft into Gmail.
CREATE TABLE IF NOT EXISTS reply_drafts (
    tenant              TEXT NOT NULL,
    id                  TEXT NOT NULL,
    owner               TEXT NOT NULL,
    mailbox             TEXT,
    thread_id           TEXT NOT NULL,
    channel             TEXT NOT NULL,
    source_message_id   TEXT NOT NULL,
    to_address          TEXT,
    subject             TEXT NOT NULL,
    body                TEXT NOT NULL,
    status              TEXT NOT NULL,
    promise_ids         JSONB NOT NULL DEFAULT '[]',
    written_by          TEXT,
    created_at          TIMESTAMPTZ NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant, id)
);

CREATE INDEX IF NOT EXISTS reply_drafts_owner ON reply_drafts (tenant, lower(owner), created_at DESC);
