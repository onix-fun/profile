CREATE TABLE content.post_publications (
    draft_id UUID PRIMARY KEY REFERENCES content.posts(id) ON DELETE CASCADE,
    revision BIGINT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('DRAFT', 'PENDING_MEDIA', 'ACTIVE', 'NEEDS_MEDIA_ACTION')),
    idempotency_key TEXT NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL,
    activated_at TIMESTAMPTZ,
    failure_asset_ids JSONB NOT NULL DEFAULT '[]'::jsonb
);

CREATE INDEX post_publications_pending_idx
    ON content.post_publications (requested_at)
    WHERE state IN ('PENDING_MEDIA', 'NEEDS_MEDIA_ACTION');

CREATE TABLE content.media_event_inbox (
    event_id TEXT PRIMARY KEY,
    received_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE content.media_event_cursor (
    consumer_key TEXT PRIMARY KEY,
    last_sequence BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
