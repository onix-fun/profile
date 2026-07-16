-- Content UI v3: persistent project layouts, dense recommendation map v2,
-- two-level discussions and a durable post search projection.

ALTER TABLE content.post_assets
    ADD COLUMN IF NOT EXISTS layout_x INT,
    ADD COLUMN IF NOT EXISTS layout_y INT,
    ADD COLUMN IF NOT EXISTS size_preset TEXT,
    ADD COLUMN IF NOT EXISTS layout_version INT;

UPDATE content.post_assets
SET layout_x = (sort_order % 4) * 420 - 630,
    layout_y = (sort_order / 4) * 420 - 420,
    size_preset = 'M',
    layout_version = 1
WHERE layout_x IS NULL OR layout_y IS NULL OR size_preset IS NULL OR layout_version IS NULL;

ALTER TABLE content.post_assets
    ALTER COLUMN layout_x SET NOT NULL,
    ALTER COLUMN layout_y SET NOT NULL,
    ALTER COLUMN size_preset SET NOT NULL,
    ALTER COLUMN layout_version SET NOT NULL;

ALTER TABLE content.post_assets DROP CONSTRAINT IF EXISTS chk_post_assets_size_preset;
ALTER TABLE content.post_assets ADD CONSTRAINT chk_post_assets_size_preset CHECK (size_preset IN ('S','M','L'));

ALTER TABLE content.comments
    ADD COLUMN IF NOT EXISTS reply_to_id UUID NULL REFERENCES content.comments(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS content_version INT NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS document_json JSONB;

WITH RECURSIVE chain AS (
    SELECT c.id AS comment_id, c.parent_id AS direct_parent_id, c.parent_id AS ancestor_id, 1 AS depth
    FROM content.comments c
    WHERE c.parent_id IS NOT NULL
    UNION ALL
    SELECT chain.comment_id, chain.direct_parent_id, parent.parent_id, chain.depth + 1
    FROM chain
    JOIN content.comments parent ON parent.id = chain.ancestor_id
    WHERE parent.parent_id IS NOT NULL
), roots AS (
    SELECT DISTINCT ON (comment_id) comment_id, direct_parent_id, ancestor_id AS root_id
    FROM chain
    ORDER BY comment_id, depth DESC
)
UPDATE content.comments comment
SET parent_id = roots.root_id,
    reply_to_id = roots.direct_parent_id
FROM roots
WHERE comment.id = roots.comment_id
  AND roots.direct_parent_id <> roots.root_id;

UPDATE content.comments
SET document_json = CASE
    WHEN text = '' THEN '{"version":1,"blocks":[]}'::jsonb
    ELSE jsonb_build_object(
        'version', 1,
        'blocks', jsonb_build_array(jsonb_build_object(
            'id', gen_random_uuid()::text,
            'type', 'PARAGRAPH',
            'content', jsonb_build_array(jsonb_build_object('text', text, 'marks', '[]'::jsonb)),
            'items', '[]'::jsonb,
            'checked', '[]'::jsonb
        ))
    )
END
WHERE document_json IS NULL;

ALTER TABLE content.comments ALTER COLUMN document_json SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_comments_reply_to ON content.comments(reply_to_id);

CREATE OR REPLACE FUNCTION content.enforce_two_level_comment_thread() RETURNS trigger AS $$
DECLARE
    parent_row content.comments%ROWTYPE;
    reply_row content.comments%ROWTYPE;
BEGIN
    IF NEW.parent_id IS NULL THEN
        IF NEW.reply_to_id IS NOT NULL THEN
            RAISE EXCEPTION 'root comment cannot have reply_to_id';
        END IF;
        RETURN NEW;
    END IF;

    SELECT * INTO parent_row FROM content.comments WHERE id = NEW.parent_id;
    IF NOT FOUND OR parent_row.post_id <> NEW.post_id OR parent_row.parent_id IS NOT NULL THEN
        RAISE EXCEPTION 'comment parent must be a root comment of the same post';
    END IF;

    IF NEW.reply_to_id IS NOT NULL THEN
        SELECT * INTO reply_row FROM content.comments WHERE id = NEW.reply_to_id;
        IF NOT FOUND OR reply_row.post_id <> NEW.post_id OR (reply_row.id <> parent_row.id AND reply_row.parent_id <> parent_row.id) THEN
            RAISE EXCEPTION 'reply target must belong to the same root thread';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS comments_two_level_guard ON content.comments;
CREATE CONSTRAINT TRIGGER comments_two_level_guard
AFTER INSERT OR UPDATE OF post_id, parent_id, reply_to_id ON content.comments
DEFERRABLE INITIALLY IMMEDIATE
FOR EACH ROW EXECUTE FUNCTION content.enforce_two_level_comment_thread();

-- Coordinates from placement v1 are intentionally discarded. They are
-- viewer-specific cache data and will be materialized densely on demand.
TRUNCATE TABLE content.recommendation_constellations CASCADE;
ALTER TABLE content.recommendation_post_slots
    ADD COLUMN IF NOT EXISTS size_preset TEXT NOT NULL DEFAULT 'M',
    ADD COLUMN IF NOT EXISTS placement_version INT NOT NULL DEFAULT 2;
ALTER TABLE content.recommendation_post_slots DROP CONSTRAINT IF EXISTS chk_recommendation_size_preset;
ALTER TABLE content.recommendation_post_slots ADD CONSTRAINT chk_recommendation_size_preset CHECK (size_preset IN ('S','M','L'));

CREATE TABLE IF NOT EXISTS content.post_search_projections (
    post_id UUID PRIMARY KEY REFERENCES content.posts(id) ON DELETE CASCADE,
    revision BIGINT NOT NULL,
    discussion_text TEXT NOT NULL DEFAULT '',
    semantic_segments_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
