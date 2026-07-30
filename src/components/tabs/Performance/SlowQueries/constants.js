/**
 * Constants for Slow Query Analysis components
 */

export const PROVIDER_TYPES = {
    S3: 'S3',
    CLOUDWATCH: 'CLOUDWATCH',
    AZURE_BLOB: 'AZURE_BLOB',
    GCP_LOGGING: 'GCP_LOGGING',
    DATADOG: 'DATADOG',
    ELASTICSEARCH: 'ELASTICSEARCH',
}

export const PROVIDER_LABELS = {
    S3: 'AWS S3',
    CLOUDWATCH: 'CloudWatch',
    AZURE_BLOB: 'Azure Blob',
    GCP_LOGGING: 'GCP Logging',
    DATADOG: 'Datadog',
    ELASTICSEARCH: 'Elasticsearch',
}

export const TIME_RANGES = {
    SINCE_LAST: 'SINCE_LAST',
    LAST_24_HOURS: 'LAST_24_HOURS',
    LAST_7_DAYS: 'LAST_7_DAYS',
    LAST_30_DAYS: 'LAST_30_DAYS',
    ALL: 'ALL',
}

export const DEFAULT_SOURCE_CONFIG = {
    enabled: false,
    providerType: 'S3',
    bucketName: '',
    objectPrefix: '',
    s3Region: '',
    logGroupName: '',
    logStreamPrefix: '',
    accessKeyId: '',
    secretAccessKey: '',
    sessionToken: '',
    azureConnectionString: '',
    azureAccountName: '',
    azureAccountKey: '',
    azureSasToken: '',
    azureContainerName: '',
    azureBlobPrefix: '',
    azureClientId: '',
    azureClientSecret: '',
    azureTenantId: '',
    gcpProjectId: '',
    gcpLogFilter: '',
    gcpInstanceId: '',
    gcpServiceAccountJson: '',
    datadogApiKey: '',
    datadogAppKey: '',
    datadogSite: 'US',
    datadogQuery: '',
    datadogServiceName: '',
    elasticsearchHost: '',
    elasticsearchPort: 9200,
    elasticsearchScheme: 'https',
    elasticsearchUsername: '',
    elasticsearchPassword: '',
    elasticsearchApiKey: '',
    elasticsearchApiKeyId: '',
    elasticsearchIndexPattern: '',
    elasticsearchQuery: '',
    elasticsearchVerifySsl: true,
    refreshFrequencyMinutes: 1440,
    autoScheduleEnabled: false,
    nextScheduledRunAt: null,
    lastAutoIngestMessage: null,
}

export const HEALTH_COLORS = {
    EXCELLENT: 'var(--color-dark-grey)',
    GOOD: 'var(--color-dark-grey)',
    FAIR: 'var(--color-grey)',
    POOR: 'var(--color-danger)',
    CRITICAL: 'var(--color-danger)',
}

export const SEVERITY_COLORS = {
    CRITICAL: 'var(--color-danger)',
    SEVERE: 'var(--color-danger)',
    MODERATE: 'var(--color-grey)',
    MINOR: 'var(--color-grey)',
}

export const TREND_COLORS = {
    IMPROVING: 'var(--color-grey)',
    STABLE: 'var(--color-grey)',
    DEGRADING: 'var(--color-grey)',
    CRITICAL: 'var(--color-danger)',
}
