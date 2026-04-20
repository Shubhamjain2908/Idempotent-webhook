CREATE TABLE outbox_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Where is this going? (e.g., 'user-onboarding-topic')
    aggregate_type VARCHAR(100) NOT NULL,

    -- The identifier for the data (e.g., the idempotency_key or user_id)
    aggregate_id VARCHAR(255) NOT NULL,

    payload JSONB NOT NULL,

    -- A simple boolean is usually faster than an ENUM for outbox polling
    published BOOLEAN NOT NULL DEFAULT FALSE,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- A Partial Index
-- If we have 10 million sent messages and 5 unsent ones, we don't want the database
-- to manage an index of all 10 million. This index ONLY stores rows where published = FALSE.
-- When the Relay queries for pending messages, it is blazingly fast.
CREATE INDEX idx_outbox_unpublished ON outbox_events(created_at) WHERE published = FALSE;
