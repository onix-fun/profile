-- New stories keep only Media v2 identity in their block payload. This index
-- makes the authorized stable delivery route independent of story volume.
CREATE INDEX IF NOT EXISTS idx_story_blocks_asset_id
    ON content.story_blocks ((data_json ->> 'assetId'))
    WHERE data_json ? 'assetId';
