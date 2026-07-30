'use client'

import { XCircle } from 'lucide-react'
import { useMemo } from 'react'
import styles from '../../Core/RagTrainingTab.module.css'

/**
 * Modal for resolving column ambiguity
 */
export function AmbiguityModal({
    isOpen,
    ambiguityData,
    selection,
    filter,
    showAll,
    onUpdateSelection,
    onUpdateFilter,
    onToggleShowAll,
    onSubmit,
    onClose,
    saving = false
}) {
    if (!isOpen) return null

    const ambiguousTables = ambiguityData?.ambiguousTables || []

    const filteredTables = useMemo(() => {
        if (!filter) return ambiguousTables
        const lower = filter.toLowerCase()
        return ambiguousTables.filter((table) => table.toLowerCase().includes(lower))
    }, [ambiguousTables, filter])

    const visibleTables = showAll ? filteredTables : filteredTables.slice(0, 20)

    const handleSubmit = (e) => {
        e.preventDefault()
        onSubmit()
    }

    return (
        <div className={styles.modalOverlay} onClick={onClose}>
            <div className={styles.modalContent} onClick={(e) => e.stopPropagation()}>
                <div className={styles.modalHeader}>
                    <div>
                        <div className={styles.modalTitle}>Resolve column ambiguity</div>
                        <div className={styles.modalSubtitle}>
                            {ambiguityData?.columnName}
                        </div>
                    </div>
                    <button className={styles.modalCloseButton} onClick={onClose} aria-label="Close">
                        <XCircle size={18} />
                    </button>
                </div>

                <form className={styles.modalBody} onSubmit={handleSubmit}>
                    <div className={styles.docHint}>
                        When a query uses <strong>{ambiguityData?.columnName}</strong> without a table prefix,
                        pick the canonical table that should be assumed (e.g., <code>HOTEL</code> for <code>hotel_id</code>).
                    </div>

                    <div className={styles.ambiguityMeta}>
                        <span>{filteredTables.length} tables</span>
                        {ambiguityData?.tableName && (
                            <button
                                type="button"
                                className={styles.inlineButton}
                                onClick={() => onUpdateSelection(ambiguityData.tableName)}
                            >
                                Use current table ({ambiguityData.tableName})
                            </button>
                        )}
                    </div>

                    <div className={styles.formField}>
                        <label>Filter tables</label>
                        <input
                            type="text"
                            value={filter}
                            onChange={(e) => onUpdateFilter(e.target.value)}
                            placeholder="Type to filter tables"
                        />
                    </div>

                    <div className={styles.formField}>
                        <label>Default table for unqualified column</label>
                        <select
                            value={selection}
                            onChange={(e) => onUpdateSelection(e.target.value)}
                            required
                        >
                            <option value="" disabled>Select a table</option>
                            {visibleTables.map((table) => (
                                <option key={table} value={table}>
                                    {table}
                                </option>
                            ))}
                        </select>
                        {!showAll && filteredTables.length > visibleTables.length ? (
                            <button
                                type="button"
                                className={styles.hintToggle}
                                onClick={onToggleShowAll}
                            >
                                Show all {filteredTables.length} tables
                            </button>
                        ) : null}
                    </div>

                    <div className={styles.modalActions}>
                        <button type="button" className={styles.cancelButton} onClick={onClose}>
                            Cancel
                        </button>
                        <button type="submit" className={styles.trainButton} disabled={saving}>
                            {saving ? 'Saving...' : 'Save preference'}
                        </button>
                    </div>
                </form>
            </div>
        </div>
    )
}
