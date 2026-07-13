ALTER TABLE profile.service_nav_buttons
    ADD COLUMN IF NOT EXISTS mode TEXT NOT NULL DEFAULT 'canvas';

ALTER TABLE profile.service_nav_buttons
    ADD COLUMN IF NOT EXISTS target_service TEXT;

ALTER TABLE profile.service_nav_buttons
    ADD COLUMN IF NOT EXISTS target_path_template TEXT;

UPDATE profile.service_nav_buttons
SET mode = 'canvas',
    kind = 'collections',
    target_service = NULL,
    target_path_template = NULL,
    frontend_route_template = NULL,
    backend_operation = 'collections'
WHERE button_key = 'collections';

UPDATE profile.service_nav_buttons
SET mode = 'canvas',
    kind = 'section',
    target_service = NULL,
    target_path_template = NULL,
    frontend_route_template = NULL,
    backend_operation = 'posts'
WHERE button_key = 'posts';

UPDATE profile.service_nav_buttons
SET mode = 'redirect',
    kind = 'redirect',
    target_service = 'content',
    target_path_template = '/stories/archive?ownerType={ownerType}&ownerId={ownerId}',
    frontend_route_template = NULL,
    backend_operation = 'story_archive'
WHERE button_key = 'story_archive';
