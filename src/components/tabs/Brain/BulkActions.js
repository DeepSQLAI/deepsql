'use client'

import { useState } from 'react'
import { Loader } from 'lucide-react'
import styles from './BulkActions.module.css'

/**
 * Bulk operations component
 * Allows performing actions on multiple items at once
 */
export function BulkActions({
    selectedCount = 0,
    onProfileAll,
    onDocumentAll,
    onClearSelection,
    loading = false
}) {
    const [actionLoading, setActionLoading] = useState(null)

    const handleProfileAll = async () => {
        setActionLoading('profile')
        try {
            await onProfileAll()
        } finally {
            setActionLoading(null)
        }
    }

    const handleDocumentAll = async () => {
        setActionLoading('document')
        try {
            await onDocumentAll()
        } finally {
            setActionLoading(null)
        }
    }

    if (selectedCount === 0) {
        return null
    }

    return (
        <div className={styles.bulkActions}>
            <div className={styles.info}>
                {selectedCount} item{selectedCount !== 1 ? 's' : ''} selected
            </div>

            <div className={styles.actions}>
                <button
                    className={styles.actionButton}
                    onClick={handleProfileAll}
                    disabled={actionLoading !== null}
                >
                    {actionLoading === 'profile' ? (
                        <Loader className={styles.spinner} size={14} />
                    ) : null}
                    <span>Profile selected</span>
                </button>

                <button
                    className={styles.actionButton}
                    onClick={handleDocumentAll}
                    disabled={actionLoading !== null}
                >
                    {actionLoading === 'document' ? (
                        <Loader className={styles.spinner} size={14} />
                    ) : null}
                    <span>Add documentation</span>
                </button>

                <button
                    className={styles.clearButton}
                    onClick={onClearSelection}
                    disabled={actionLoading !== null}
                >
                    Clear selection
                </button>
            </div>
        </div>
    )
}

/**
 * Quick action buttons for common bulk operations
 */
export function QuickActions({
    unprofiledCount = 0,
    undocumentedCount = 0,
    onProfileAll,
    onExportData,
    loading = false
}) {
    return (
        <div className={styles.quickActions}>
            <h4>Quick actions</h4>

            <div className={styles.actionGrid}>
                {unprofiledCount > 0 && (
                    <button
                        className={styles.quickActionButton}
                        onClick={onProfileAll}
                        disabled={loading}
                    >
                        {loading ? <Loader className={styles.spinner} size={14} /> : null}
                        <div className={styles.quickActionContent}>
                            <span className={styles.quickActionLabel}>Profile all unprofiled</span>
                            <span className={styles.quickActionCount}>{unprofiledCount} columns</span>
                        </div>
                    </button>
                )}

                {undocumentedCount > 0 && (
                    <div className={styles.quickActionButton} style={{ cursor: 'default', opacity: 0.6 }}>
                        <div className={styles.quickActionContent}>
                            <span className={styles.quickActionLabel}>Needs documentation</span>
                            <span className={styles.quickActionCount}>{undocumentedCount} items</span>
                        </div>
                    </div>
                )}

                <button
                    className={styles.quickActionButton}
                    onClick={onExportData}
                    disabled={loading}
                >
                    <div className={styles.quickActionContent}>
                        <span className={styles.quickActionLabel}>Export data</span>
                        <span className={styles.quickActionCount}>CSV/JSON</span>
                    </div>
                </button>
            </div>
        </div>
    )
}
