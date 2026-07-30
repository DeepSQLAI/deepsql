-- V35: Add SSH tunnel support for database connections
-- Allows connecting to databases behind firewalls/VPCs via SSH bastion hosts

-- Add SSH tunnel configuration columns to encrypted_credentials table
ALTER TABLE encrypted_credentials ADD COLUMN IF NOT EXISTS ssh_enabled BOOLEAN DEFAULT FALSE;
ALTER TABLE encrypted_credentials ADD COLUMN IF NOT EXISTS ssh_auth_type VARCHAR(20) DEFAULT 'PASSWORD';
ALTER TABLE encrypted_credentials ADD COLUMN IF NOT EXISTS encrypted_ssh_host BYTEA;
ALTER TABLE encrypted_credentials ADD COLUMN IF NOT EXISTS encrypted_ssh_port BYTEA;
ALTER TABLE encrypted_credentials ADD COLUMN IF NOT EXISTS encrypted_ssh_username BYTEA;
ALTER TABLE encrypted_credentials ADD COLUMN IF NOT EXISTS encrypted_ssh_password BYTEA;
ALTER TABLE encrypted_credentials ADD COLUMN IF NOT EXISTS encrypted_ssh_private_key BYTEA;
ALTER TABLE encrypted_credentials ADD COLUMN IF NOT EXISTS encrypted_ssh_passphrase BYTEA;

-- Add comment for documentation
COMMENT ON COLUMN encrypted_credentials.ssh_enabled IS 'Whether SSH tunneling is enabled for this connection';
COMMENT ON COLUMN encrypted_credentials.ssh_auth_type IS 'SSH authentication type: PASSWORD or PRIVATE_KEY';
COMMENT ON COLUMN encrypted_credentials.encrypted_ssh_host IS 'Encrypted SSH bastion/jump host address';
COMMENT ON COLUMN encrypted_credentials.encrypted_ssh_port IS 'Encrypted SSH port (default 22)';
COMMENT ON COLUMN encrypted_credentials.encrypted_ssh_username IS 'Encrypted SSH username';
COMMENT ON COLUMN encrypted_credentials.encrypted_ssh_password IS 'Encrypted SSH password (for PASSWORD auth)';
COMMENT ON COLUMN encrypted_credentials.encrypted_ssh_private_key IS 'Encrypted SSH private key (for PRIVATE_KEY auth)';
COMMENT ON COLUMN encrypted_credentials.encrypted_ssh_passphrase IS 'Encrypted passphrase for private key (if encrypted)';
