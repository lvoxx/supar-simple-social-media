CREATE TABLE IF NOT EXISTS verifications (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID        NOT NULL,
    type          VARCHAR(30),
    status        VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    document_url  TEXT,
    reviewed_by   UUID,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_verifications_user_id ON verifications(user_id);
