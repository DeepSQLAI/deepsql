ALTER TABLE encrypted_credentials
    ADD COLUMN owner_username VARCHAR(255);

UPDATE encrypted_credentials
SET owner_username = 'admin'
WHERE owner_username IS NULL;

ALTER TABLE encrypted_credentials
    ALTER COLUMN owner_username SET NOT NULL;
