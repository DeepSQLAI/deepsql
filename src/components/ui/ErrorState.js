'use client'

import { AlertCircle, RefreshCw } from 'lucide-react'
import styles from './ui.module.css'

/**
 * Error state component with optional retry action
 *
 * @param {Object} props
 * @param {string} [props.title='Error'] - Title text
 * @param {string} [props.message] - Error message
 * @param {Function} [props.onRetry] - Retry callback
 * @param {string} [props.retryLabel='Retry'] - Retry button label
 * @param {number} [props.size=48] - Icon size
 */
export default function ErrorState({
    title = 'Error',
    message,
    onRetry,
    retryLabel = 'Retry',
    size = 48,
}) {
    return (
        <div className={styles.errorState}>
            <AlertCircle size={size} />
            <h3>{title}</h3>
            {message && <p>{message}</p>}
            {onRetry && (
                <button onClick={onRetry} className={styles.retryButton}>
                    <RefreshCw size={14} />
                    {retryLabel}
                </button>
            )}
        </div>
    )
}
