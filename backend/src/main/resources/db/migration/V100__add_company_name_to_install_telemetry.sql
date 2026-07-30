-- V100: add company_name to installs_telemetry.
--
-- Phase-1 launched with an anonymous-only install identity (UUID + secret).
-- Operators want install-level analytics tagged with the deploying
-- organization so support, success, and product-usage workflows can map
-- install_id → real customer.
--
-- Column is NOT NULL with a sentinel default so existing rows remain valid
-- and new boots without DEEPSQL_COMPANY_NAME set don't break.
ALTER TABLE installs_telemetry
    ADD COLUMN company_name VARCHAR(128) NOT NULL DEFAULT 'unknown';

-- Backfill: if the install has exactly one admin user with a non-freemail
-- email domain, use that domain as the company name. Avoids leaving every
-- pre-V100 install permanently labelled 'unknown' without operator action.
--
-- Scoped to single-admin installs because multi-admin orgs may have mixed
-- domains and picking one would be misleading. The 'unknown' default
-- remains in those cases — operator can set DEEPSQL_COMPANY_NAME manually.
DO $$
DECLARE
    admin_count INTEGER;
    admin_email TEXT;
    email_domain TEXT;
BEGIN
    SELECT COUNT(*), MIN(email)
      INTO admin_count, admin_email
      FROM users
     WHERE role = 'ADMIN';

    IF admin_count = 1
       AND admin_email IS NOT NULL
       AND admin_email LIKE '%@%' THEN
        email_domain := lower(split_part(admin_email, '@', 2));
        IF email_domain NOT IN (
                'gmail.com', 'googlemail.com', 'yahoo.com', 'hotmail.com',
                'outlook.com', 'icloud.com', 'me.com', 'protonmail.com',
                'aol.com', 'mail.com', 'localhost', 'dba-agent.local'
           ) THEN
            UPDATE installs_telemetry
               SET company_name = email_domain
             WHERE id = 1;
        END IF;
    END IF;
END $$;
