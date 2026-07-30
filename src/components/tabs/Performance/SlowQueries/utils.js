/**
 * Utility functions for Slow Query Analysis components
 */

import { PROVIDER_LABELS, HEALTH_COLORS, SEVERITY_COLORS, TREND_COLORS } from './constants'

/**
 * Get display label for provider type
 */
export const getProviderLabel = (providerType) => {
    return PROVIDER_LABELS[providerType] || providerType || 'Unknown'
}

/**
 * Get the configured source identifier based on provider type
 */
export const getProviderSource = (config) => {
    if (!config) return 'Unknown'

    switch (config.providerType) {
        case 'S3':
            return config.bucketName
        case 'CLOUDWATCH':
            return config.logGroupName
        case 'AZURE_BLOB':
            return config.azureContainerName
        case 'GCP_LOGGING':
            return config.gcpProjectId
        case 'DATADOG':
            return config.datadogServiceName || config.datadogSite
        case 'ELASTICSEARCH':
            return config.elasticsearchHost
        default:
            return 'Unknown'
    }
}

/**
 * Get color for health status
 */
export const getHealthColor = (health) => {
    return HEALTH_COLORS[health] || 'var(--color-light-6)'
}

/**
 * Get color for severity
 */
export const getSeverityColor = (severity) => {
    return SEVERITY_COLORS[severity] || 'var(--color-light-6)'
}

/**
 * Get color for trend direction
 */
export const getTrendColor = (direction) => {
    return TREND_COLORS[direction] || 'var(--color-light-6)'
}

/**
 * Format timestamp relative to now
 */
export const formatTimestamp = (timestamp) => {
    if (!timestamp) return 'N/A'

    const date = new Date(timestamp)
    const now = new Date()
    const diffMs = now - date
    const diffMins = Math.floor(diffMs / 60000)

    if (diffMins < 1) return 'Just now'
    if (diffMins < 60) return `${diffMins}m ago`
    if (diffMins < 1440) return `${Math.floor(diffMins / 60)}h ago`
    return date.toLocaleDateString()
}

/**
 * Format duration in milliseconds
 */
export const formatDuration = (ms) => {
    if (ms == null) return 'N/A'
    if (ms < 1000) return `${ms.toFixed(2)}ms`
    return `${(ms / 1000).toFixed(2)}s`
}

/**
 * Format date string
 */
export const formatDate = (dateString) => {
    if (!dateString) return 'N/A'
    const date = new Date(dateString)
    return date.toLocaleString()
}

/**
 * Format bytes to human-readable string (KB, MB, GB)
 */
export const formatBytes = (bytes) => {
    if (bytes === 0 || bytes == null) return '0 B'
    const k = 1024
    const sizes = ['B', 'KB', 'MB', 'GB']
    const i = Math.floor(Math.log(bytes) / Math.log(k))
    const size = bytes / Math.pow(k, i)
    return `${size.toFixed(i > 0 ? 1 : 0)} ${sizes[i]}`
}

/**
 * Format next scheduled run time
 */
export const formatNextScheduledRun = (dateTimeStr) => {
    if (!dateTimeStr) return 'Not scheduled'

    const nextRun = new Date(dateTimeStr)
    const now = new Date()
    const diffMs = nextRun - now

    if (diffMs < 0) return 'Running soon...'

    const diffMins = Math.floor(diffMs / 60000)
    const diffHours = Math.floor(diffMins / 60)
    const diffDays = Math.floor(diffHours / 24)

    if (diffDays > 0) {
        return `in ${diffDays}d ${diffHours % 24}h`
    } else if (diffHours > 0) {
        return `in ${diffHours}h ${diffMins % 60}m`
    } else if (diffMins > 0) {
        return `in ${diffMins}m`
    } else {
        return 'Running soon...'
    }
}

/**
 * Generate IAM policy based on provider type and config
 */
export const generateIamPolicy = (sourceConfig) => {
    const region = sourceConfig.s3Region || '*'
    const accountId = '<ACCOUNT_ID>'

    if (sourceConfig.providerType === 'S3') {
        const bucket = sourceConfig.bucketName || '<BUCKET_NAME>'
        const prefix = sourceConfig.objectPrefix || '*'
        const objectPath = prefix ? `${prefix}*` : '*'

        return {
            Version: '2012-10-17',
            Statement: [
                {
                    Sid: 'ListBucket',
                    Effect: 'Allow',
                    Action: ['s3:ListBucket'],
                    Resource: [`arn:aws:s3:::${bucket}`],
                    ...(prefix ? {
                        Condition: {
                            StringLike: {
                                's3:prefix': [`${prefix}*`]
                            }
                        }
                    } : {})
                },
                {
                    Sid: 'GetObjects',
                    Effect: 'Allow',
                    Action: ['s3:GetObject'],
                    Resource: [`arn:aws:s3:::${bucket}/${objectPath}`]
                }
            ]
        }
    } else if (sourceConfig.providerType === 'CLOUDWATCH') {
        const logGroup = sourceConfig.logGroupName || '<LOG_GROUP_NAME>'
        const logStreamPrefix = sourceConfig.logStreamPrefix || '*'

        return {
            Version: '2012-10-17',
            Statement: [
                {
                    Sid: 'DescribeLogGroups',
                    Effect: 'Allow',
                    Action: ['logs:DescribeLogGroups'],
                    Resource: [`arn:aws:logs:${region}:${accountId}:log-group:*`]
                },
                {
                    Sid: 'DescribeLogStreams',
                    Effect: 'Allow',
                    Action: ['logs:DescribeLogStreams'],
                    Resource: [`arn:aws:logs:${region}:${accountId}:log-group:${logGroup}:*`]
                },
                {
                    Sid: 'GetLogEvents',
                    Effect: 'Allow',
                    Action: ['logs:GetLogEvents', 'logs:FilterLogEvents'],
                    Resource: [`arn:aws:logs:${region}:${accountId}:log-group:${logGroup}:log-stream:${logStreamPrefix}`]
                }
            ]
        }
    }

    // Return generic placeholder for other providers
    return {
        note: `See ${getProviderLabel(sourceConfig.providerType)} documentation for access configuration.`
    }
}
