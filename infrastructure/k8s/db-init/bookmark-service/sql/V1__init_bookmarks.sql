-- Bookmark collections (user-managed groupings)
CREATE TABLE IF NOT EXISTS bookmark_collections (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID         NOT NULL,
    name        VARCHAR(100) NOT NULL,
    description TEXT,
    color       VARCHAR(7),
    -- hex color, e.g. "#FF5733"
    icon        VARCHAR(50),
    -- emoji or icon key
    is_default  BOOLEAN      NOT NULL DEFAULT FALSE,
    -- "Saved" default collection, cannot be deleted
    visibility  VARCHAR(20)  NOT NULL DEFAULT 'PRIVATE',
    -- PRIVATE | PUBLIC_LINK
    post_count  INT          NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ,
    is_deleted  BOOLEAN      NOT NULL DEFAULT FALSE,
    deleted_at  TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_bookmark_collections_user ON bookmark_collections(user_id) WHERE is_deleted = false;

-- Bookmark entries: one row per (post, user) pair
-- Denormalised post fields avoid cross-service joins at query time
CREATE TABLE IF NOT EXISTS bookmarks (
    post_id              UUID        NOT NULL,
    user_id              UUID        NOT NULL,
    collection_id        UUID        NOT NULL,
    -- app-enforced ref to bookmark_collections; no FK constraint
    note                 TEXT,
    saved_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    post_author_id       UUID        NOT NULL,
    post_content_preview TEXT,
    -- denormalised snippet ≤ 280 chars; set to '[Post đã bị xoá]' on post.deleted
    post_media_thumb     TEXT,
    -- denormalised CloudFront CDN URL of first media thumbnail
    post_created_at      TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (post_id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_bookmarks_user_collection      ON bookmarks(user_id, collection_id);
CREATE INDEX IF NOT EXISTS idx_bookmarks_user_saved_at        ON bookmarks(user_id, saved_at DESC);
CREATE INDEX IF NOT EXISTS idx_bookmarks_collection_post_date ON bookmarks(collection_id, post_created_at DESC);
-- Full-text search on preview content
CREATE INDEX IF NOT EXISTS idx_bookmarks_fts ON bookmarks
    USING GIN (to_tsvector('simple', COALESCE(post_content_preview, '')));
