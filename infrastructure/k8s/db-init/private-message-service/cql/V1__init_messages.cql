CREATE KEYSPACE IF NOT EXISTS sssm_messages
    WITH replication = {'class': 'NetworkTopologyStrategy', 'datacenter1': 3}
    AND durable_writes = true;

USE sssm_messages;

CREATE TABLE IF NOT EXISTS conversations (
    conversation_id UUID       PRIMARY KEY,
    type            TEXT,
    -- DIRECT | GROUP_CHAT | GROUP_CHANNEL
    name            TEXT,
    avatar_url      TEXT,
    group_id        UUID,
    created_by      UUID,
    created_at      TIMESTAMP,
    updated_at      TIMESTAMP,
    settings        TEXT,
    -- JSON: global conversation settings
    is_deleted      BOOLEAN DEFAULT FALSE
);

CREATE TABLE IF NOT EXISTS conversation_participants (
    conversation_id       UUID,
    user_id               UUID,
    role                  TEXT DEFAULT 'MEMBER',
    -- OWNER | ADMIN | MEMBER
    status                TEXT DEFAULT 'ACTIVE',
    -- ACTIVE | LEFT | REMOVED | MUTED
    joined_at             TIMESTAMP,
    last_read_message_id  UUID,
    last_read_at          TIMESTAMP,
    notification_settings TEXT,
    -- JSON per-conversation settings: muteUntil, notifyOn, messagePreview, theme, nickname
    PRIMARY KEY (conversation_id, user_id)
);
CREATE INDEX IF NOT EXISTS ON conversation_participants (user_id);

-- Reverse lookup: all conversations for a user, sorted by latest message
CREATE TABLE IF NOT EXISTS conversations_by_user (
    user_id           UUID,
    last_message_at   TIMESTAMP,
    conversation_id   UUID,
    conversation_type TEXT,
    unread_count      INT,
    is_muted          BOOLEAN,
    PRIMARY KEY (user_id, last_message_at, conversation_id)
) WITH CLUSTERING ORDER BY (last_message_at DESC, conversation_id ASC);

CREATE TABLE IF NOT EXISTS messages (
    conversation_id                UUID,
    message_id                     TIMEUUID,
    sender_id                      UUID,
    message_type                   TEXT,
    -- TEXT | IMAGE | VIDEO | AUDIO | FILE | STICKER | FORWARDED | SYSTEM
    content                        TEXT,
    media_ids                      LIST<UUID>,
    forwarded_from_message_id      UUID,
    forwarded_from_conversation_id UUID,
    reply_to_message_id            UUID,
    status                         TEXT,
    -- SENT | DELIVERED | READ | FAILED | DELETED
    is_deleted                     BOOLEAN DEFAULT FALSE,
    deleted_at                     TIMESTAMP,
    deleted_by                     UUID,
    edited_at                      TIMESTAMP,
    metadata                       TEXT,
    -- JSON: {fileName, fileSize, duration}
    created_at                     TIMESTAMP,
    PRIMARY KEY (conversation_id, message_id)
) WITH CLUSTERING ORDER BY (message_id DESC);

-- 1 reaction per user per message (PUT semantics: changing emoji overwrites)
CREATE TABLE IF NOT EXISTS message_reactions (
    conversation_id UUID,
    message_id      TIMEUUID,
    user_id         UUID,
    emoji           TEXT,
    reacted_at      TIMESTAMP,
    PRIMARY KEY (conversation_id, message_id, user_id)
);

-- Read receipts: tracks per-user read position per message
-- Only written when user.settings.readReceipts = true (WS broadcast check is separate)
CREATE TABLE IF NOT EXISTS message_read_receipts (
    conversation_id UUID,
    message_id      TIMEUUID,
    user_id         UUID,
    read_at         TIMESTAMP,
    PRIMARY KEY (conversation_id, message_id, user_id)
);
