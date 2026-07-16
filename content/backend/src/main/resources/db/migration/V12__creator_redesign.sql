ALTER TABLE content.posts
    ADD COLUMN IF NOT EXISTS content_version INT NOT NULL DEFAULT 1;

ALTER TABLE content.comments
    ADD COLUMN IF NOT EXISTS pinned_at TIMESTAMPTZ NULL;

CREATE INDEX IF NOT EXISTS idx_posts_owner_drafts
    ON content.posts(owner_type, owner_id, updated_at DESC)
    WHERE status = 'DRAFT';

CREATE INDEX IF NOT EXISTS idx_comments_post_pinned
    ON content.comments(post_id, pinned_at DESC NULLS LAST, created_at DESC)
    WHERE status = 'ACTIVE';

CREATE TABLE IF NOT EXISTS content.poll_votes (
    post_id UUID NOT NULL REFERENCES content.posts(id) ON DELETE CASCADE,
    block_id TEXT NOT NULL,
    actor_type TEXT NOT NULL,
    actor_id UUID NOT NULL,
    option_id TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (post_id, block_id, actor_type, actor_id)
);

CREATE TABLE IF NOT EXISTS content.comment_reports (
    comment_id UUID NOT NULL REFERENCES content.comments(id) ON DELETE CASCADE,
    actor_type TEXT NOT NULL,
    actor_id UUID NOT NULL,
    reason TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (comment_id, actor_type, actor_id)
);

CREATE TABLE IF NOT EXISTS content.comment_viewer_hides (
    comment_id UUID NOT NULL REFERENCES content.comments(id) ON DELETE CASCADE,
    actor_type TEXT NOT NULL,
    actor_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (comment_id, actor_type, actor_id)
);

CREATE INDEX IF NOT EXISTS idx_story_archive_period
    ON content.stories(owner_type, owner_id, created_at DESC)
    WHERE status <> 'DELETED';
