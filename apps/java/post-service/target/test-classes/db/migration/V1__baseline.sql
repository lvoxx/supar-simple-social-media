-- Baseline schema for post-service: posts (threaded) + transactional outbox.
-- INFRASTRUCTURE-OWNED. Applied by the Flyway runner (docker/docker-compose.flyway.yml
-- locally; a Kubernetes migration Job in cloud) into schema `sssm` — NEVER by the app.
-- The app runs with hibernate ddl-auto=validate and only reads this schema.
-- gen_random_uuid() and now() are PostgreSQL 13+ core functions (no extension needed on PG 17).

-- Posts are RANGE-partitioned by created_at: the home/profile timelines are time-ordered, so
-- range partitioning keeps recent partitions hot and lets old ones be detached/archived cheaply.
-- The partition key MUST be part of the primary key, hence PK (id, created_at). Replies reference
-- another post by id only (reply_to_post_id) — no cross-partition FK is enforced; the parent's
-- reply_count is kept in sync transactionally by the app.
CREATE TABLE posts (
    id               UUID         NOT NULL DEFAULT gen_random_uuid(),
    author_id        UUID         NOT NULL,
    text             VARCHAR(280) NOT NULL,
    reply_to_post_id UUID,
    reply_count      BIGINT       NOT NULL DEFAULT 0,
    like_count       BIGINT       NOT NULL DEFAULT 0,
    repost_count     BIGINT       NOT NULL DEFAULT 0,
    bookmark_count   BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);

-- A DEFAULT partition guarantees every insert lands somewhere even before a dated partition for
-- its month exists. Infrastructure provisions per-month partitions ahead of time (a scheduled
-- maintenance Job); the default is the safety net, not the steady-state home for hot data.
CREATE TABLE posts_default PARTITION OF posts DEFAULT;

-- Author timeline ("posts by X, newest first") with keyset pagination on (created_at, id).
CREATE INDEX idx_posts_author ON posts (author_id, created_at DESC, id DESC);
-- Thread lookup ("replies to post X"); partial to skip the many root posts.
CREATE INDEX idx_posts_reply_to ON posts (reply_to_post_id) WHERE reply_to_post_id IS NOT NULL;

-- Transactional outbox: domain events are written here in the SAME transaction as the post change,
-- then a relay (post-service @Scheduled poller) publishes unpublished rows to Kafka and stamps
-- published_at. This guarantees a post and its event are committed atomically — no lost or phantom
-- events even if Kafka is briefly unavailable. payload holds the serialized Protobuf message
-- (sssm.event.v1.PostCreated / PostDeleted) so Java and Go consumers share one wire format.
CREATE TABLE outbox_events (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type VARCHAR(50) NOT NULL,
    aggregate_id   UUID        NOT NULL,
    event_type     VARCHAR(50) NOT NULL,
    payload        BYTEA       NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ
);

-- The relay polls only unpublished rows oldest-first; a partial index keeps that scan tiny as the
-- table grows (published rows are excluded and can be pruned later).
CREATE INDEX idx_outbox_unpublished ON outbox_events (created_at) WHERE published_at IS NULL;
