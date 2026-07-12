ALTER TABLE content.posts
    ADD COLUMN owner_type TEXT NOT NULL DEFAULT 'USER',
    ADD COLUMN owner_id UUID;

UPDATE content.posts SET owner_id = author_id WHERE owner_id IS NULL;

ALTER TABLE content.posts
    ALTER COLUMN owner_id SET NOT NULL,
    ADD CONSTRAINT chk_posts_owner_type CHECK (owner_type IN ('USER', 'ORGANIZATION'));

ALTER TABLE content.stories
    ADD COLUMN owner_type TEXT NOT NULL DEFAULT 'USER',
    ADD COLUMN owner_id UUID;

UPDATE content.stories SET owner_id = author_id WHERE owner_id IS NULL;

ALTER TABLE content.stories
    ALTER COLUMN owner_id SET NOT NULL,
    ADD CONSTRAINT chk_stories_owner_type CHECK (owner_type IN ('USER', 'ORGANIZATION'));

ALTER TABLE content.comments
    ADD COLUMN owner_type TEXT NOT NULL DEFAULT 'USER',
    ADD COLUMN owner_id UUID;

UPDATE content.comments SET owner_id = author_id WHERE owner_id IS NULL;

ALTER TABLE content.comments
    ALTER COLUMN owner_id SET NOT NULL,
    ADD CONSTRAINT chk_comments_owner_type CHECK (owner_type IN ('USER', 'ORGANIZATION'));

ALTER TABLE content.post_likes
    ADD COLUMN actor_type TEXT NOT NULL DEFAULT 'USER',
    ADD COLUMN actor_id UUID;

UPDATE content.post_likes SET actor_id = user_id WHERE actor_id IS NULL;

ALTER TABLE content.post_likes
    ALTER COLUMN actor_id SET NOT NULL,
    ADD CONSTRAINT chk_post_likes_actor_type CHECK (actor_type IN ('USER', 'ORGANIZATION'));

ALTER TABLE content.story_likes
    ADD COLUMN actor_type TEXT NOT NULL DEFAULT 'USER',
    ADD COLUMN actor_id UUID;

UPDATE content.story_likes SET actor_id = user_id WHERE actor_id IS NULL;

ALTER TABLE content.story_likes
    ALTER COLUMN actor_id SET NOT NULL,
    ADD CONSTRAINT chk_story_likes_actor_type CHECK (actor_type IN ('USER', 'ORGANIZATION'));

ALTER TABLE content.comment_likes
    ADD COLUMN actor_type TEXT NOT NULL DEFAULT 'USER',
    ADD COLUMN actor_id UUID;

UPDATE content.comment_likes SET actor_id = user_id WHERE actor_id IS NULL;

ALTER TABLE content.comment_likes
    ALTER COLUMN actor_id SET NOT NULL,
    ADD CONSTRAINT chk_comment_likes_actor_type CHECK (actor_type IN ('USER', 'ORGANIZATION'));

ALTER TABLE content.post_views
    ADD COLUMN actor_type TEXT NOT NULL DEFAULT 'USER',
    ADD COLUMN actor_id UUID;

UPDATE content.post_views SET actor_id = user_id WHERE actor_id IS NULL;

ALTER TABLE content.post_views
    ALTER COLUMN actor_id SET NOT NULL,
    ADD CONSTRAINT chk_post_views_actor_type CHECK (actor_type IN ('USER', 'ORGANIZATION'));

ALTER TABLE content.story_views
    ADD COLUMN actor_type TEXT NOT NULL DEFAULT 'USER',
    ADD COLUMN actor_id UUID;

UPDATE content.story_views SET actor_id = user_id WHERE actor_id IS NULL;

ALTER TABLE content.story_views
    ALTER COLUMN actor_id SET NOT NULL,
    ADD CONSTRAINT chk_story_views_actor_type CHECK (actor_type IN ('USER', 'ORGANIZATION'));

CREATE INDEX IF NOT EXISTS idx_posts_owner_created ON content.posts(owner_type, owner_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_stories_owner_expires ON content.stories(owner_type, owner_id, expires_at DESC);
CREATE INDEX IF NOT EXISTS idx_comments_owner_created ON content.comments(owner_type, owner_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_post_likes_actor_created ON content.post_likes(actor_type, actor_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_story_likes_actor_created ON content.story_likes(actor_type, actor_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_comment_likes_actor_created ON content.comment_likes(actor_type, actor_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_post_views_actor_viewed ON content.post_views(actor_type, actor_id, viewed_at DESC);
CREATE INDEX IF NOT EXISTS idx_story_views_actor_viewed ON content.story_views(actor_type, actor_id, viewed_at DESC);
