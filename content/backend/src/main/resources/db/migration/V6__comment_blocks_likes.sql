CREATE TABLE IF NOT EXISTS content.comment_blocks (
    id UUID PRIMARY KEY,
    comment_id UUID NOT NULL REFERENCES content.comments(id) ON DELETE CASCADE,
    sort_order INT NOT NULL,
    block_type TEXT NOT NULL,
    data_json JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE TABLE IF NOT EXISTS content.comment_likes (
    comment_id UUID NOT NULL REFERENCES content.comments(id) ON DELETE CASCADE,
    user_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (comment_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_comment_likes_user_created
    ON content.comment_likes(user_id, created_at DESC);
