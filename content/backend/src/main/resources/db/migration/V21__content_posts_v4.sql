-- Content Posts v4: immutable working revisions, explicit Media lifecycle
-- snapshots and a denser recommendation placement contract.

CREATE TABLE IF NOT EXISTS content.post_revisions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    post_id UUID NOT NULL REFERENCES content.posts(id) ON DELETE CASCADE,
    revision_no BIGINT NOT NULL,
    state TEXT NOT NULL CHECK (state IN (
        'DRAFT','PENDING_SOURCE','PROCESSING_MEDIA','ACTIVE',
        'NEEDS_ACTION','SUPERSEDED','CANCELLED'
    )),
    edit_version BIGINT NOT NULL DEFAULT 1,
    allow_comments BOOLEAN NOT NULL DEFAULT TRUE,
    hidden_tags JSONB NOT NULL DEFAULT '[]'::jsonb,
    layout_version INT NOT NULL DEFAULT 2,
    requested_at TIMESTAMPTZ,
    activated_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (post_id, revision_no)
);

CREATE TABLE IF NOT EXISTS content.post_revision_assets (
    revision_id UUID NOT NULL REFERENCES content.post_revisions(id) ON DELETE CASCADE,
    item_id TEXT NOT NULL,
    asset_id TEXT NOT NULL,
    sort_order INT NOT NULL,
    x INT NOT NULL,
    y INT NOT NULL,
    size_preset TEXT NOT NULL CHECK (size_preset IN ('S','M','L')),
    source_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    processing_run_id TEXT,
    generation BIGINT,
    failure_code TEXT,
    retry_count INT NOT NULL DEFAULT 0 CHECK (retry_count BETWEEN 0 AND 1),
    PRIMARY KEY (revision_id, item_id),
    UNIQUE (revision_id, sort_order)
);

ALTER TABLE content.posts ADD COLUMN IF NOT EXISTS active_revision_id UUID;
DO $$ BEGIN
    ALTER TABLE content.posts ADD CONSTRAINT posts_active_revision_fk
        FOREIGN KEY (active_revision_id) REFERENCES content.post_revisions(id) ON DELETE SET NULL;
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

ALTER TABLE content.post_publications ADD COLUMN IF NOT EXISTS revision_id UUID REFERENCES content.post_revisions(id) ON DELETE CASCADE;

ALTER TABLE content.post_assets
    ADD COLUMN IF NOT EXISTS source_status TEXT,
    ADD COLUMN IF NOT EXISTS processing_status TEXT NOT NULL DEFAULT 'NONE',
    ADD COLUMN IF NOT EXISTS delivery_status TEXT NOT NULL DEFAULT 'NONE',
    ADD COLUMN IF NOT EXISTS failure_json JSONB;

CREATE INDEX IF NOT EXISTS idx_post_revisions_working
    ON content.post_revisions(post_id, updated_at DESC)
    WHERE state IN ('DRAFT','PENDING_SOURCE','PROCESSING_MEDIA','NEEDS_ACTION');

CREATE TABLE IF NOT EXISTS content.maintenance_audit (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    action TEXT NOT NULL,
    entity_type TEXT NOT NULL,
    entity_id TEXT NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- The owner explicitly requested deletion of this one broken draft. Cascades
-- remove its publication attempt, assets, placements and search projection.
INSERT INTO content.maintenance_audit(action, entity_type, entity_id, metadata)
SELECT 'DELETE_BROKEN_DRAFT', 'post', id::text, jsonb_build_object('status', status)
FROM content.posts
WHERE id = '51fdc9b6-a3f1-47bb-90b8-961d75b8ee31' AND status = 'DRAFT';

DELETE FROM content.posts
WHERE id = '51fdc9b6-a3f1-47bb-90b8-961d75b8ee31' AND status = 'DRAFT';

-- Placement v2 coordinates are cache data. Rebuild them deterministically
-- with the denser v3 algorithm on the next spatial request.
TRUNCATE TABLE content.recommendation_constellations CASCADE;
ALTER TABLE content.recommendation_post_slots ALTER COLUMN placement_version SET DEFAULT 3;
