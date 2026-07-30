ALTER TABLE slack_link_code
    ADD COLUMN IF NOT EXISTS encrypted_code BYTEA;

ALTER TABLE slack_link_code
    ALTER COLUMN expires_at DROP NOT NULL;
