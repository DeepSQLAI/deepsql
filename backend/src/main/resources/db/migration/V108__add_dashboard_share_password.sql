-- Optional password protection for a dashboard's public link (BCrypt hash).
-- Nullable, so Hibernate ddl-auto also adds it; idempotent for safety.
ALTER TABLE saved_dashboards ADD COLUMN IF NOT EXISTS share_password_hash VARCHAR(100);
