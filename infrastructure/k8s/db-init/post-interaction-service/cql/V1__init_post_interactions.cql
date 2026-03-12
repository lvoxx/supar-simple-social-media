CREATE KEYSPACE IF NOT EXISTS sssm_post_interactions
    WITH replication = {'class': 'NetworkTopologyStrategy', 'datacenter1': 3}
    AND durable_writes = true;

USE sssm_post_interactions;

-- Actor list: who interacted with a post (author-only visibility)
-- Partition by (post_id, interaction_type) → scales for viral posts
-- TIMEUUID user_id provides natural time-sorted ordering
CREATE TABLE IF NOT EXISTS post_interaction_actors (
    post_id          UUID,
    interaction_type TEXT,
    -- LIKE | SHARE | BOOKMARK
    user_id          TIMEUUID,
    created_at       TIMESTAMP,
    PRIMARY KEY ((post_id, interaction_type), user_id)
) WITH CLUSTERING ORDER BY (user_id DESC)
  AND default_time_to_live = 15552000;
  -- 180 days TTL

-- Dedup check: has this user already liked/shared/bookmarked this post?
CREATE TABLE IF NOT EXISTS post_interaction_by_user (
    user_id          UUID,
    interaction_type TEXT,
    post_id          TIMEUUID,
    created_at       TIMESTAMP,
    PRIMARY KEY ((user_id, interaction_type), post_id)
) WITH CLUSTERING ORDER BY (post_id DESC)
  AND default_time_to_live = 15552000;
  -- 180 days TTL

-- Atomic interaction counters (Cassandra requires separate table for COUNTERs)
CREATE TABLE IF NOT EXISTS post_interaction_counters (
    post_id        UUID PRIMARY KEY,
    like_count     COUNTER,
    share_count    COUNTER,
    bookmark_count COUNTER
);

-- View counters use time-buffered flush strategy; kept separate per Cassandra COUNTER rules
-- Flush: Redis buffer (120s TTL) → batch-written here every 60s by ViewFlushJob
CREATE TABLE IF NOT EXISTS post_view_counters (
    post_id    UUID PRIMARY KEY,
    view_count COUNTER
);
