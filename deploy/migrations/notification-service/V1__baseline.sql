-- Baseline schema for notification-service: per-user notification inbox + push device tokens.
-- INFRASTRUCTURE-OWNED. Applied by the Flyway runner (docker/docker-compose.flyway.yml locally;
-- a Kubernetes migration Job in cloud) into schema `sssm` — NEVER by the app. The Go app connects
-- read/write to the two tables it OWNS here, and READS sssm.posts (owned by post-service) to resolve
-- a post to its author. It never runs migrations.
-- gen_random_uuid() and now() are PostgreSQL 13+ core functions (no extension needed on PG 17).

-- One row per delivered notification. recipient_id is the user who receives it; actor_id is the user
-- who triggered it. post_id is the subject post — for a REPLY it is the PARENT post being replied to,
-- and reply_post_id is the new reply post. read_at is NULL until the recipient marks the inbox read.
CREATE TABLE notifications (
    id            UUID        NOT NULL DEFAULT gen_random_uuid(),
    recipient_id  UUID        NOT NULL,
    actor_id      UUID        NOT NULL,
    kind          VARCHAR(20) NOT NULL,   -- LIKE | REPOST | BOOKMARK | REPLY
    post_id       UUID,                   -- subject post (parent post for REPLY)
    reply_post_id UUID,                   -- the reply post (REPLY only)
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    read_at       TIMESTAMPTZ,
    PRIMARY KEY (id),
    -- Idempotent dedupe of Kafka at-least-once redelivery: one notification per
    -- (recipient, actor, kind, post). The Slice 1 kinds always set post_id, so NULL-distinctness in
    -- the unique index is not a concern. INSERT ... ON CONFLICT DO NOTHING relies on this.
    CONSTRAINT uq_notifications_natural UNIQUE (recipient_id, actor_id, kind, post_id)
);

-- "A recipient's inbox, newest first" with keyset pagination on (created_at, id).
CREATE INDEX idx_notifications_recipient ON notifications (recipient_id, created_at DESC, id DESC);
-- Partial index makes the unread-count and unread-badge query index-only.
CREATE INDEX idx_notifications_unread ON notifications (recipient_id) WHERE read_at IS NULL;
-- Supports post-deletion cleanup (DELETE ... WHERE post_id = ?).
CREATE INDEX idx_notifications_post ON notifications (post_id);

-- One row per registered push target. A physical device's (platform, token) is globally unique;
-- re-registration re-points the token at the current user via ON CONFLICT DO UPDATE.
CREATE TABLE device_tokens (
    id         UUID        NOT NULL DEFAULT gen_random_uuid(),
    user_id    UUID        NOT NULL,
    platform   VARCHAR(10) NOT NULL,   -- FCM | APNS
    token      TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ,
    PRIMARY KEY (id),
    CONSTRAINT uq_device_tokens_token UNIQUE (platform, token)
);

-- "A user's devices" for fan-out when the real FCM/APNs pusher lands.
CREATE INDEX idx_device_tokens_user ON device_tokens (user_id);
