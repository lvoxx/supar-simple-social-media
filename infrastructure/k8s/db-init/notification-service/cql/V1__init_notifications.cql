CREATE KEYSPACE IF NOT EXISTS sssm_notifications
    WITH replication = {'class': 'NetworkTopologyStrategy', 'datacenter1': 3}
    AND durable_writes = true;

USE sssm_notifications;

CREATE TABLE IF NOT EXISTS notifications_by_user (
    user_id          UUID,
    notification_id  TIMEUUID,
    type             TEXT,
    actor_id         UUID,
    actor_username   TEXT,
    actor_avatar_url TEXT,
    entity_type      TEXT,
    entity_id        UUID,
    message          TEXT,
    deep_link        TEXT,
    is_read          BOOLEAN   DEFAULT FALSE,
    is_deleted       BOOLEAN   DEFAULT FALSE,
    delivered_at     TIMESTAMP,
    created_at       TIMESTAMP,
    PRIMARY KEY (user_id, notification_id)
) WITH CLUSTERING ORDER BY (notification_id DESC)
  AND default_time_to_live = 7776000;
  -- 90 days TTL

CREATE TABLE IF NOT EXISTS device_sessions (
    user_id     UUID,
    device_id   UUID,
    pod_id      TEXT,
    platform    TEXT,
    last_active TIMESTAMP,
    PRIMARY KEY (user_id, device_id)
);
