-- Schema khởi tạo cho integration tests user-service
-- Được load bởi Spring Boot test qua spring.sql.init.schema-locations

CREATE TABLE IF NOT EXISTS users (
    id                    UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    keycloak_id           UUID        UNIQUE NOT NULL,
    username              VARCHAR(50) UNIQUE NOT NULL,
    display_name          VARCHAR(100),
    bio                   TEXT,
    avatar_url            TEXT,
    background_url        TEXT,
    website_url           TEXT,
    location              VARCHAR(100),
    birth_date            DATE,
    is_verified           BOOLEAN     NOT NULL DEFAULT FALSE,
    is_private            BOOLEAN     NOT NULL DEFAULT FALSE,
    role                  VARCHAR(20) NOT NULL DEFAULT 'USER',
    follower_count        INT         NOT NULL DEFAULT 0,
    following_count       INT         NOT NULL DEFAULT 0,
    post_count            INT         NOT NULL DEFAULT 0,
    status                VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    theme_settings        JSONB,
    notification_settings JSONB,
    account_settings      JSONB,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ,
    created_by            UUID,
    updated_by            UUID,
    is_deleted            BOOLEAN     NOT NULL DEFAULT FALSE,
    deleted_at            TIMESTAMPTZ,
    deleted_by            UUID
);

CREATE TABLE IF NOT EXISTS followers (
    follower_id   UUID        NOT NULL,
    following_id  UUID        NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (follower_id, following_id)
);
CREATE INDEX IF NOT EXISTS idx_followers_following_id ON followers(following_id);

CREATE TABLE IF NOT EXISTS follow_requests (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    requester_id UUID        NOT NULL,
    target_id    UUID        NOT NULL,
    status       VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS account_history (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID        NOT NULL,
    action      VARCHAR(50),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
