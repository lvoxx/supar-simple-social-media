CREATE TABLE IF NOT EXISTS posts (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    author_id        UUID        NOT NULL,
    group_id         UUID,
    content          TEXT,
    repost_of_id     UUID,
    quoted_post_id   UUID,
    post_type        VARCHAR(20) NOT NULL DEFAULT 'ORIGINAL',
    -- ORIGINAL | REPOST | QUOTE | AUTO
    status           VARCHAR(20) NOT NULL DEFAULT 'PUBLISHED',
    -- DRAFT | PENDING_MEDIA | PUBLISHED | PENDING_REVIEW | HIDDEN | DELETED
    visibility       VARCHAR(20) NOT NULL DEFAULT 'PUBLIC',
    is_edited        BOOLEAN     NOT NULL DEFAULT FALSE,
    edited_at        TIMESTAMPTZ,
    is_pinned        BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ,
    created_by       UUID,
    updated_by       UUID,
    is_deleted       BOOLEAN     NOT NULL DEFAULT FALSE,
    deleted_at       TIMESTAMPTZ,
    deleted_by       UUID
);
CREATE INDEX IF NOT EXISTS idx_posts_author_id ON posts(author_id) WHERE is_deleted = false;
CREATE INDEX IF NOT EXISTS idx_posts_group_id  ON posts(group_id)  WHERE is_deleted = false;
CREATE INDEX IF NOT EXISTS idx_posts_status    ON posts(status)    WHERE is_deleted = false;

CREATE TABLE IF NOT EXISTS post_media (
    post_id    UUID NOT NULL,
    media_id   UUID NOT NULL,
    position   INT  NOT NULL DEFAULT 0,
    PRIMARY KEY (post_id, media_id)
);

CREATE TABLE IF NOT EXISTS post_hashtags (
    post_id  UUID         NOT NULL,
    hashtag  VARCHAR(100) NOT NULL,
    PRIMARY KEY (post_id, hashtag)
);

CREATE TABLE IF NOT EXISTS post_mentions (
    post_id           UUID NOT NULL,
    mentioned_user_id UUID NOT NULL,
    PRIMARY KEY (post_id, mentioned_user_id)
);

-- Audit log for post edits (append-only, never updated)
CREATE TABLE IF NOT EXISTS post_edits (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    post_id          UUID        NOT NULL,
    previous_content TEXT,
    edited_at        TIMESTAMPTZ NOT NULL,
    edited_by        UUID        NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_post_edits_post_id ON post_edits(post_id);

CREATE TABLE IF NOT EXISTS post_reports (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    post_id     UUID        NOT NULL,
    reporter_id UUID        NOT NULL,
    reason      VARCHAR(50),
    status      VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_post_reports_post_id ON post_reports(post_id);
