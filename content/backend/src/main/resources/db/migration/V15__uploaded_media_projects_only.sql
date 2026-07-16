-- Media projects no longer accept remote URLs or embeds.  Any transitional
-- rows are removed rather than letting a client-controlled URL survive in a
-- public project surface.
DELETE FROM content.post_assets
WHERE source_kind <> 'UPLOAD'
   OR asset_kind NOT IN ('IMAGE', 'VIDEO', 'AUDIO');

UPDATE content.post_assets
SET source_url = NULL,
    provider = NULL;

ALTER TABLE content.post_assets
    DROP CONSTRAINT IF EXISTS chk_post_assets_kind;

ALTER TABLE content.post_assets
    ADD CONSTRAINT chk_post_assets_kind
    CHECK (asset_kind IN ('IMAGE', 'VIDEO', 'AUDIO'));

ALTER TABLE content.post_assets
    DROP CONSTRAINT IF EXISTS chk_post_assets_source;

ALTER TABLE content.post_assets
    ADD CONSTRAINT chk_post_assets_source
    CHECK (source_kind = 'UPLOAD');
