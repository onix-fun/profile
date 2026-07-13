ALTER TABLE content.posts
    ADD COLUMN IF NOT EXISTS owner_type TEXT,
    ADD COLUMN IF NOT EXISTS owner_id UUID;

UPDATE content.posts SET owner_type = 'USER' WHERE owner_type IS NULL;
UPDATE content.posts SET owner_id = author_id WHERE owner_id IS NULL;

ALTER TABLE content.posts
    ALTER COLUMN owner_type SET DEFAULT 'USER',
    ALTER COLUMN owner_type SET NOT NULL,
    ALTER COLUMN owner_id SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'chk_posts_owner_type'
          AND conrelid = 'content.posts'::regclass
    ) THEN
        ALTER TABLE content.posts
            ADD CONSTRAINT chk_posts_owner_type CHECK (owner_type IN ('USER', 'ORGANIZATION'));
    END IF;
END $$;

ALTER TABLE content.stories
    ADD COLUMN IF NOT EXISTS owner_type TEXT,
    ADD COLUMN IF NOT EXISTS owner_id UUID;

UPDATE content.stories SET owner_type = 'USER' WHERE owner_type IS NULL;
UPDATE content.stories SET owner_id = author_id WHERE owner_id IS NULL;

ALTER TABLE content.stories
    ALTER COLUMN owner_type SET DEFAULT 'USER',
    ALTER COLUMN owner_type SET NOT NULL,
    ALTER COLUMN owner_id SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'chk_stories_owner_type'
          AND conrelid = 'content.stories'::regclass
    ) THEN
        ALTER TABLE content.stories
            ADD CONSTRAINT chk_stories_owner_type CHECK (owner_type IN ('USER', 'ORGANIZATION'));
    END IF;
END $$;

ALTER TABLE content.comments
    ADD COLUMN IF NOT EXISTS owner_type TEXT,
    ADD COLUMN IF NOT EXISTS owner_id UUID;

UPDATE content.comments SET owner_type = 'USER' WHERE owner_type IS NULL;
UPDATE content.comments SET owner_id = author_id WHERE owner_id IS NULL;

ALTER TABLE content.comments
    ALTER COLUMN owner_type SET DEFAULT 'USER',
    ALTER COLUMN owner_type SET NOT NULL,
    ALTER COLUMN owner_id SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'chk_comments_owner_type'
          AND conrelid = 'content.comments'::regclass
    ) THEN
        ALTER TABLE content.comments
            ADD CONSTRAINT chk_comments_owner_type CHECK (owner_type IN ('USER', 'ORGANIZATION'));
    END IF;
END $$;

ALTER TABLE content.post_likes
    ADD COLUMN IF NOT EXISTS actor_type TEXT,
    ADD COLUMN IF NOT EXISTS actor_id UUID;

UPDATE content.post_likes SET actor_type = 'USER' WHERE actor_type IS NULL;
UPDATE content.post_likes SET actor_id = user_id WHERE actor_id IS NULL;

ALTER TABLE content.post_likes
    ALTER COLUMN actor_type SET DEFAULT 'USER',
    ALTER COLUMN actor_type SET NOT NULL,
    ALTER COLUMN actor_id SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'chk_post_likes_actor_type'
          AND conrelid = 'content.post_likes'::regclass
    ) THEN
        ALTER TABLE content.post_likes
            ADD CONSTRAINT chk_post_likes_actor_type CHECK (actor_type IN ('USER', 'ORGANIZATION'));
    END IF;
END $$;

ALTER TABLE content.story_likes
    ADD COLUMN IF NOT EXISTS actor_type TEXT,
    ADD COLUMN IF NOT EXISTS actor_id UUID;

UPDATE content.story_likes SET actor_type = 'USER' WHERE actor_type IS NULL;
UPDATE content.story_likes SET actor_id = user_id WHERE actor_id IS NULL;

ALTER TABLE content.story_likes
    ALTER COLUMN actor_type SET DEFAULT 'USER',
    ALTER COLUMN actor_type SET NOT NULL,
    ALTER COLUMN actor_id SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'chk_story_likes_actor_type'
          AND conrelid = 'content.story_likes'::regclass
    ) THEN
        ALTER TABLE content.story_likes
            ADD CONSTRAINT chk_story_likes_actor_type CHECK (actor_type IN ('USER', 'ORGANIZATION'));
    END IF;
END $$;

ALTER TABLE content.comment_likes
    ADD COLUMN IF NOT EXISTS actor_type TEXT,
    ADD COLUMN IF NOT EXISTS actor_id UUID;

UPDATE content.comment_likes SET actor_type = 'USER' WHERE actor_type IS NULL;
UPDATE content.comment_likes SET actor_id = user_id WHERE actor_id IS NULL;

ALTER TABLE content.comment_likes
    ALTER COLUMN actor_type SET DEFAULT 'USER',
    ALTER COLUMN actor_type SET NOT NULL,
    ALTER COLUMN actor_id SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'chk_comment_likes_actor_type'
          AND conrelid = 'content.comment_likes'::regclass
    ) THEN
        ALTER TABLE content.comment_likes
            ADD CONSTRAINT chk_comment_likes_actor_type CHECK (actor_type IN ('USER', 'ORGANIZATION'));
    END IF;
END $$;

ALTER TABLE content.post_views
    ADD COLUMN IF NOT EXISTS actor_type TEXT,
    ADD COLUMN IF NOT EXISTS actor_id UUID;

UPDATE content.post_views SET actor_type = 'USER' WHERE actor_type IS NULL;
UPDATE content.post_views SET actor_id = user_id WHERE actor_id IS NULL;

ALTER TABLE content.post_views
    ALTER COLUMN actor_type SET DEFAULT 'USER',
    ALTER COLUMN actor_type SET NOT NULL,
    ALTER COLUMN actor_id SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'chk_post_views_actor_type'
          AND conrelid = 'content.post_views'::regclass
    ) THEN
        ALTER TABLE content.post_views
            ADD CONSTRAINT chk_post_views_actor_type CHECK (actor_type IN ('USER', 'ORGANIZATION'));
    END IF;
END $$;

ALTER TABLE content.story_views
    ADD COLUMN IF NOT EXISTS actor_type TEXT,
    ADD COLUMN IF NOT EXISTS actor_id UUID;

UPDATE content.story_views SET actor_type = 'USER' WHERE actor_type IS NULL;
UPDATE content.story_views SET actor_id = user_id WHERE actor_id IS NULL;

ALTER TABLE content.story_views
    ALTER COLUMN actor_type SET DEFAULT 'USER',
    ALTER COLUMN actor_type SET NOT NULL,
    ALTER COLUMN actor_id SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'chk_story_views_actor_type'
          AND conrelid = 'content.story_views'::regclass
    ) THEN
        ALTER TABLE content.story_views
            ADD CONSTRAINT chk_story_views_actor_type CHECK (actor_type IN ('USER', 'ORGANIZATION'));
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_posts_owner_created ON content.posts(owner_type, owner_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_stories_owner_expires ON content.stories(owner_type, owner_id, expires_at DESC);
CREATE INDEX IF NOT EXISTS idx_comments_owner_created ON content.comments(owner_type, owner_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_post_likes_actor_created ON content.post_likes(actor_type, actor_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_story_likes_actor_created ON content.story_likes(actor_type, actor_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_comment_likes_actor_created ON content.comment_likes(actor_type, actor_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_post_views_actor_viewed ON content.post_views(actor_type, actor_id, viewed_at DESC);
CREATE INDEX IF NOT EXISTS idx_story_views_actor_viewed ON content.story_views(actor_type, actor_id, viewed_at DESC);
