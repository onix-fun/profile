CREATE SCHEMA IF NOT EXISTS content;

CREATE TABLE IF NOT EXISTS content.posts (
    id UUID PRIMARY KEY,
    author_id UUID NOT NULL,
    title TEXT,
    text TEXT NOT NULL DEFAULT '',
    visibility TEXT NOT NULL DEFAULT 'PUBLIC',
    status TEXT NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS content.post_blocks (
    id UUID PRIMARY KEY,
    post_id UUID NOT NULL REFERENCES content.posts(id) ON DELETE CASCADE,
    sort_order INT NOT NULL,
    block_type TEXT NOT NULL,
    data_json JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE TABLE IF NOT EXISTS content.post_tags (
    post_id UUID NOT NULL REFERENCES content.posts(id) ON DELETE CASCADE,
    tag TEXT NOT NULL,
    PRIMARY KEY (post_id, tag)
);

CREATE TABLE IF NOT EXISTS content.stories (
    id UUID PRIMARY KEY,
    author_id UUID NOT NULL,
    visibility TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS content.story_blocks (
    id UUID PRIMARY KEY,
    story_id UUID NOT NULL REFERENCES content.stories(id) ON DELETE CASCADE,
    sort_order INT NOT NULL,
    block_type TEXT NOT NULL,
    data_json JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE TABLE IF NOT EXISTS content.comments (
    id UUID PRIMARY KEY,
    post_id UUID NOT NULL REFERENCES content.posts(id) ON DELETE CASCADE,
    author_id UUID NOT NULL,
    parent_id UUID REFERENCES content.comments(id) ON DELETE CASCADE,
    text TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT comments_no_self_parent CHECK (parent_id IS NULL OR parent_id <> id)
);

CREATE TABLE IF NOT EXISTS content.media_references (
    id UUID PRIMARY KEY,
    owner_type TEXT NOT NULL,
    owner_id UUID NOT NULL,
    blob_id UUID NOT NULL,
    profile TEXT,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_posts_author_created ON content.posts(author_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_stories_author_expires ON content.stories(author_id, expires_at DESC);
CREATE INDEX IF NOT EXISTS idx_comments_post_created ON content.comments(post_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_post_tags_tag ON content.post_tags(tag);
