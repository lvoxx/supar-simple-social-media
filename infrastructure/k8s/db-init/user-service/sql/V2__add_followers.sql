CREATE TABLE IF NOT EXISTS followers (
    follower_id   UUID        NOT NULL,
    following_id  UUID        NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (follower_id, following_id)
);
CREATE INDEX IF NOT EXISTS idx_followers_following_id ON followers(following_id);
