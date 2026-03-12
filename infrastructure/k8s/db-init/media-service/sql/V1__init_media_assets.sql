CREATE TABLE IF NOT EXISTS media_assets (
    id                   UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id             UUID        NOT NULL,
    owner_type           VARCHAR(20) NOT NULL,
    -- USER | POST | COMMENT | MESSAGE | GROUP
    original_filename    TEXT,
    content_type         VARCHAR(100),
    file_size_bytes      BIGINT,
    processed_size_bytes BIGINT,
    s3_key               TEXT        NOT NULL,
    s3_bucket            TEXT        NOT NULL,
    cdn_url              TEXT        NOT NULL,
    thumbnail_s3_key     TEXT,
    thumbnail_url        TEXT,
    width                INT,
    height               INT,
    duration_seconds     INT,
    status               VARCHAR(20) NOT NULL DEFAULT 'PROCESSING',
    -- PROCESSING | READY | REJECTED | DELETED
    rejection_reason     TEXT,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ,
    created_by           UUID,
    updated_by           UUID,
    is_deleted           BOOLEAN     NOT NULL DEFAULT FALSE,
    deleted_at           TIMESTAMPTZ,
    deleted_by           UUID
);
CREATE INDEX IF NOT EXISTS idx_media_assets_owner  ON media_assets(owner_id, owner_type) WHERE is_deleted = false;
CREATE INDEX IF NOT EXISTS idx_media_assets_status ON media_assets(status)               WHERE is_deleted = false;
