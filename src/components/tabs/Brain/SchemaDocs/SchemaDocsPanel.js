'use client'

import { useState, useMemo, useCallback } from 'react'
import { Loader2 } from 'lucide-react'
import { useCreateBrainNote, useUpdateBrainNote } from '@/lib/hooks/queries/useBrain'
import { useAuth } from '@/hooks/useAuth'
import { BrainOverviewHero } from '../components'
import { useSchemaDocsData } from './useSchemaDocsData'
import { SchemaDocsHeader } from './SchemaDocsHeader'
import { SchemaDocsTableRow } from './SchemaDocsTableRow'
import styles from './SchemaDocs.module.css'

/**
 * Main container for the Schema Docs tab.
 * Displays all tables with expandable column lists and inline description editing.
 */
export function SchemaDocsPanel({
    connectionId,
    activeTab,
    onTabChange,
    onBulkUpload,
    onTrain,
    trainingStatus,
    showHero = true,
    title = 'Schema Docs',
    subtitle = 'Browse tables and columns, add descriptions to improve Brain context.',
    onOpenCompanyKnowledge,
}) {
    const { tables, coverage, isLoading, error } = useSchemaDocsData(connectionId)

    const [searchTerm, setSearchTerm] = useState('')
    const [expandedTables, setExpandedTables] = useState(new Set())
    const [savingNoteId, setSavingNoteId] = useState(null)
    const { username } = useAuth()

    const createNote = useCreateBrainNote()
    const updateNote = useUpdateBrainNote()

    // Filter tables by search
    const filteredTables = useMemo(() => {
        if (!searchTerm.trim()) return tables
        const q = searchTerm.toLowerCase()
        return tables.filter(t => {
            const name = (t.tableName || '').toLowerCase()
            const desc = (t.note?.noteText || '').toLowerCase()
            return name.includes(q) || desc.includes(q)
        })
    }, [tables, searchTerm])

    const toggleTable = useCallback((tableName) => {
        setExpandedTables(prev => {
            const next = new Set(prev)
            if (next.has(tableName)) next.delete(tableName)
            else next.add(tableName)
            return next
        })
    }, [])

    const handleSaveTableNote = useCallback(async (tableName, text, noteId) => {
        if (!connectionId) return
        setSavingNoteId(noteId || `new-table-${tableName}`)
        try {
            const payload = {
                connectionId,
                scopeType: 'TABLE',
                tableName,
                columnName: null,
                noteText: text,
                createdBy: username || null,
            }
            if (noteId) {
                await updateNote.mutateAsync({ noteId, payload })
            } else {
                await createNote.mutateAsync(payload)
            }
        } catch (err) {
            console.error('Failed to save table note:', err)
        } finally {
            setSavingNoteId(null)
        }
    }, [connectionId, createNote, updateNote, username])

    const handleSaveColumnNote = useCallback(async (tableName, columnName, text, noteId) => {
        if (!connectionId) return
        setSavingNoteId(noteId || `new-col-${tableName}.${columnName}`)
        try {
            const payload = {
                connectionId,
                scopeType: 'COLUMN',
                tableName,
                columnName,
                noteText: text,
                createdBy: username || null,
            }
            if (noteId) {
                await updateNote.mutateAsync({ noteId, payload })
            } else {
                await createNote.mutateAsync(payload)
            }
        } catch (err) {
            console.error('Failed to save column note:', err)
        } finally {
            setSavingNoteId(null)
        }
    }, [connectionId, createNote, updateNote, username])

    if (error) {
        return (
            <div className={styles.panel}>
                {showHero && (
                    <BrainOverviewHero
                        title={title}
                        subtitle={subtitle}
                        activeTab={activeTab}
                        onTabChange={onTabChange}
                    />
                )}
                <div className={styles.error}>
                    Failed to load schema: {error.message || 'Unknown error'}
                </div>
            </div>
        )
    }

    return (
        <div className={styles.panel}>
            {showHero && (
                <BrainOverviewHero
                    title={title}
                    subtitle={subtitle}
                    activeTab={activeTab}
                    onTabChange={onTabChange}
                />
            )}

            <SchemaDocsHeader
                coverage={coverage}
                searchTerm={searchTerm}
                onSearchChange={setSearchTerm}
                onBulkUpload={onBulkUpload}
                onTrain={onTrain}
                trainingStatus={trainingStatus}
            />

            {isLoading ? (
                <div className={styles.loading}>
                    <Loader2 size={18} className={styles.spinner} />
                    Loading schema...
                </div>
            ) : filteredTables.length === 0 ? (
                <div className={styles.emptyState}>
                    <p>
                        {searchTerm
                            ? `No tables match "${searchTerm}"`
                            : 'No tables found. Run a schema scan first.'}
                    </p>
                </div>
            ) : (
                <div className={styles.tableList}>
                    {filteredTables.map(table => (
                        <SchemaDocsTableRow
                            key={table.tableName}
                            table={table}
                            expanded={expandedTables.has(table.tableName)}
                            onToggle={() => toggleTable(table.tableName)}
                            onSaveTableNote={handleSaveTableNote}
                            onSaveColumnNote={handleSaveColumnNote}
                            savingNoteId={savingNoteId}
                            onOpenCompanyKnowledge={onOpenCompanyKnowledge}
                        />
                    ))}
                </div>
            )}
        </div>
    )
}
