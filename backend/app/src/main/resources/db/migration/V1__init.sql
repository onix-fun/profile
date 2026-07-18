-- Generated from the final pre-production schema for profile.
-- Data is intentionally reset; historical compatibility DDL is forbidden here.

CREATE SCHEMA IF NOT EXISTS profile;

CREATE TABLE profile.collections (
    id uuid NOT NULL,
    owner_type text NOT NULL,
    owner_id uuid NOT NULL,
    title text NOT NULL,
    description text,
    cover_json jsonb,
    visibility text DEFAULT 'PRIVATE'::text NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT chk_profile_collections_owner_type CHECK (owner_type = ANY (ARRAY['USER'::text, 'ORGANIZATION'::text])),
    CONSTRAINT chk_profile_collections_title_not_blank CHECK (length(TRIM(BOTH FROM title)) > 0),
    CONSTRAINT chk_profile_collections_visibility CHECK (visibility = ANY (ARRAY['PUBLIC'::text, 'PRIVATE'::text])),
    CONSTRAINT collections_created_at_not_null NOT NULL created_at,
    CONSTRAINT collections_id_not_null NOT NULL id,
    CONSTRAINT collections_owner_id_not_null NOT NULL owner_id,
    CONSTRAINT collections_owner_type_not_null NOT NULL owner_type,
    CONSTRAINT collections_pkey PRIMARY KEY (id),
    CONSTRAINT collections_title_not_null NOT NULL title,
    CONSTRAINT collections_updated_at_not_null NOT NULL updated_at,
    CONSTRAINT collections_visibility_not_null NOT NULL visibility
);

CREATE TABLE profile.collection_items (
    collection_id uuid NOT NULL,
    service_key text NOT NULL,
    item_type text NOT NULL,
    item_id text NOT NULL,
    added_at timestamp with time zone NOT NULL,
    CONSTRAINT collection_items_added_at_not_null NOT NULL added_at,
    CONSTRAINT collection_items_collection_id_fkey FOREIGN KEY (collection_id) REFERENCES profile.collections(id) ON DELETE CASCADE,
    CONSTRAINT collection_items_collection_id_not_null NOT NULL collection_id,
    CONSTRAINT collection_items_item_id_not_null NOT NULL item_id,
    CONSTRAINT collection_items_item_type_not_null NOT NULL item_type,
    CONSTRAINT collection_items_pkey PRIMARY KEY (collection_id, service_key, item_type, item_id),
    CONSTRAINT collection_items_service_key_not_null NOT NULL service_key
);

CREATE TABLE profile.public_profiles (
    owner_type text NOT NULL,
    owner_id uuid NOT NULL,
    username text NOT NULL,
    display_name text NOT NULL,
    bio text NOT NULL DEFAULT '',
    avatar_asset_id uuid,
    social_links jsonb NOT NULL DEFAULT '[]'::jsonb,
    revision bigint NOT NULL DEFAULT 1,
    created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT public_profiles_pkey PRIMARY KEY (owner_type, owner_id),
    CONSTRAINT public_profiles_owner_type_check CHECK (owner_type IN ('USER', 'ORGANIZATION')),
    CONSTRAINT public_profiles_revision_check CHECK (revision > 0)
);

CREATE UNIQUE INDEX public_profiles_username_uq
    ON profile.public_profiles (lower(username));

CREATE TABLE profile.outbox_events (
    event_id uuid PRIMARY KEY,
    aggregate_type text NOT NULL,
    aggregate_id uuid NOT NULL,
    revision bigint NOT NULL,
    event_type text NOT NULL,
    payload_json jsonb NOT NULL,
    status text NOT NULL DEFAULT 'PENDING',
    attempts integer NOT NULL DEFAULT 0,
    next_attempt_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    leased_until timestamp with time zone,
    last_error text,
    created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT profile_outbox_status_check CHECK (status IN ('PENDING', 'LEASED', 'DELIVERED', 'FAILED'))
);

CREATE INDEX profile_outbox_ready_idx
    ON profile.outbox_events (status, next_attempt_at, created_at);

CREATE TABLE profile.inbox_events (
    source_service text NOT NULL,
    event_id uuid NOT NULL,
    aggregate_id uuid NOT NULL,
    revision bigint NOT NULL,
    received_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT profile_inbox_pkey PRIMARY KEY (source_service, event_id)
);

CREATE INDEX idx_profile_collection_items_collection_added ON profile.collection_items USING btree (collection_id, added_at DESC);

CREATE INDEX idx_profile_collection_items_ref ON profile.collection_items USING btree (service_key, item_type, item_id);

CREATE UNIQUE INDEX idx_profile_collections_owner_title ON profile.collections USING btree (owner_type, owner_id, lower(title));

CREATE INDEX idx_profile_collections_owner_updated ON profile.collections USING btree (owner_type, owner_id, updated_at DESC);
