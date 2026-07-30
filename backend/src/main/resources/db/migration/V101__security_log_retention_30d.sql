-- Enforce 30-day retention on user / security log tables.
--
-- Runs once at startup via Flyway to flush all records older than 30 days.
-- Ongoing purges are handled nightly by SecurityCleanupService (04:00 AM).

-- 1. Audit log — remove events older than 30 days
DELETE FROM security_event
WHERE created_at < NOW() - INTERVAL '30 days';

-- 2. User sessions — remove sessions whose refresh token expired more than 30 days ago
--    (revoked sessions are also covered once their refresh window has passed)
DELETE FROM user_session
WHERE refresh_expires_at < NOW() - INTERVAL '30 days';

-- 3. Auth login challenges — remove challenges that expired more than 30 days ago
DELETE FROM auth_login_challenge
WHERE expires_at < NOW() - INTERVAL '30 days';
