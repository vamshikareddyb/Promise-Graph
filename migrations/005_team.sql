-- Promise Graph schema, version 5: team mode (Google sign-in, one Gmail per person, a real directory).
-- On an existing local database, apply it with:
--   docker compose exec -T app-db psql -U promise -d promise_graph < migrations/005_team.sql

-- People who can own and take over promises. Everyone who signs in is added; admins fill in the rest.
CREATE TABLE IF NOT EXISTS people (
    tenant      TEXT NOT NULL,
    email       TEXT NOT NULL,
    name        TEXT NOT NULL,
    team        TEXT NOT NULL DEFAULT '',
    role        TEXT NOT NULL DEFAULT '',
    lead        BOOLEAN NOT NULL DEFAULT FALSE,
    admin       BOOLEAN NOT NULL DEFAULT FALSE,
    active      BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant, email)
);

-- Signed-in Google accounts. The refresh token is encrypted (AES-GCM) with the app's secret key.
CREATE TABLE IF NOT EXISTS accounts (
    tenant          TEXT NOT NULL,
    email           TEXT NOT NULL,
    refresh_token   TEXT NOT NULL,
    signed_in_at    TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant, email)
);
