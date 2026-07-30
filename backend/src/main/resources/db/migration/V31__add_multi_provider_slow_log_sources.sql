-- Add Azure Blob Storage columns
ALTER TABLE slow_log_source_config ADD COLUMN IF NOT EXISTS azure_connection_string BYTEA;
ALTER TABLE slow_log_source_config ADD COLUMN IF NOT EXISTS azure_account_name VARCHAR(255);
ALTER TABLE slow_log_source_config ADD COLUMN IF NOT EXISTS azure_account_key BYTEA;
ALTER TABLE slow_log_source_config ADD COLUMN IF NOT EXISTS azure_sas_token BYTEA;
ALTER TABLE slow_log_source_config ADD COLUMN IF NOT EXISTS azure_container_name VARCHAR(255);
ALTER TABLE slow_log_source_config ADD COLUMN IF NOT EXISTS azure_blob_prefix TEXT;
ALTER TABLE slow_log_source_config ADD COLUMN IF NOT EXISTS azure_client_id VARCHAR(255);
ALTER TABLE slow_log_source_config ADD COLUMN IF NOT EXISTS azure_client_secret BYTEA;
ALTER TABLE slow_log_source_config ADD COLUMN IF NOT EXISTS azure_tenant_id VARCHAR(255);

-- Add GCP Cloud Logging columns
ALTER TABLE slow_log_source_config ADD COLUMN IF NOT EXISTS gcp_project_id VARCHAR(255);
ALTER TABLE slow_log_source_config ADD COLUMN IF NOT EXISTS gcp_log_filter TEXT;
ALTER TABLE slow_log_source_config ADD COLUMN IF NOT EXISTS gcp_instance_id VARCHAR(255);
ALTER TABLE slow_log_source_config ADD COLUMN IF NOT EXISTS gcp_service_account_json BYTEA;

-- Add Datadog columns
ALTER TABLE slow_log_source_config ADD COLUMN IF NOT EXISTS datadog_api_key BYTEA;
ALTER TABLE slow_log_source_config ADD COLUMN IF NOT EXISTS datadog_app_key BYTEA;
ALTER TABLE slow_log_source_config ADD COLUMN IF NOT EXISTS datadog_site VARCHAR(10);
ALTER TABLE slow_log_source_config ADD COLUMN IF NOT EXISTS datadog_query TEXT;
ALTER TABLE slow_log_source_config ADD COLUMN IF NOT EXISTS datadog_service_name VARCHAR(255);

-- Add Elasticsearch/ELK columns
ALTER TABLE slow_log_source_config ADD COLUMN IF NOT EXISTS elasticsearch_host VARCHAR(255);
ALTER TABLE slow_log_source_config ADD COLUMN IF NOT EXISTS elasticsearch_port INTEGER;
ALTER TABLE slow_log_source_config ADD COLUMN IF NOT EXISTS elasticsearch_scheme VARCHAR(10);
ALTER TABLE slow_log_source_config ADD COLUMN IF NOT EXISTS elasticsearch_username VARCHAR(255);
ALTER TABLE slow_log_source_config ADD COLUMN IF NOT EXISTS elasticsearch_password BYTEA;
ALTER TABLE slow_log_source_config ADD COLUMN IF NOT EXISTS elasticsearch_api_key BYTEA;
ALTER TABLE slow_log_source_config ADD COLUMN IF NOT EXISTS elasticsearch_api_key_id VARCHAR(255);
ALTER TABLE slow_log_source_config ADD COLUMN IF NOT EXISTS elasticsearch_index_pattern VARCHAR(255);
ALTER TABLE slow_log_source_config ADD COLUMN IF NOT EXISTS elasticsearch_query TEXT;
ALTER TABLE slow_log_source_config ADD COLUMN IF NOT EXISTS elasticsearch_verify_ssl BOOLEAN DEFAULT TRUE;

-- Add scheduling columns
ALTER TABLE slow_log_source_config ADD COLUMN IF NOT EXISTS auto_schedule_enabled BOOLEAN DEFAULT FALSE;
ALTER TABLE slow_log_source_config ADD COLUMN IF NOT EXISTS next_scheduled_run_at TIMESTAMP;
ALTER TABLE slow_log_source_config ADD COLUMN IF NOT EXISTS last_auto_ingest_message TEXT;

-- Add index for auto-scheduling queries
CREATE INDEX IF NOT EXISTS idx_slow_log_source_auto_schedule
    ON slow_log_source_config (auto_schedule_enabled, next_scheduled_run_at)
    WHERE enabled = TRUE AND auto_schedule_enabled = TRUE;

COMMENT ON COLUMN slow_log_source_config.provider_type IS 'S3, CLOUDWATCH, AZURE_BLOB, GCP_LOGGING, DATADOG, ELASTICSEARCH';
COMMENT ON COLUMN slow_log_source_config.datadog_site IS 'Datadog site: US, EU, US3, US5, GOV';
COMMENT ON COLUMN slow_log_source_config.elasticsearch_scheme IS 'http or https';
