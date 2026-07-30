'use client'

import { XCircle } from 'lucide-react'
import { buildBcnfSuggestions } from '../utils/bcnfUtils'
import styles from '../../Core/RagTrainingTab.module.css'

/**
 * Modal for viewing BCNF table details and creating review tasks
 */
export function BCNFModal({
    isOpen,
    bcnfData,
    onCreateTask,
    onClose,
    taskStatus = null
}) {
    if (!isOpen) return null

    const renderDetailValue = (value) => {
        if (value === null || value === undefined) return '--'
        return value
    }

    const suggestions = bcnfData?.suggestions && bcnfData.suggestions.length > 0
        ? bcnfData.suggestions
        : buildBcnfSuggestions(bcnfData?.issues)

    return (
        <div className={styles.modalOverlay} onClick={onClose}>
            <div className={styles.modalContent} onClick={(e) => e.stopPropagation()}>
                <div className={styles.modalHeader}>
                    <div>
                        <div className={styles.modalTitle}>BCNF review</div>
                        <div className={styles.modalSubtitle}>{bcnfData?.tableLabel}</div>
                    </div>
                    <button className={styles.modalCloseButton} onClick={onClose} aria-label="Close">
                        <XCircle size={18} />
                    </button>
                </div>

                <div className={styles.modalBody}>
                    <div className={styles.detailGrid}>
                        <div className={styles.detailItem}>
                            <span className={styles.detailLabel}>BCNF status</span>
                            <span className={styles.detailValue}>Needs review</span>
                        </div>
                        <div className={styles.detailItem}>
                            <span className={styles.detailLabel}>BCNF score</span>
                            <span className={styles.detailValue}>{renderDetailValue(bcnfData?.bcnfScore)}</span>
                        </div>
                        <div className={styles.detailItem}>
                            <span className={styles.detailLabel}>Issues flagged</span>
                            <span className={styles.detailValue}>{bcnfData?.issues?.length ?? 0}</span>
                        </div>
                    </div>

                    <div className={styles.detailNotes}>
                        <div className={styles.detailItemWide}>
                            <span className={styles.detailLabel}>Why flagged</span>
                            {bcnfData?.issues?.length ? (
                                <ul className={styles.bcnfList}>
                                    {bcnfData.issues.map((issue) => (
                                        <li key={issue}>{issue}</li>
                                    ))}
                                </ul>
                            ) : (
                                <span className={styles.detailValue}>No specific issue details were captured.</span>
                            )}
                        </div>
                        <div className={styles.detailItemWide}>
                            <span className={styles.detailLabel}>Suggested next steps</span>
                            <ul className={styles.bcnfList}>
                                {suggestions.map((suggestion) => (
                                    <li key={suggestion}>{suggestion}</li>
                                ))}
                            </ul>
                        </div>
                    </div>

                    {taskStatus ? (
                        <div className={styles.bcnfTaskNote}>{taskStatus}</div>
                    ) : null}

                    <div className={styles.modalActions}>
                        <button
                            type="button"
                            className={styles.trainButton}
                            onClick={() => onCreateTask(bcnfData)}
                        >
                            Create task
                        </button>
                        <button type="button" className={styles.cancelButton} onClick={onClose}>
                            Close
                        </button>
                    </div>
                </div>
            </div>
        </div>
    )
}
