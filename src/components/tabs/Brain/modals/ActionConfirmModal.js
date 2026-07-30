'use client'

import { XCircle } from 'lucide-react'
import styles from '../../Core/RagTrainingTab.module.css'

/**
 * Modal for confirming actions (train, profile, rescan)
 */
export function ActionConfirmModal({
    isOpen,
    actionType,
    onConfirm,
    onClose,
    loading = false
}) {
    if (!isOpen) return null

    const getContent = () => {
        switch (actionType) {
            case 'train':
                return {
                    title: 'Train Brain',
                    description: 'Process all tables and columns to build understanding of this schema.',
                    details: [
                        'Analyzes table relationships and foreign keys',
                        'Processes column types and constraints',
                        'Calculates normalization scores (BCNF)',
                        'Updates the overall understanding score'
                    ],
                    confirmLabel: 'Start training'
                }
            case 'profile':
                return {
                    title: 'Profile columns',
                    description: 'Run statistical analysis on all unprofiled columns.',
                    details: [
                        'Samples column data to detect patterns',
                        'Identifies null rates and cardinality',
                        'Detects potential data quality issues',
                        'May take several minutes for large schemas'
                    ],
                    confirmLabel: 'Start profiling'
                }
            case 'rescan':
                return {
                    title: 'Rescan schema',
                    description: 'Capture a fresh snapshot of the database schema.',
                    details: [
                        'Queries database metadata for all tables',
                        'Updates column definitions and constraints',
                        'Detects schema drift since last scan',
                        'Does not affect existing documentation'
                    ],
                    confirmLabel: 'Rescan now'
                }
            default:
                return {
                    title: 'Confirm action',
                    description: 'Are you sure you want to proceed?',
                    details: [],
                    confirmLabel: 'Continue'
                }
        }
    }

    const content = getContent()

    return (
        <div className={styles.modalOverlay} onClick={onClose}>
            <div className={styles.modalContent} onClick={(e) => e.stopPropagation()}>
                <div className={styles.modalHeader}>
                    <div>
                        <div className={styles.modalTitle}>{content.title}</div>
                        <div className={styles.modalSubtitle}>Review before continuing.</div>
                    </div>
                    <button className={styles.modalCloseButton} onClick={onClose} aria-label="Close">
                        <XCircle size={18} />
                    </button>
                </div>

                <div className={styles.modalBody}>
                    <div className={styles.actionSummary}>
                        {content.description}
                    </div>
                    {content.details.length > 0 && (
                        <ul className={styles.actionList}>
                            {content.details.map((detail) => (
                                <li key={detail}>{detail}</li>
                            ))}
                        </ul>
                    )}
                    <div className={styles.modalActions}>
                        <button type="button" className={styles.cancelButton} onClick={onClose}>
                            Cancel
                        </button>
                        <button
                            type="button"
                            className={styles.trainButton}
                            onClick={onConfirm}
                            disabled={loading}
                        >
                            {content.confirmLabel}
                        </button>
                    </div>
                </div>
            </div>
        </div>
    )
}
