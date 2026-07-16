ALTER TABLE content.post_publications DROP CONSTRAINT IF EXISTS post_publications_state_check;
ALTER TABLE content.post_publications ADD CONSTRAINT post_publications_state_check
    CHECK (state IN ('DRAFT','PENDING_SOURCE','PROCESSING_MEDIA','PENDING_MEDIA','ACTIVE','NEEDS_MEDIA_ACTION','CANCELLED'));
ALTER TABLE content.post_publications ADD COLUMN IF NOT EXISTS processing_run_ids JSONB NOT NULL DEFAULT '{}'::jsonb;
DROP INDEX IF EXISTS content.post_publications_pending_idx;
CREATE INDEX post_publications_pending_idx ON content.post_publications(requested_at)
    WHERE state IN ('PENDING_SOURCE','PROCESSING_MEDIA','PENDING_MEDIA','NEEDS_MEDIA_ACTION');

ALTER TABLE content.post_assets DROP CONSTRAINT IF EXISTS chk_post_assets_status;
ALTER TABLE content.post_assets ADD CONSTRAINT chk_post_assets_status
    CHECK (status IN ('UPLOADING','VERIFYING','AVAILABLE','PROCESSING','READY','FAILED','CANCELLED'));
ALTER TABLE content.post_assets
    ADD COLUMN IF NOT EXISTS generation BIGINT,
    ADD COLUMN IF NOT EXISTS processing_run_id TEXT,
    ADD COLUMN IF NOT EXISTS delivery_contract TEXT;

-- Existing rows intentionally retain their legacy URLs. STABLE_V2 is written
-- only by new publications and never stores a presigned URL.
CREATE INDEX IF NOT EXISTS post_assets_stable_delivery_idx
    ON content.post_assets(asset_id, generation) WHERE delivery_contract = 'STABLE_V2';
