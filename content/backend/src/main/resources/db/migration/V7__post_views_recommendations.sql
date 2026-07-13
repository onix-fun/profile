CREATE TABLE IF NOT EXISTS content.post_views (
    post_id UUID NOT NULL REFERENCES content.posts(id) ON DELETE CASCADE,
    user_id UUID NOT NULL,
    duration_ms BIGINT NOT NULL DEFAULT 0,
    viewed_at TIMESTAMPTZ NOT NULL,
    view_count BIGINT NOT NULL DEFAULT 1,
    PRIMARY KEY (post_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_post_views_user_viewed ON content.post_views(user_id, viewed_at DESC);
CREATE INDEX IF NOT EXISTS idx_post_views_post ON content.post_views(post_id);
