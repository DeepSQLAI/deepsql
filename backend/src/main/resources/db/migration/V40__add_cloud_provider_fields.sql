-- Add cloud provider and managed service fields for deployment context
-- This enables feature gating based on cloud environment (e.g., CloudWatch for AWS only)

ALTER TABLE encrypted_credentials
ADD COLUMN IF NOT EXISTS cloud_provider VARCHAR(20) DEFAULT NULL;

ALTER TABLE encrypted_credentials
ADD COLUMN IF NOT EXISTS managed_service VARCHAR(30) DEFAULT NULL;

-- Add comments for documentation
COMMENT ON COLUMN encrypted_credentials.cloud_provider IS 'Cloud provider: aws, azure, gcp, self-hosted, or null';
COMMENT ON COLUMN encrypted_credentials.managed_service IS 'Managed service type: rds, aurora, cloud-sql, azure-flexible, azure-single, or null';

-- Create index for querying by cloud provider
CREATE INDEX IF NOT EXISTS idx_credentials_cloud_provider ON encrypted_credentials(cloud_provider);
