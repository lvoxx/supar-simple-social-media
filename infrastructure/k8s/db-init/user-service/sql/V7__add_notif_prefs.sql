ALTER TABLE users
    ADD COLUMN IF NOT EXISTS fcm_token    TEXT,
    ADD COLUMN IF NOT EXISTS apns_token   TEXT,
    ADD COLUMN IF NOT EXISTS push_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS email_enabled BOOLEAN NOT NULL DEFAULT TRUE;

CREATE TABLE IF NOT EXISTS user_settings (
    user_id           UUID        PRIMARY KEY,
    read_receipts     BOOLEAN     NOT NULL DEFAULT TRUE,
    online_status     BOOLEAN     NOT NULL DEFAULT TRUE,
    notification_level VARCHAR(20) NOT NULL DEFAULT 'ALL'
);
