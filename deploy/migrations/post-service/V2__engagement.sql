-- V2 — post engagements (like / repost / bookmark) for post-service Slice 2.
-- INFRASTRUCTURE-OWNED. Applied by the Flyway runner (docker/docker-compose.flyway.yml locally;
-- a Kubernetes migration Job in cloud) into schema `sssm` — NEVER by the app, which runs with
-- hibernate ddl-auto=validate and only reads this schema.

-- One row per (post, actor, kind): the row's existence IS the engagement, so the composite primary
-- key makes like/repost/bookmark idempotent (a second like is a no-op, not a double count). `type`
-- holds the kind (LIKE / REPOST / BOOKMARK); the add-vs-remove distinction lives only in the emitted
-- PostEngagement event, not here. Like reply_to_post_id in V1, post_id references a post by id only —
-- no FK is enforced (posts is RANGE-partitioned), and the post's denormalized like/repost/bookmark
-- counts are kept in sync transactionally by the app in the same tx as the outbox event.
CREATE TABLE post_engagements (
    post_id    UUID        NOT NULL,
    actor_id   UUID        NOT NULL,
    type       VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (post_id, actor_id, type)
);

-- "What has actor X liked/reposted/bookmarked, newest first" (e.g. the bookmarks list). The PK
-- already serves the per-post idempotency check and "who engaged with post X" prefix scans.
CREATE INDEX idx_post_engagements_actor ON post_engagements (actor_id, type, created_at DESC);
