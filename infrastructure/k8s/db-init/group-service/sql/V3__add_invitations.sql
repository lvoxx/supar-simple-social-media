CREATE TABLE IF NOT EXISTS group_invitations (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    group_id    UUID        NOT NULL,
    inviter_id  UUID        NOT NULL,
    invitee_id  UUID        NOT NULL,
    status      VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    -- PENDING | ACCEPTED | DECLINED | EXPIRED
    expires_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_invitations_invitee ON group_invitations(invitee_id, status);
CREATE INDEX IF NOT EXISTS idx_invitations_group   ON group_invitations(group_id, status);
