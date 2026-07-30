'use client'

import { Search, Upload, RefreshCw } from 'lucide-react'
import styles from './SchemaDocs.module.css'

/**
 * Toolbar showing coverage stats, search input, bulk import, and sync buttons.
 */
export function SchemaDocsHeader({
    coverage,
    searchTerm,
    onSearchChange,
    onBulkUpload,
    onTrain,
    trainingStatus,
}) {
    const isTraining = trainingStatus === 'IN_PROGRESS' || trainingStatus === 'PROCESSING'

    return (
        <div className={styles.header}>
            <div className={styles.coveragePills}>
                <span className={styles.pill}>
                    Tables: <span className={styles.pillValue}>{coverage.tables}/{coverage.totalTables}</span>
                </span>
                <span className={styles.pill}>
                    Columns: <span className={styles.pillValue}>{coverage.columns}/{coverage.totalColumns}</span>
                </span>
            </div>

            <input
                type="text"
                className={styles.searchInput}
                placeholder="Search tables or descriptions..."
                value={searchTerm}
                onChange={(e) => onSearchChange(e.target.value)}
            />

            <div className={styles.headerActions}>
                {onBulkUpload && (
                    <button className={styles.headerButton} onClick={onBulkUpload}>
                        <Upload size={13} />
                        Bulk Import
                    </button>
                )}
                {onTrain && (
                    <button
                        className={styles.headerButton}
                        onClick={onTrain}
                        disabled={isTraining}
                    >
                        <RefreshCw size={13} className={isTraining ? styles.spinner : ''} />
                        {isTraining ? 'Syncing...' : 'Sync to Brain'}
                    </button>
                )}
            </div>
        </div>
    )
}
