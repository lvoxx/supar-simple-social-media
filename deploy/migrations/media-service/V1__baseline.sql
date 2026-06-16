-- Baseline schema for media-service: image media metadata.
-- INFRASTRUCTURE-OWNED. Applied by the Flyway runner (docker/docker-compose.flyway.yml
-- locally; a Kubernetes migration Job in cloud) into schema `sssm` — NEVER by the app.
-- The app runs with hibernate ddl-auto=validate and only reads this schema.
-- gen_random_uuid() and now() are PostgreSQL 13+ core functions (no extension needed on PG 17).

-- One row per uploaded image. media-service stores ONLY the original object in R2; AVIF/WebP
-- variants are produced on demand by imgproxy and addressed by signed URLs, so no derived rows or
-- per-variant bytes are tracked here.
--
-- Lifecycle: a row is created PENDING when a presigned upload URL is issued, then promoted to READY
-- once the client confirms the direct-to-R2 upload (the service HEADs the object to learn its real
-- size/content-type). object_key is the R2 key and is unique. width/height are optional layout hints
-- the client may supply on completion (the service does not decode the image).
CREATE TABLE media (
    id            UUID         NOT NULL DEFAULT gen_random_uuid(),
    owner_id      UUID         NOT NULL,
    object_key    TEXT         NOT NULL,
    content_type  VARCHAR(100) NOT NULL,
    size_bytes    BIGINT,
    width         INTEGER,
    height        INTEGER,
    status        VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ,
    PRIMARY KEY (id),
    CONSTRAINT uq_media_object_key UNIQUE (object_key)
);

-- "An owner's media, newest first" with keyset pagination on (created_at, id).
CREATE INDEX idx_media_owner ON media (owner_id, created_at DESC, id DESC);
