CREATE TABLE IF NOT EXISTS content.story_views (
    story_id UUID NOT NULL REFERENCES content.stories(id) ON DELETE CASCADE,
    user_id UUID NOT NULL,
    viewed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (story_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_story_views_user_viewed ON content.story_views(user_id, viewed_at DESC);
CREATE INDEX IF NOT EXISTS idx_stories_author_created ON content.stories(author_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_stories_author_archive ON content.stories(author_id, status, expires_at DESC, created_at DESC);
