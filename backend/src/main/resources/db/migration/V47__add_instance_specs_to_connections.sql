-- Add instance sizing fields to encrypted_credentials table
-- Used for context-aware configuration tuning recommendations

ALTER TABLE encrypted_credentials
ADD COLUMN IF NOT EXISTS instance_class VARCHAR(64) DEFAULT NULL;

ALTER TABLE encrypted_credentials
ADD COLUMN IF NOT EXISTS instance_vcpus INTEGER DEFAULT NULL;

ALTER TABLE encrypted_credentials
ADD COLUMN IF NOT EXISTS instance_memory_gb DOUBLE PRECISION DEFAULT NULL;

ALTER TABLE encrypted_credentials
ADD COLUMN IF NOT EXISTS storage_type VARCHAR(30) DEFAULT NULL;

ALTER TABLE encrypted_credentials
ADD COLUMN IF NOT EXISTS storage_max_iops INTEGER DEFAULT NULL;

COMMENT ON COLUMN encrypted_credentials.instance_class IS 'Cloud instance class/size (e.g., AWS: db.m6g.4xlarge, Azure: GP_Gen5_8, GCP: db-n1-standard-4)';
COMMENT ON COLUMN encrypted_credentials.instance_vcpus IS 'Instance vCPU/vCore count (for tuning calculations)';
COMMENT ON COLUMN encrypted_credentials.instance_memory_gb IS 'Instance memory in GB (for tuning calculations)';
COMMENT ON COLUMN encrypted_credentials.storage_type IS 'Storage type (e.g., gp3, io1, premium-ssd, pd-ssd)';
COMMENT ON COLUMN encrypted_credentials.storage_max_iops IS 'Maximum provisioned IOPS (if applicable)';
