'use client'

import { XCircle, Search } from 'lucide-react'
import { useMemo } from 'react'
import styles from '../../Core/RagTrainingTab.module.css'

/**
 * Modal for reviewing all tables that need BCNF review
 */
export function BCNFReviewModal({
    isOpen,
    tables,
    searchTerm,
    onUpdateSearch,
    onViewDetails,
    onClose
}) {
    if (!isOpen) return null

    const reviewTables = useMemo(() => {
        return tables.filter((table) => {
            const status = (table.bcnfStatus || '').toLowerCase()
            return status === 'review'
        })
    }, [tables])

    const filteredTables = useMemo(() => {
        if (!searchTerm) return reviewTables
        const lower = searchTerm.toLowerCase()
        return reviewTables.filter((table) =>
            table.tableLabel?.toLowerCase().includes(lower)
        )
    }, [reviewTables, searchTerm])

    const renderDetailValue = (value) => {
        if (value === null || value === undefined) return '--'
        return value
    }

    return (
        <div className={styles.modalOverlay} onClick={onClose}>
            <div className={styles.modalContent} onClick={(e) => e.stopPropagation()}>
                <div className={styles.modalHeader}>
                    <div>
                        <div className={styles.modalTitle}>BCNF tables needing review</div>
                        <div className={styles.modalSubtitle}>
                            {reviewTables.length} table{reviewTables.length === 1 ? '' : 's'} flagged
                        </div>
                    </div>
                    <button className={styles.modalCloseButton} onClick={onClose} aria-label="Close">
                        <XCircle size={18} />
                    </button>
                </div>

                <div className={styles.modalBody}>
                    <div className={styles.bcnfReviewHeader}>
                        <div className={styles.brainFilterInput}>
                            <Search size={14} />
                            <input
                                type="search"
                                value={searchTerm}
                                onChange={(e) => onUpdateSearch(e.target.value)}
                                placeholder="Filter tables"
                            />
                        </div>
                    </div>

                    <div className={styles.bcnfReviewList}>
                        {filteredTables.length ? (
                            filteredTables.map((table, index) => (
                                <div key={table._tableKey || table.tableLabel || table.tableName || index} className={styles.bcnfReviewRow}>
                                    <div>
                                        <div className={styles.bcnfReviewName}>{table.tableLabel}</div>
                                        <div className={styles.bcnfReviewMeta}>
                                            Score {renderDetailValue(table.bcnfScore)} • Issues {table.issues?.length ?? 0}
                                        </div>
                                    </div>
                                    <button
                                        type="button"
                                        className={styles.inlineButton}
                                        onClick={() => onViewDetails(table)}
                                    >
                                        View details
                                    </button>
                                </div>
                            ))
                        ) : (
                            <div className={styles.brainEmpty}>No tables match this filter.</div>
                        )}
                    </div>

                    <div className={styles.modalActions}>
                        <button type="button" className={styles.cancelButton} onClick={onClose}>
                            Close
                        </button>
                    </div>
                </div>
            </div>
        </div>
    )
}
