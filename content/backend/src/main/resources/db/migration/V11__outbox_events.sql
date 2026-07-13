CREATE TABLE IF NOT EXISTS content.outbox_events (
    id UUID PRIMARY KEY,
    idempotency_key TEXT NOT NULL UNIQUE,
    target_service TEXT NOT NULL,
    event_type TEXT NOT NULL,
    collection TEXT NOT NULL,
    document_id UUID NOT NULL,
    operation TEXT NOT NULL,
    revision BIGINT NOT NULL,
    payload_json JSONB NOT NULL,
    status TEXT NOT NULL DEFAULT 'pending',
    attempts BIGINT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    leased_until TIMESTAMPTZ,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_outbox_status CHECK (status IN ('pending', 'leased', 'accepted', 'retry', 'dead')),
    CONSTRAINT chk_outbox_operation CHECK (operation IN ('upsert', 'delete'))
);

CREATE INDEX IF NOT EXISTS idx_outbox_ready
    ON content.outbox_events(status, next_attempt_at, created_at)
    WHERE status IN ('pending', 'retry');

CREATE INDEX IF NOT EXISTS idx_outbox_leased_until
    ON content.outbox_events(leased_until)
    WHERE status = 'leased';
