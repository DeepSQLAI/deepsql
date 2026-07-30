DO $$
DECLARE
    column_type text;
BEGIN
    SELECT data_type
    INTO column_type
    FROM information_schema.columns
    WHERE table_schema = 'public'
      AND table_name = 'slack_link_code'
      AND column_name = 'encrypted_code';

    IF column_type IS NULL THEN
        ALTER TABLE slack_link_code
            ADD COLUMN encrypted_code BYTEA;
    ELSIF column_type = 'oid' THEN
        EXECUTE '
            ALTER TABLE slack_link_code
            ALTER COLUMN encrypted_code TYPE BYTEA
            USING CASE
                WHEN encrypted_code IS NULL THEN NULL
                ELSE lo_get(encrypted_code)
            END
        ';
    END IF;
END $$;
