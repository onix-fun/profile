ALTER TABLE profile.service_providers
    ADD COLUMN IF NOT EXISTS grpc_target_env TEXT;

ALTER TABLE profile.service_providers
    ADD COLUMN IF NOT EXISTS frontend_base_url_env TEXT;

CREATE TABLE IF NOT EXISTS profile.provider_capabilities (
    service_key TEXT NOT NULL REFERENCES profile.service_providers(service_key),
    capability_key TEXT NOT NULL,
    operation TEXT NOT NULL,
    item_types TEXT[] NOT NULL DEFAULT '{}',
    config_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    enabled BOOLEAN NOT NULL DEFAULT true,
    PRIMARY KEY (service_key, capability_key)
);

ALTER TABLE profile.service_nav_buttons
    ADD COLUMN IF NOT EXISTS capability_key TEXT;

UPDATE profile.service_providers
SET grpc_target_env = NULL,
    frontend_base_url_env = NULL
WHERE service_key = 'profile';

UPDATE profile.service_providers
SET grpc_target_env = 'PROFILE_PROVIDER_CONTENT_GRPC_URL',
    frontend_base_url_env = 'PROFILE_CONTENT_FRONTEND_URL',
    enabled = true
WHERE service_key = 'content';

INSERT INTO profile.provider_capabilities(service_key, capability_key, operation, item_types, config_json, enabled)
VALUES
    ('content', 'posts', 'owner_section', ARRAY['post'], '{"buttonKey":"posts"}'::jsonb, true),
    ('content', 'story_archive', 'redirect', ARRAY['story'], '{"targetPathTemplate":"/stories/archive?ownerType={ownerType}&ownerId={ownerId}"}'::jsonb, true),
    ('content', 'content_search', 'search', ARRAY['post', 'comment'], '{}'::jsonb, true),
    ('content', 'content_suggest', 'suggest', ARRAY['post', 'comment', 'tag'], '{}'::jsonb, true),
    ('content', 'post_like', 'action', ARRAY['post'], '{"action":"likePost"}'::jsonb, true),
    ('content', 'post_unlike', 'action', ARRAY['post'], '{"action":"unlikePost"}'::jsonb, true),
    ('content', 'recommendations', 'action', ARRAY['post'], '{"action":"recommendationFeed"}'::jsonb, true)
ON CONFLICT (service_key, capability_key) DO UPDATE SET
    operation = EXCLUDED.operation,
    item_types = EXCLUDED.item_types,
    config_json = EXCLUDED.config_json,
    enabled = EXCLUDED.enabled;

UPDATE profile.service_nav_buttons
SET capability_key = 'collections'
WHERE button_key = 'collections';

UPDATE profile.service_nav_buttons
SET capability_key = 'posts'
WHERE button_key = 'posts';

UPDATE profile.service_nav_buttons
SET capability_key = 'story_archive'
WHERE button_key = 'story_archive';
