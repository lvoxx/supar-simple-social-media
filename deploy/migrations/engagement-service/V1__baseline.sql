-- Baseline schema for engagement-service: durable snapshot of the live Redis engagement counters.
-- INFRASTRUCTURE-OWNED. Applied by the Flyway runner (docker/docker-compose.flyway.yml locally;
-- a Kubernetes migration Job in cloud) into schema `sssm` — NEVER by the app. The Go app connects
-- read/write to the table it OWNS here; it never runs migrations.
-- gen_random_uuid() and now() are PostgreSQL 13+ core functions (no extension needed on PG 17).

-- One row per post that has accumulated engagement. These high-throughput counters (incl. views,
-- which post-service does not store on the post row) are periodically flushed here from Redis so they
-- survive eviction/restart and are queryable in bulk/joins. They are DISTINCT from post-service's
-- transactional like/repost/bookmark display counts on sssm.posts and feed ranking in Phase 3.
CREATE TABLE post_metrics (
    post_id    UUID        NOT NULL,
    views      BIGINT      NOT NULL DEFAULT 0,
    likes      BIGINT      NOT NULL DEFAULT 0,
    reposts    BIGINT      NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (post_id)
);
