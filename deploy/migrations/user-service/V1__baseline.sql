-- Baseline schema for user-service: profiles + follow graph.
-- INFRASTRUCTURE-OWNED. Applied by the Flyway runner (docker/docker-compose.flyway.yml
-- locally; a Kubernetes migration Job in cloud) into schema `sssm` — NEVER by the app.
-- The app runs with hibernate ddl-auto=validate and only reads this schema.
-- gen_random_uuid() and now() are PostgreSQL 13+ core functions (no extension needed on PG 17).

CREATE TABLE profiles (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    keycloak_id     UUID         NOT NULL UNIQUE,
    username        VARCHAR(30)  NOT NULL UNIQUE,
    display_name    VARCHAR(50)  NOT NULL,
    bio             VARCHAR(160),
    avatar_url      TEXT,
    banner_url      TEXT,
    location        VARCHAR(60),
    website         VARCHAR(120),
    verified        BOOLEAN      NOT NULL DEFAULT FALSE,
    follower_count  BIGINT       NOT NULL DEFAULT 0,
    following_count BIGINT       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT username_lowercase CHECK (username = lower(username))
);

-- Directed follow edges. Counts on `profiles` are denormalized for read speed
-- and kept in sync transactionally by user-service (and reconciled async later).
CREATE TABLE follows (
    follower_id UUID        NOT NULL REFERENCES profiles (id) ON DELETE CASCADE,
    followee_id UUID        NOT NULL REFERENCES profiles (id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (follower_id, followee_id),
    CONSTRAINT no_self_follow CHECK (follower_id <> followee_id)
);

-- PK already indexes (follower_id, *) for "who does X follow";
-- this covers the reverse "who follows X".
CREATE INDEX idx_follows_followee ON follows (followee_id);
