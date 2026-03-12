-- Audit log for member management actions (append-only, never updated or deleted)
CREATE TABLE IF NOT EXISTS group_member_activity (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    group_id   UUID        NOT NULL,
    actor_id   UUID        NOT NULL,
    target_id  UUID,
    action     VARCHAR(50) NOT NULL,
    -- BAN | UNBAN | PROMOTE | DEMOTE | APPROVE | REJECT | REMOVE
    detail     JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_group_activity_group ON group_member_activity(group_id, created_at DESC);
