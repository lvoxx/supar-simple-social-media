-- Max 5 pinned posts per group — enforced in application layer, not DB
CREATE TABLE IF NOT EXISTS group_pinned_posts (
    group_id    UUID        NOT NULL,
    post_id     UUID        NOT NULL,
    pinned_by   UUID        NOT NULL,
    order_index INT         NOT NULL DEFAULT 0,
    pinned_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (group_id, post_id)
);

-- Association table: tracks which posts belong to which group
-- Populated by group-service consuming post.created Kafka events
CREATE TABLE IF NOT EXISTS group_post_associations (
    group_id   UUID        NOT NULL,
    post_id    UUID        NOT NULL,
    posted_by  UUID        NOT NULL,
    status     VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    -- ACTIVE | REMOVED
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (group_id, post_id)
);
CREATE INDEX IF NOT EXISTS idx_group_posts_group ON group_post_associations(group_id, status, created_at DESC);
