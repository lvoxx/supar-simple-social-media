CREATE TABLE IF NOT EXISTS groups (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    slug           VARCHAR(100) UNIQUE NOT NULL,
    name           VARCHAR(150) NOT NULL,
    description    TEXT,
    avatar_url     TEXT,
    background_url TEXT,
    visibility     VARCHAR(20)  NOT NULL DEFAULT 'PUBLIC',
    -- PUBLIC | PRIVATE | INVITE_ONLY
    status         VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    owner_id       UUID         NOT NULL,
    member_count   INT          NOT NULL DEFAULT 0,
    post_count     INT          NOT NULL DEFAULT 0,
    tags           TEXT[],
    category       VARCHAR(60),
    rules          JSONB,
    -- [{title, description, order}]
    policy         JSONB,
    -- {autoApproveMembers, allowMemberPost, requireJoinAnswers,
    --  minAccountAgeDays, allowMemberInvite, postApproval, maxMembers}
    settings       JSONB,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ,
    created_by     UUID,
    updated_by     UUID,
    is_deleted     BOOLEAN      NOT NULL DEFAULT FALSE,
    deleted_at     TIMESTAMPTZ,
    deleted_by     UUID
);
CREATE INDEX IF NOT EXISTS idx_groups_slug       ON groups(slug)       WHERE is_deleted = false;
CREATE INDEX IF NOT EXISTS idx_groups_owner_id   ON groups(owner_id)   WHERE is_deleted = false;
CREATE INDEX IF NOT EXISTS idx_groups_visibility ON groups(visibility)  WHERE is_deleted = false;
CREATE INDEX IF NOT EXISTS idx_groups_category   ON groups(category)   WHERE is_deleted = false;

CREATE TABLE IF NOT EXISTS group_members (
    group_id     UUID        NOT NULL,
    user_id      UUID        NOT NULL,
    role         VARCHAR(20) NOT NULL DEFAULT 'MEMBER',
    -- OWNER | ADMIN | MODERATOR | MEMBER
    status       VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    -- ACTIVE | BANNED | MUTED | PENDING_APPROVAL
    join_answers JSONB,
    invited_by   UUID,
    banned_by    UUID,
    ban_reason   TEXT,
    banned_at    TIMESTAMPTZ,
    joined_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ,
    PRIMARY KEY (group_id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_group_members_user   ON group_members(user_id, status);
CREATE INDEX IF NOT EXISTS idx_group_members_role   ON group_members(group_id, role);
