'use client'

import { useState, useRef, useEffect } from 'react'
import styles from './SchemaDocs.module.css'

/**
 * Inline editor for table/column descriptions.
 * Display mode shows text or placeholder; click to edit.
 */
export function InlineDescriptionEditor({
    value = '',
    placeholder = 'Click to add description',
    source,
    onSave,
    saving = false,
    noteId,
    sourceFiles,
}) {
    const [editing, setEditing] = useState(false)
    const [draft, setDraft] = useState(value)
    const [sourcesOpen, setSourcesOpen] = useState(false)
    const textareaRef = useRef(null)

    useEffect(() => {
        if (editing && textareaRef.current) {
            textareaRef.current.focus()
            textareaRef.current.select()
        }
    }, [editing])

    // Sync draft with external value changes when not editing.
    // React-19-friendly "adjust state during render" pattern: track the last
    // synced value via state and reconcile in render rather than useEffect.
    const [syncedValue, setSyncedValue] = useState(value)
    if (!editing && value !== syncedValue) {
        setSyncedValue(value)
        setDraft(value)
    }

    const handleSave = () => {
        const trimmed = draft.trim()
        if (trimmed === (value || '').trim()) {
            setEditing(false)
            return
        }
        onSave(trimmed, noteId)
        setEditing(false)
    }

    const handleCancel = () => {
        setDraft(value)
        setEditing(false)
    }

    const handleKeyDown = (e) => {
        if (e.key === 'Escape') handleCancel()
        if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) handleSave()
    }

    const sourceLabel = source === 'AI_GENERATED' ? 'AI'
        : source === 'CSV_IMPORT' ? 'CSV'
        : source === 'USER' ? 'User'
        : source === 'CODE_DERIVED' ? 'Code'
        : null

    const sourceClass = source === 'AI_GENERATED' ? styles.sourceAi
        : source === 'CSV_IMPORT' ? styles.sourceCsv
        : source === 'USER' ? styles.sourceUser
        : source === 'CODE_DERIVED' ? styles.sourceCode
        : ''

    const hasSources = Array.isArray(sourceFiles) && sourceFiles.length > 0

    if (editing) {
        return (
            <div className={styles.editor}>
                <div className={styles.editorForm}>
                    <textarea
                        ref={textareaRef}
                        className={styles.editorTextarea}
                        value={draft}
                        onChange={(e) => setDraft(e.target.value)}
                        onKeyDown={handleKeyDown}
                        rows={2}
                        placeholder={placeholder}
                    />
                    <div className={styles.editorActions}>
                        <button
                            className={styles.editorSave}
                            onClick={handleSave}
                            disabled={saving || !draft.trim()}
                        >
                            {saving ? 'Saving...' : 'Save'}
                        </button>
                        <button
                            className={styles.editorCancel}
                            onClick={handleCancel}
                        >
                            Cancel
                        </button>
                    </div>
                </div>
            </div>
        )
    }

    return (
        <div className={styles.editor}>
            <div className={styles.editorDisplay} onClick={() => setEditing(true)}>
                {value ? (
                    <span className={styles.editorText}>{value}</span>
                ) : (
                    <span className={styles.editorPlaceholder}>{placeholder}</span>
                )}
                {sourceLabel && (
                    <span className={`${styles.sourceBadge} ${sourceClass}`}>
                        {sourceLabel}
                    </span>
                )}
                {hasSources && (
                    <button
                        type="button"
                        className={styles.sourcesToggle}
                        onClick={(e) => {
                            e.stopPropagation()
                            setSourcesOpen((v) => !v)
                        }}
                        title={sourcesOpen ? 'Hide source files' : 'Show source files'}
                    >
                        {sourcesOpen ? '▾' : '▸'} {sourceFiles.length} source{sourceFiles.length === 1 ? '' : 's'}
                    </button>
                )}
            </div>
            {hasSources && sourcesOpen && (
                <ul className={styles.sourcesList} onClick={(e) => e.stopPropagation()}>
                    {sourceFiles.map((sf, idx) => (
                        <li key={`${sf.path}-${idx}`} className={styles.sourcesItem}>
                            <code>{sf.path}</code>
                            {(sf.startLine != null && sf.endLine != null) && (
                                <span className={styles.sourcesLines}>:{sf.startLine}–{sf.endLine}</span>
                            )}
                            {sf.rationale && (
                                <div className={styles.sourcesRationale}>{sf.rationale}</div>
                            )}
                        </li>
                    ))}
                </ul>
            )}
        </div>
    )
}
