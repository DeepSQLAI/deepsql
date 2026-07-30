'use client'

import { Key } from 'lucide-react'
import { InlineDescriptionEditor } from './InlineDescriptionEditor'
import styles from './SchemaDocs.module.css'

/**
 * Single column row within an expanded table.
 * Shows column name, type badge, PK icon, nullable indicator, and inline description editor.
 */
export function SchemaDocsColumnRow({ column, onSave, saving, onOpenCompanyKnowledge }) {
    const isPK = column.primaryKey || column.pk
    const isNullable = column.nullable !== false

    return (
        <div className={styles.columnRow}>
            <div className={styles.columnInfo}>
                {isPK && <Key size={12} className={styles.pkIcon} />}
                <span className={styles.columnName}>{column.columnName}</span>
                <span className={styles.typeBadge}>{column.dataType || column.columnType || 'unknown'}</span>
                {!isPK && isNullable && <span className={styles.nullableIcon}>?</span>}
                <button
                    type="button"
                    className={`${styles.knowledgeLink} ${column.companyKnowledgeCount ? styles.knowledgeLinkActive : ''}`}
                    onClick={() => {
                        if (column.companyKnowledgeCount > 0 && onOpenCompanyKnowledge) {
                            onOpenCompanyKnowledge({ column: column.reference })
                        }
                    }}
                    disabled={!column.companyKnowledgeCount}
                >
                    {column.companyKnowledgeCount || 0}
                </button>
            </div>
            <div className={styles.columnDescription}>
                <InlineDescriptionEditor
                    value={column.note?.noteText || ''}
                    source={column.note?.source}
                    noteId={column.note?.id}
                    sourceFiles={column.note?.sourceFiles}
                    onSave={onSave}
                    saving={saving}
                    placeholder="Click to add column description"
                />
            </div>
        </div>
    )
}
