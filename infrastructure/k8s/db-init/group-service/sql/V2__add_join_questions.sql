CREATE TABLE IF NOT EXISTS group_join_questions (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    group_id     UUID        NOT NULL,
    question     TEXT        NOT NULL,
    order_index  INT         NOT NULL,
    is_required  BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_join_questions_group ON group_join_questions(group_id);

CREATE TABLE IF NOT EXISTS group_join_requests (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    group_id      UUID        NOT NULL,
    requester_id  UUID        NOT NULL,
    answers       JSONB,
    status        VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    -- PENDING | APPROVED | REJECTED
    reviewed_by   UUID,
    reviewed_at   TIMESTAMPTZ,
    reject_reason TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_join_requests_group       ON group_join_requests(group_id, status);
CREATE INDEX IF NOT EXISTS idx_join_requests_requester   ON group_join_requests(requester_id, status);
