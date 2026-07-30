'use client'

import { Loader2 } from 'lucide-react'
import styles from './ui.module.css'

/**
 * Loading state component with spinner and optional message
 *
 * @param {Object} props
 * @param {string} [props.title='Loading'] - Title text
 * @param {string} [props.message] - Optional description message
 * @param {number} [props.size=48] - Spinner icon size
 * @param {boolean} [props.inline=false] - Inline display mode (smaller)
 */
export default function LoadingState({
    title = 'Loading',
    message,
    size = 48,
    inline = false,
}) {
    if (inline) {
        return (
            <div className={styles.loadingInline}>
                <Loader2 size={16} className={styles.spinner} />
                <span>{title}</span>
            </div>
        )
    }

    return (
        <div className={styles.loadingState}>
            <Loader2 size={size} className={styles.spinner} />
            <h3>{title}</h3>
            {message && <p>{message}</p>}
        </div>
    )
}
