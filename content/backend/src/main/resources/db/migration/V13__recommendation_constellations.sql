CREATE TABLE IF NOT EXISTS content.recommendation_constellations (
    viewer_type TEXT NOT NULL,
    viewer_id UUID NOT NULL,
    constellation_key TEXT NOT NULL,
    anchor_x DOUBLE PRECISION NOT NULL,
    anchor_y DOUBLE PRECISION NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (viewer_type, viewer_id, constellation_key)
);

CREATE TABLE IF NOT EXISTS content.recommendation_post_slots (
    viewer_type TEXT NOT NULL,
    viewer_id UUID NOT NULL,
    post_id UUID NOT NULL REFERENCES content.posts(id) ON DELETE CASCADE,
    constellation_key TEXT NOT NULL,
    salt INT NOT NULL CHECK (salt >= 0),
    world_x DOUBLE PRECISION NOT NULL,
    world_y DOUBLE PRECISION NOT NULL,
    orbit_order INT NOT NULL CHECK (orbit_order >= 0),
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (viewer_type, viewer_id, post_id),
    FOREIGN KEY (viewer_type, viewer_id, constellation_key)
        REFERENCES content.recommendation_constellations(viewer_type, viewer_id, constellation_key)
        ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_recommendation_slots_viewer_world
    ON content.recommendation_post_slots(viewer_type, viewer_id, world_x, world_y);

CREATE INDEX IF NOT EXISTS idx_recommendation_slots_viewer_constellation
    ON content.recommendation_post_slots(viewer_type, viewer_id, constellation_key, orbit_order);
