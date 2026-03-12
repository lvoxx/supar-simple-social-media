CREATE KEYSPACE IF NOT EXISTS sssm_comments
    WITH replication = {'class': 'NetworkTopologyStrategy', 'datacenter1': 3}
    AND durable_writes = true;

USE sssm_comments;

-- All comments for a post (top-level and replies), partition by post_id
CREATE TABLE IF NOT EXISTS comments_by_post (
    post_id           UUID,
    comment_id        TIMEUUID,
    parent_comment_id UUID,
    root_comment_id   UUID,
    author_id         UUID,
    author_username   TEXT,
    author_avatar_url TEXT,
    content           TEXT,
    depth             INT,
    status            TEXT,
    -- ACTIVE | HIDDEN | DELETED | SPAM_HIDDEN | POST_DELETED
    is_deleted        BOOLEAN,
    deleted_at        TIMESTAMP,
    deleted_by        UUID,
    media_ids         LIST<UUID>,
    created_at        TIMESTAMP,
    updated_at        TIMESTAMP,
    PRIMARY KEY (post_id, comment_id)
) WITH CLUSTERING ORDER BY (comment_id DESC);

-- Reverse lookup: replies for a given parent comment
CREATE TABLE IF NOT EXISTS comments_by_parent (
    parent_comment_id UUID,
    comment_id        TIMEUUID,
    post_id           UUID,
    author_id         UUID,
    author_username   TEXT,
    content           TEXT,
    depth             INT,
    is_deleted        BOOLEAN,
    created_at        TIMESTAMP,
    PRIMARY KEY (parent_comment_id, comment_id)
) WITH CLUSTERING ORDER BY (comment_id DESC);

-- Efficient paging for depth-0 (top-level) comments only
CREATE TABLE IF NOT EXISTS top_comments_by_post (
    post_id    UUID,
    comment_id TIMEUUID,
    author_id  UUID,
    content    TEXT,
    is_deleted BOOLEAN,
    created_at TIMESTAMP,
    PRIMARY KEY (post_id, comment_id)
) WITH CLUSTERING ORDER BY (comment_id DESC);

-- Reaction dedup: 1 reaction type per user per comment (PUT semantics)
CREATE TABLE IF NOT EXISTS comment_reactions (
    comment_id    UUID,
    user_id       UUID,
    post_id       UUID,
    reaction_type TEXT,
    -- LIKE | LOVE | HAHA | WOW | SAD | ANGRY
    reacted_at    TIMESTAMP,
    PRIMARY KEY (comment_id, user_id)
);

-- Atomic reaction + reply counters (Cassandra requires separate table for COUNTERs)
CREATE TABLE IF NOT EXISTS comment_counters (
    comment_id   UUID PRIMARY KEY,
    reply_count  COUNTER,
    like_count   COUNTER,
    love_count   COUNTER,
    haha_count   COUNTER,
    wow_count    COUNTER,
    sad_count    COUNTER,
    angry_count  COUNTER
);

-- View counters use time-buffered flush strategy; kept separate per Cassandra COUNTER rules
CREATE TABLE IF NOT EXISTS comment_view_counters (
    comment_id UUID PRIMARY KEY,
    view_count COUNTER
);
