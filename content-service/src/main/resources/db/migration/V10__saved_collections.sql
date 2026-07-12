CREATE TABLE IF NOT EXISTS content.collections (
    id UUID PRIMARY KEY,
    owner_type TEXT NOT NULL,
    owner_id UUID NOT NULL,
    title TEXT NOT NULL,
    description TEXT,
    cover_json JSONB,
    visibility TEXT NOT NULL DEFAULT 'PRIVATE',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_collections_owner_type CHECK (owner_type IN ('USER', 'ORGANIZATION')),
    CONSTRAINT chk_collections_visibility CHECK (visibility IN ('PUBLIC', 'PRIVATE')),
    CONSTRAINT chk_collections_title_not_blank CHECK (length(trim(title)) > 0)
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_collections_owner_title
    ON content.collections(owner_type, owner_id, lower(title));

CREATE INDEX IF NOT EXISTS idx_collections_owner_updated
    ON content.collections(owner_type, owner_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS content.collection_items (
    collection_id UUID NOT NULL REFERENCES content.collections(id) ON DELETE CASCADE,
    post_id UUID NOT NULL REFERENCES content.posts(id) ON DELETE CASCADE,
    added_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (collection_id, post_id)
);

CREATE INDEX IF NOT EXISTS idx_collection_items_collection_added
    ON content.collection_items(collection_id, added_at DESC);

CREATE INDEX IF NOT EXISTS idx_collection_items_post
    ON content.collection_items(post_id);
