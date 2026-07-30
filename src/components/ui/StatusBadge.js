'use client'

import styles from './ui.module.css'

/**
 * Status badge component with variant support
 *
 * @param {Object} props
 * @param {string} props.label - Badge label text
 * @param {'success' | 'warning' | 'error' | 'info' | 'neutral'} [props.variant='neutral'] - Badge variant
 * @param {React.ReactNode} [props.icon] - Optional icon
 * @param {string} [props.className] - Additional CSS class
 */
export default function StatusBadge({
    label,
    variant = 'neutral',
    icon,
    className = '',
}) {
    return (
        <span className={`${styles.statusBadge} ${styles[variant]} ${className}`}>
            {icon}
            {label}
        </span>
    )
}

/**
 * Get variant from health status
 */
export function getHealthVariant(health) {
    switch (health) {
        case 'EXCELLENT':
        case 'GOOD':
            return 'success'
        case 'FAIR':
            return 'warning'
        case 'POOR':
        case 'CRITICAL':
            return 'error'
        default:
            return 'neutral'
    }
}

/**
 * Get variant from severity level
 */
export function getSeverityVariant(severity) {
    switch (severity) {
        case 'CRITICAL':
        case 'SEVERE':
        case 'HIGH':
            return 'error'
        case 'MODERATE':
        case 'MEDIUM':
            return 'warning'
        case 'MINOR':
        case 'LOW':
            return 'success'
        default:
            return 'neutral'
    }
}

/**
 * Get variant from status string
 */
export function getStatusVariant(status) {
    switch (status?.toUpperCase()) {
        case 'SUCCESS':
        case 'COMPLETED':
        case 'ACTIVE':
        case 'RUNNING':
            return 'success'
        case 'WARNING':
        case 'PENDING':
        case 'IN_PROGRESS':
            return 'warning'
        case 'ERROR':
        case 'FAILED':
        case 'CANCELLED':
            return 'error'
        case 'INFO':
            return 'info'
        default:
            return 'neutral'
    }
}
