-- Content v2 is a deliberate clean slate.  Stories and collection definitions
-- survive, while all former project graph data and collection memberships go.
TRUNCATE TABLE content.posts CASCADE;
TRUNCATE TABLE content.recommendation_constellations CASCADE;
DELETE FROM content.media_references WHERE owner_type IN ('post', 'comment');
DELETE FROM content.outbox_events WHERE collection IN ('posts', 'comments', 'post', 'comment');

ALTER TABLE content.posts
    ADD COLUMN IF NOT EXISTS pinned_comment_id UUID NULL;

ALTER TABLE content.posts
    DROP CONSTRAINT IF EXISTS posts_pinned_comment_id_fkey;

ALTER TABLE content.posts
    ADD CONSTRAINT posts_pinned_comment_id_fkey
    FOREIGN KEY (pinned_comment_id) REFERENCES content.comments(id) ON DELETE SET NULL;

ALTER TABLE content.comments
    ADD COLUMN IF NOT EXISTS edited_at TIMESTAMPTZ NULL;

CREATE TABLE IF NOT EXISTS content.post_assets (
    id TEXT PRIMARY KEY,
    post_id UUID NOT NULL REFERENCES content.posts(id) ON DELETE CASCADE,
    sort_order INT NOT NULL,
    asset_kind TEXT NOT NULL,
    source_kind TEXT NOT NULL,
    asset_id TEXT,
    source_url TEXT,
    provider TEXT,
    status TEXT NOT NULL,
    variants_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    poster_url TEXT,
    waveform_url TEXT,
    width INT,
    height INT,
    duration_ms BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_post_assets_kind CHECK (asset_kind IN ('IMAGE', 'VIDEO', 'AUDIO', 'EMBED')),
    CONSTRAINT chk_post_assets_source CHECK (source_kind IN ('UPLOAD', 'EXTERNAL', 'EMBED')),
    CONSTRAINT chk_post_assets_status CHECK (status IN ('UPLOADING', 'PROCESSING', 'READY', 'FAILED')),
    CONSTRAINT uq_post_assets_order UNIQUE (post_id, sort_order)
);

CREATE INDEX IF NOT EXISTS idx_post_assets_post_order
    ON content.post_assets(post_id, sort_order);

CREATE TABLE IF NOT EXISTS content.comment_assets (
    id TEXT PRIMARY KEY,
    comment_id UUID NOT NULL REFERENCES content.comments(id) ON DELETE CASCADE,
    sort_order INT NOT NULL,
    asset_kind TEXT NOT NULL,
    asset_id TEXT NOT NULL,
    status TEXT NOT NULL,
    variants_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    poster_url TEXT,
    width INT,
    height INT,
    duration_ms BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_comment_assets_kind CHECK (asset_kind IN ('IMAGE', 'VIDEO')),
    CONSTRAINT chk_comment_assets_status CHECK (status IN ('UPLOADING', 'PROCESSING', 'READY', 'FAILED')),
    CONSTRAINT uq_comment_assets_order UNIQUE (comment_id, sort_order)
);

CREATE INDEX IF NOT EXISTS idx_comment_assets_comment_order
    ON content.comment_assets(comment_id, sort_order);

CREATE INDEX IF NOT EXISTS idx_comments_post_parent_created
    ON content.comments(post_id, parent_id, created_at DESC, id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_comments_one_pinned_root_per_post
    ON content.comments(post_id)
    WHERE parent_id IS NULL AND pinned_at IS NOT NULL AND status = 'ACTIVE';
