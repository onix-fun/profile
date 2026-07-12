DROP INDEX IF EXISTS content.uq_post_likes_post_user;
DROP INDEX IF EXISTS content.uq_story_likes_story_user;
DROP INDEX IF EXISTS content.uq_comment_likes_comment_user;
DROP INDEX IF EXISTS content.uq_post_views_post_user;
DROP INDEX IF EXISTS content.uq_story_views_story_user;

ALTER TABLE content.post_likes DROP CONSTRAINT IF EXISTS post_likes_pkey;
ALTER TABLE content.story_likes DROP CONSTRAINT IF EXISTS story_likes_pkey;
ALTER TABLE content.comment_likes DROP CONSTRAINT IF EXISTS comment_likes_pkey;
ALTER TABLE content.post_views DROP CONSTRAINT IF EXISTS post_views_pkey;
ALTER TABLE content.story_views DROP CONSTRAINT IF EXISTS story_views_pkey;

CREATE UNIQUE INDEX IF NOT EXISTS uq_post_likes_post_actor
    ON content.post_likes(post_id, actor_type, actor_id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_story_likes_story_actor
    ON content.story_likes(story_id, actor_type, actor_id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_comment_likes_comment_actor
    ON content.comment_likes(comment_id, actor_type, actor_id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_post_views_post_actor
    ON content.post_views(post_id, actor_type, actor_id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_story_views_story_actor
    ON content.story_views(story_id, actor_type, actor_id);
