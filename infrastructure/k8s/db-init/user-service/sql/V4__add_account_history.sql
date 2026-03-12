CREATE TABLE IF NOT EXISTS account_history (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID        NOT NULL,
    action      VARCHAR(50) NOT NULL,
    detail      JSONB,
    ip          INET,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_account_history_user_id ON account_history(user_id);
