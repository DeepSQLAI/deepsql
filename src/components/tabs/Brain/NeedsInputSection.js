'use client'

import styles from '../Core/RagTrainingTab.module.css'

/**
 * Section showing items that need user input (missing docs, ambiguities, etc.)
 */
export function NeedsInputSection({
    needsInputRows = [],
    loading = false,
    hasData = false,
    onResolveAmbiguity,
    onAddDetails
}) {
    return (
        <div className={styles.brainNeeds}>
            <h4>Needs input</h4>
            {loading ? (
                <div className={styles.brainEmpty}>Loading understanding data...</div>
            ) : needsInputRows.length ? (
                <div className={styles.brainNeedsList}>
                    {needsInputRows.map((item, index) => {
                        const isMissingDoc = typeof item.reason === 'string' &&
                            item.reason.toLowerCase().includes('missing documentation')
                        const showResolve = item.ambiguousTables?.length
                        const showAddDetails = isMissingDoc
                        const title = item.objectType === 'COLUMN'
                            ? (item.aggregatedAmbiguity ? item.columnName : `${item.tableName}.${item.columnName}`)
                            : item.tableName
                        const ambiguousPreview = item.aggregatedAmbiguity && item.ambiguousTables?.length
                            ? item.ambiguousTables.slice(0, 3).join(', ')
                            : null
                        const ambiguousRemainder = item.aggregatedAmbiguity && item.ambiguousTables?.length > 3
                            ? item.ambiguousTables.length - 3
                            : 0

                        return (
                            <div key={`${item.objectType}-${item.tableName}-${item.columnName}-${index}`} className={styles.brainNeedRow}>
                                <div>
                                    <div className={styles.brainNeedTitle}>
                                        {title}
                                    </div>
                                    <div className={styles.brainNeedReason}>{item.reason}</div>
                                    {ambiguousPreview ? (
                                        <div className={styles.brainNeedHint}>
                                            Example tables: {ambiguousPreview}
                                            {ambiguousRemainder ? ` +${ambiguousRemainder} more` : ''}
                                        </div>
                                    ) : null}
                                </div>
                                <div className={styles.brainNeedActions}>
                                    {showResolve ? (
                                        <button
                                            className={styles.brainActionButton}
                                            onClick={() => onResolveAmbiguity(item)}
                                        >
                                            Resolve
                                        </button>
                                    ) : null}
                                    {showAddDetails ? (
                                        <button
                                            className={styles.brainActionButton}
                                            onClick={() => onAddDetails({ ...item, locked: true })}
                                        >
                                            Add details
                                        </button>
                                    ) : null}
                                </div>
                            </div>
                        )
                    })}
                </div>
            ) : hasData ? (
                <div className={styles.brainEmpty}>No missing details detected.</div>
            ) : (
                <div className={styles.brainEmpty}>No understanding data yet.</div>
            )}
        </div>
    )
}
