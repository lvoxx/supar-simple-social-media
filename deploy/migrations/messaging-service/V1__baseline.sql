-- Baseline schema for messaging-service: 1:1 direct-message conversations + messages.
-- INFRASTRUCTURE-OWNED. Applied by the Flyway runner (docker/docker-compose.flyway.yml locally;
-- a Kubernetes migration Job in cloud) into schema `sssm` — NEVER by the app. The Go app connects
-- read/write only to the two tables it OWNS here and runs with ddl-auto=validate semantics; it never
-- runs migrations.
-- gen_random_uuid() and now() are PostgreSQL 13+ core functions (no extension needed on PG 17).

-- One row per 1:1 conversation. A conversation is identified by its unordered participant PAIR; we
-- store it canonically ordered (user_lo < user_hi as text) so (user_lo, user_hi) is unique and the
-- same pair never yields two conversations regardless of who messages first. last_message_at is bumped
-- on every message so a user's conversation list can be ordered by recency with keyset pagination.
CREATE TABLE dm_conversations (
    id              UUID        NOT NULL DEFAULT gen_random_uuid(),
    user_lo         UUID        NOT NULL,             -- lexicographically smaller participant id
    user_hi         UUID        NOT NULL,             -- lexicographically larger participant id
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_message_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CONSTRAINT uq_dm_conversations_pair UNIQUE (user_lo, user_hi),
    CONSTRAINT ck_dm_conversations_order CHECK (user_lo < user_hi)
);

-- "A participant's conversations, most-recently-active first" with keyset pagination on
-- (last_message_at, id). A participant may be either side of the pair, so we index both columns; the
-- OR-filtered list query plans as a BitmapOr over the two indexes.
CREATE INDEX idx_dm_conversations_lo ON dm_conversations (user_lo, last_message_at DESC, id DESC);
CREATE INDEX idx_dm_conversations_hi ON dm_conversations (user_hi, last_message_at DESC, id DESC);

-- One row per message. recipient_id is denormalized (derivable from the conversation) so the live
-- pub/sub fan-out and per-user routing never need a join, and so a message carries its own addressing.
CREATE TABLE dm_messages (
    id              UUID        NOT NULL DEFAULT gen_random_uuid(),
    conversation_id UUID        NOT NULL REFERENCES dm_conversations (id) ON DELETE CASCADE,
    sender_id       UUID        NOT NULL,
    recipient_id    UUID        NOT NULL,
    body            TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id)
);

-- "A conversation's messages, newest first" with keyset pagination on (created_at, id).
CREATE INDEX idx_dm_messages_conversation ON dm_messages (conversation_id, created_at DESC, id DESC);
