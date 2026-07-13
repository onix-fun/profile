CREATE TABLE IF NOT EXISTS content.post_likes (
    post_id UUID NOT NULL REFERENCES content.posts(id) ON DELETE CASCADE,
    user_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (post_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_post_likes_user_created ON content.post_likes(user_id, created_at DESC);
