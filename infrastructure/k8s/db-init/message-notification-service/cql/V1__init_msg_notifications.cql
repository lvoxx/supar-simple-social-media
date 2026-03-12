CREATE KEYSPACE IF NOT EXISTS sssm_msg_notifications
    WITH replication = {'class': 'NetworkTopologyStrategy', 'datacenter1': 3}
    AND durable_writes = true;

USE sssm_msg_notifications;

CREATE TABLE IF NOT EXISTS device_push_tokens (
    user_id       UUID,
    device_id     UUID,
    platform      TEXT,
    -- FCM_ANDROID | APNS_IOS | WEB_PUSH
    token         TEXT,
    app_version   TEXT,
    registered_at TIMESTAMP,
    last_active   TIMESTAMP,
    is_active     BOOLEAN DEFAULT TRUE,
    PRIMARY KEY (user_id, device_id)
);

-- Delivery audit log, TTL 30 days, append-only
CREATE TABLE IF NOT EXISTS message_notification_log (
    user_id          UUID,
    notification_id  TIMEUUID,
    conversation_id  UUID,
    message_id       UUID,
    channel          TEXT,
    -- FCM_ANDROID | APNS_IOS | WEB_PUSH | SKIPPED
    status           TEXT,
    -- PENDING | SENT | FAILED | SKIPPED
    sent_at          TIMESTAMP,
    failure_reason   TEXT,
    retry_count      INT DEFAULT 0,
    PRIMARY KEY (user_id, notification_id)
) WITH CLUSTERING ORDER BY (notification_id DESC)
  AND default_time_to_live = 2592000;
  -- 30 days TTL
