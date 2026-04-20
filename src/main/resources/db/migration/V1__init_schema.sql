-- V1__init_schema.sql

CREATE TABLE processed_events (
    idempotency_key VARCHAR(255) PRIMARY KEY,

    -- SHA-256 hash is 64 characters long in hex
    payload_hash VARCHAR(64) NOT NULL,

    status VARCHAR(20) NOT NULL,

    -- JSONB is critical here. It stores data in a decomposed binary format,
    -- making it fast to process and query if we ever need to search inside the payloads.
    result_json JSONB,

    -- TIMESTAMPTZ (with time zone) is a PostgreSQL best practice to avoid timezone bugs
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL
);

-- Why this index?
-- We will have a background job running: DELETE FROM processed_events WHERE expires_at < NOW()
-- Without an index on expires_at, that job would require a full table scan,
-- which locks rows and destroys database performance at scale.
CREATE INDEX idx_processed_events_expires_at ON processed_events(expires_at);
