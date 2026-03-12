CREATE TABLE IF NOT EXISTS follow_requests (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    requester_id  UUID        NOT NULL,
    target_id     UUID        NOT NULL,
    status        VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_follow_req_unique ON follow_requests(requester_id, target_id) WHERE status = 'PENDING';
