'use client'
import styles from '../Core/RagTrainingTab.module.css'

/**
 * Header component for the Brain tab
 * Shows connection name and training controls
 */
export function BrainHeader({ connectionName, connectionId }) {
    return (
        <div className={styles.header}>
            <div>
                <h2>Brain</h2>
                <p className={styles.subtle}>
                    Understanding and training for <strong>{connectionName || connectionId}</strong>
                </p>
            </div>
        </div>
    )
}
