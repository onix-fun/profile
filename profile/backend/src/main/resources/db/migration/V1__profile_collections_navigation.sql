CREATE SCHEMA IF NOT EXISTS profile;

CREATE TABLE IF NOT EXISTS profile.collections (
    id UUID PRIMARY KEY,
    owner_type TEXT NOT NULL,
    owner_id UUID NOT NULL,
    title TEXT NOT NULL,
    description TEXT,
    cover_json JSONB,
    visibility TEXT NOT NULL DEFAULT 'PRIVATE',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_profile_collections_owner_type CHECK (owner_type IN ('USER', 'ORGANIZATION')),
    CONSTRAINT chk_profile_collections_visibility CHECK (visibility IN ('PUBLIC', 'PRIVATE')),
    CONSTRAINT chk_profile_collections_title_not_blank CHECK (length(trim(title)) > 0)
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_profile_collections_owner_title
    ON profile.collections(owner_type, owner_id, lower(title));

CREATE INDEX IF NOT EXISTS idx_profile_collections_owner_updated
    ON profile.collections(owner_type, owner_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS profile.collection_items (
    collection_id UUID NOT NULL REFERENCES profile.collections(id) ON DELETE CASCADE,
    service_key TEXT NOT NULL,
    item_type TEXT NOT NULL,
    item_id UUID NOT NULL,
    added_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (collection_id, service_key, item_type, item_id)
);

CREATE INDEX IF NOT EXISTS idx_profile_collection_items_collection_added
    ON profile.collection_items(collection_id, added_at DESC);

CREATE INDEX IF NOT EXISTS idx_profile_collection_items_ref
    ON profile.collection_items(service_key, item_type, item_id);

CREATE TABLE IF NOT EXISTS profile.service_providers (
    service_key TEXT PRIMARY KEY,
    display_name TEXT NOT NULL,
    grpc_target_env TEXT,
    frontend_base_url_env TEXT,
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS profile.provider_capabilities (
    service_key TEXT NOT NULL REFERENCES profile.service_providers(service_key),
    capability_key TEXT NOT NULL,
    operation TEXT NOT NULL,
    item_types TEXT[] NOT NULL DEFAULT '{}',
    config_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    enabled BOOLEAN NOT NULL DEFAULT true,
    PRIMARY KEY (service_key, capability_key)
);

CREATE TABLE IF NOT EXISTS profile.service_nav_buttons (
    button_key TEXT PRIMARY KEY,
    service_key TEXT NOT NULL REFERENCES profile.service_providers(service_key),
    feature_key TEXT NOT NULL,
    capability_key TEXT,
    label TEXT NOT NULL,
    icon TEXT NOT NULL,
    color TEXT NOT NULL,
    mode TEXT NOT NULL DEFAULT 'canvas',
    kind TEXT NOT NULL,
    frontend_route_template TEXT,
    target_service TEXT,
    target_path_template TEXT,
    backend_operation TEXT,
    sort_order INT NOT NULL,
    requires_usage BOOLEAN NOT NULL DEFAULT true,
    enabled BOOLEAN NOT NULL DEFAULT true
);

CREATE TABLE IF NOT EXISTS profile.owner_service_usage (
    owner_type TEXT NOT NULL,
    owner_id UUID NOT NULL,
    service_key TEXT NOT NULL REFERENCES profile.service_providers(service_key),
    feature_key TEXT NOT NULL,
    first_used_at TIMESTAMPTZ NOT NULL,
    last_used_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (owner_type, owner_id, service_key, feature_key),
    CONSTRAINT chk_profile_owner_usage_owner_type CHECK (owner_type IN ('USER', 'ORGANIZATION'))
);

INSERT INTO profile.service_providers(service_key, display_name, grpc_target_env, frontend_base_url_env)
VALUES
    ('profile', 'Profile', NULL, NULL),
    ('content', 'Content', 'PROFILE_PROVIDER_CONTENT_GRPC_URL', 'PROFILE_CONTENT_FRONTEND_URL')
ON CONFLICT (service_key) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    grpc_target_env = EXCLUDED.grpc_target_env,
    frontend_base_url_env = EXCLUDED.frontend_base_url_env,
    enabled = true;

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

INSERT INTO profile.service_nav_buttons(
    button_key, service_key, feature_key, capability_key, label, icon, color, kind,
    mode, frontend_route_template, target_service, target_path_template, backend_operation, sort_order, requires_usage, enabled
) VALUES
    ('collections', 'profile', 'collections', 'collections', 'Collections', 'pi pi-bookmark', '#111827', 'collections', 'canvas', NULL, NULL, NULL, 'collections', 10, false, true),
    ('posts', 'content', 'posts', 'posts', 'Posts', 'pi pi-th-large', '#111827', 'section', 'canvas', NULL, NULL, NULL, 'posts', 20, true, true),
    ('story_archive', 'content', 'story_archive', 'story_archive', 'Archive', 'pi pi-history', '#22c55e', 'redirect', 'redirect', NULL, 'content', '/stories/archive?ownerType={ownerType}&ownerId={ownerId}', 'story_archive', 30, true, true)
ON CONFLICT (button_key) DO UPDATE SET
    service_key = EXCLUDED.service_key,
    feature_key = EXCLUDED.feature_key,
    capability_key = EXCLUDED.capability_key,
    label = EXCLUDED.label,
    icon = EXCLUDED.icon,
    color = EXCLUDED.color,
    mode = EXCLUDED.mode,
    kind = EXCLUDED.kind,
    frontend_route_template = EXCLUDED.frontend_route_template,
    target_service = EXCLUDED.target_service,
    target_path_template = EXCLUDED.target_path_template,
    backend_operation = EXCLUDED.backend_operation,
    sort_order = EXCLUDED.sort_order,
    requires_usage = EXCLUDED.requires_usage,
    enabled = EXCLUDED.enabled;
