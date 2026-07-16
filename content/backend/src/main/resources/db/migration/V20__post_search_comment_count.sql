ALTER TABLE content.post_search_projections
    ADD COLUMN IF NOT EXISTS comment_count INTEGER NOT NULL DEFAULT 0;
