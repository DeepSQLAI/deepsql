'use client'

import { ChevronRight } from 'lucide-react'
import { InlineDescriptionEditor } from './InlineDescriptionEditor'
import { SchemaDocsColumnRow } from './SchemaDocsColumnRow'
import styles from './SchemaDocs.module.css'

const ROLE_STYLES = {
    FACT: styles.roleFact,
    DIMENSION: styles.roleDimension,
    BRIDGE: styles.roleBridge,
    LOOKUP: styles.roleLookup,
    ORPHANED: styles.roleOrphaned,
}

/**
 * Expandable table row showing table name, role badge, description preview,
 * and a coverage bar. When expanded, shows inline table description editor
 * and a list of column rows.
 */
export function SchemaDocsTableRow({
    table,
    expanded,
    onToggle,
    onSaveTableNote,
    onSaveColumnNote,
    savingNoteId,
    onOpenCompanyKnowledge,
}) {
    const coveragePercent = table.columnsTotal > 0
        ? Math.round((table.columnsDocumented / table.columnsTotal) * 100)
        : 0

    const roleClass = ROLE_STYLES[table.role] || styles.roleDefault

    const descriptionText = table.note?.noteText || ''
    const displayName = table.tableReference || table.tableName
    const persistTableName = table.tableReference || table.tableName

    return (
        <div className={styles.tableRow}>
            <div className={styles.tableRowHeader} onClick={onToggle}>
                <ChevronRight
                    size={14}
                    className={`${styles.chevron} ${expanded ? styles.chevronExpanded : ''}`}
                />
                <span className={styles.tableName} title={displayName}>{displayName}</span>
                <div className={styles.tableMeta}>
                    {table.rowCount != null && (
                        <span className={styles.rowCount}>
                            {Number(table.rowCount).toLocaleString()} rows
                        </span>
                    )}
                    {table.role && (
                        <span className={`${styles.roleBadge} ${roleClass}`}>
                            {table.role}
                        </span>
                    )}
                    <button
                        type="button"
                        className={`${styles.knowledgeLink} ${table.companyKnowledgeCount ? styles.knowledgeLinkActive : ''}`}
                        onClick={(event) => {
                            event.stopPropagation()
                            if (table.companyKnowledgeCount > 0 && onOpenCompanyKnowledge) {
                                onOpenCompanyKnowledge({ table: table.tableReference || table.tableName })
                            }
                        }}
                        disabled={!table.companyKnowledgeCount}
                    >
                        Knowledge {table.companyKnowledgeCount || 0}
                    </button>
                </div>
                <span className={styles.descriptionPreview}>
                    {descriptionText ? (
                        descriptionText.length > 80
                            ? descriptionText.slice(0, 80) + '...'
                            : descriptionText
                    ) : (
                        <span className={styles.descriptionPlaceholder}>No description</span>
                    )}
                </span>
                <div className={styles.coverageBar}>
                    <div className={styles.coverageTrack}>
                        <div
                            className={styles.coverageFill}
                            style={{ width: `${coveragePercent}%` }}
                        />
                    </div>
                    <span className={styles.coverageLabel}>
                        {table.columnsDocumented}/{table.columnsTotal}
                    </span>
                </div>
            </div>

            {expanded && (
                <div className={styles.tableBody}>
                    <div className={styles.tableDescription}>
                        <InlineDescriptionEditor
                            value={descriptionText}
                            source={table.note?.source}
                            noteId={table.note?.id}
                            sourceFiles={table.note?.sourceFiles}
                            onSave={(text, noteId) => onSaveTableNote(persistTableName, text, noteId)}
                            saving={savingNoteId === table.note?.id}
                            placeholder="Click to add table description"
                        />
                    </div>
                    {table.columns.length > 0 && (
                        <div className={styles.columnList}>
                            {table.columns.map(col => (
                                <SchemaDocsColumnRow
                                    key={`${persistTableName}.${col.columnName}`}
                                    column={col}
                                    onSave={(text, noteId) =>
                                        onSaveColumnNote(persistTableName, col.columnName, text, noteId)
                                    }
                                    saving={savingNoteId === col.note?.id}
                                    onOpenCompanyKnowledge={onOpenCompanyKnowledge}
                                />
                            ))}
                        </div>
                    )}
                </div>
            )}
        </div>
    )
}
