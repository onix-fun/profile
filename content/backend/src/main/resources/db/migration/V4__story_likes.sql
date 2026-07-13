CREATE TABLE IF NOT EXISTS content.story_likes (
    story_id UUID NOT NULL REFERENCES content.stories(id) ON DELETE CASCADE,
    user_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (story_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_story_likes_user_created ON content.story_likes(user_id, created_at DESC);
