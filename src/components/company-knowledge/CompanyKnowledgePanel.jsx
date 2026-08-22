import { useEffect, useMemo, useRef, useState } from 'react'
import { ArrowRight, Cog, FileCode, Inbox, Loader2, Map as MapIcon, NotebookText, Plus, Save, Trash2, X } from 'lucide-react'
import { useQuery } from '@tanstack/react-query'
import { connectionAPI, schemaAPI } from '@/lib/api/client'
import { queryKeys } from '@/lib/queryKeys'
import { useCompanyKnowledgeStore } from '@/lib/stores/useCompanyKnowledgeStore'
import {
  useCodeScanSuggestions,
  useCompanyKnowledge,
  useCreateCompanyKnowledge,
  useDeleteCompanyKnowledge,
  useUpdateCompanyKnowledge,
} from '@/lib/hooks/queries'
import sectionStyles from '@/components/sections/TopLevelSection.module.css'
import styles from './CompanyKnowledgePanel.module.css'
import BackgroundJobsTab from './BackgroundJobsTab'
import CodeSourcesTab from './CodeSourcesTab'
import SuggestionsQueueTab from './SuggestionsQueueTab'
import EntriesTable from './EntriesTable'
import SchemaContextTab from './SchemaContextTab'
import { canonicalTableReference } from '@/lib/schemaNames'

const EMPTY_FORM = {
  title: '',
  content: '',
}

const TABLE_ANNOTATION_RE = /(?<!@)@([A-Za-z_][\w$.]*)/g
const COLUMN_ANNOTATION_RE = /(?<!@)@@([A-Za-z_][\w$.]*)/g

function normalizeValue(value) {
  return (value || '').trim().toLowerCase()
}

function formatCoverageStatus(value) {
  if (!value) return ''
  return value.replaceAll('_', ' ').toLowerCase()
}

function getEntryDiagnostics(entry) {
  if (!entry) return []
  const diagnostics = []

  if (Array.isArray(entry.invalidMentions) && entry.invalidMentions.length > 0) {
    diagnostics.push('DeepSQL could not resolve one or more tagged table or column references yet.')
    diagnostics.push(`Unknown or invalid tagged references: ${entry.invalidMentions.slice(0, 6).join(', ')}`)
  }

  return diagnostics
}

function getDiagnosticTone(entry) {
  if (!entry) return 'muted'
  if (Array.isArray(entry.invalidMentions) && entry.invalidMentions.length > 0) {
    return 'warning'
  }
  return 'muted'
}

function buildTableLookup(tableOptions) {
  const lookup = new Map()
  const bareCounts = new Map()
  tableOptions.forEach((table) => {
    const bare = (table.value || '').split('.').pop()
    if (!bare) return
    bareCounts.set(normalizeValue(bare), (bareCounts.get(normalizeValue(bare)) || 0) + 1)
  })
  tableOptions.forEach((table) => {
    const bare = (table.value || '').split('.').pop()
    const bareKey = normalizeValue(bare)
    // Always index the canonical value. Index the bare name only when unique
    // across schemas so @orders stays unambiguous on multi-schema DBs.
    const keys = [table.value, table.label]
    if (bare && bareCounts.get(bareKey) === 1) {
      keys.push(bare)
    }
    keys
      .filter(Boolean)
      .forEach((key) => lookup.set(normalizeValue(key), table.value))
  })
  return lookup
}

function buildColumnLookup(columnOptions) {
  const lookup = new Map()
  const shortCounts = new Map()
  columnOptions.forEach((column) => {
    const shortTable = column.tableValue?.split('.').pop()
    const shortKey = normalizeValue(`${shortTable}.${column.columnLabel}`)
    if (!shortKey) return
    shortCounts.set(shortKey, (shortCounts.get(shortKey) || 0) + 1)
  })
  columnOptions.forEach((column) => {
    const canonical = column.value
    const shortTable = column.tableValue?.split('.').pop()
    const shortKey = `${shortTable}.${column.columnLabel}`
    const keys = [
      canonical,
      `${column.tableValue}.${column.columnLabel}`,
    ]
    if (shortCounts.get(normalizeValue(shortKey)) === 1) {
      keys.push(shortKey)
    }
    keys
      .filter(Boolean)
      .forEach((key) => lookup.set(normalizeValue(key), canonical))
  })
  return lookup
}

function extractAnnotationReferences(content, tableLookup, columnLookup) {
  const linkedTables = new Set()
  const linkedColumns = new Set()

  if (!content) {
    return { linkedTables: [], linkedColumns: [] }
  }

  for (const match of content.matchAll(COLUMN_ANNOTATION_RE)) {
    const resolved = columnLookup.get(normalizeValue(match[1]))
    if (resolved) {
      linkedColumns.add(resolved)
    }
  }

  for (const match of content.matchAll(TABLE_ANNOTATION_RE)) {
    const resolved = tableLookup.get(normalizeValue(match[1]))
    if (resolved) {
      linkedTables.add(resolved)
    }
  }

  return {
    linkedTables: Array.from(linkedTables),
    linkedColumns: Array.from(linkedColumns),
  }
}

function buildAnnotationSeed(linkedTableFilter, linkedColumnFilter) {
  const lines = []
  if (linkedTableFilter) {
    lines.push(`@${linkedTableFilter}`)
  }
  if (linkedColumnFilter) {
    lines.push(`@@${linkedColumnFilter}`)
  }
  return lines.join('\n')
}

function mergeAnnotationSeed(content, linkedTables = [], linkedColumns = []) {
  const missing = []

  linkedTables.forEach((table) => {
    if (table && !content.includes(`@${table}`)) {
      missing.push(`@${table}`)
    }
  })

  linkedColumns.forEach((column) => {
    if (column && !content.includes(`@@${column}`)) {
      missing.push(`@@${column}`)
    }
  })

  if (missing.length === 0) {
    return content || ''
  }

  const trimmed = (content || '').trim()
  return trimmed ? `${missing.join('\n')}\n\n${trimmed}` : missing.join('\n')
}

function annotationMatch(value, cursor) {
  if (!value || typeof cursor !== 'number') {
    return null
  }

  const beforeCursor = value.slice(0, cursor)
  const tokenMatch = beforeCursor.match(/(^|[\s(])(@@?)([A-Za-z0-9_$.]*)$/)
  if (!tokenMatch) {
    return null
  }

  const prefix = tokenMatch[2]
  const query = tokenMatch[3] || ''
  return {
    kind: prefix === '@@' ? 'column' : 'table',
    start: cursor - prefix.length - query.length,
    end: cursor,
    query,
  }
}

function scoreOption(option, query) {
  const normalizedQuery = normalizeValue(query)
  if (!normalizedQuery) {
    return 1
  }
  const haystack = normalizeValue(`${option.label} ${option.value}`)
  if (haystack.startsWith(normalizedQuery)) {
    return 4
  }
  if (haystack.includes(normalizedQuery)) {
    return 2
  }
  return 0
}

export default function CompanyKnowledgePanel({ connectionId }) {
  const [editingId, setEditingId] = useState(null)
  const [form, setForm] = useState(EMPTY_FORM)
  const [annotationState, setAnnotationState] = useState({
    open: false,
    kind: 'table',
    query: '',
    start: 0,
    end: 0,
    selectedIndex: 0,
  })
  const textareaRef = useRef(null)
  const linkedTableFilter = useCompanyKnowledgeStore((state) => state.linkedTableFilter)
  const linkedColumnFilter = useCompanyKnowledgeStore((state) => state.linkedColumnFilter)
  const clearLinkedFilters = useCompanyKnowledgeStore((state) => state.clearLinkedFilters)
  const activeTab = useCompanyKnowledgeStore((state) => state.activeTab)
  const setActiveTab = useCompanyKnowledgeStore((state) => state.setActiveTab)
  const userChoseTab = useCompanyKnowledgeStore((state) => state.userChoseTab)
  const setDefaultTab = useCompanyKnowledgeStore((state) => state.setDefaultTab)

  const pendingSuggestionsQuery = useCodeScanSuggestions({
    connectionId,
    status: 'PENDING',
    page: 0,
    size: 1,
  })
  const pendingSuggestionCount = pendingSuggestionsQuery.data?.totalElements ?? 0

  const { data: entries = [], isLoading, error } = useCompanyKnowledge(connectionId)
  const createEntry = useCreateCompanyKnowledge()
  const updateEntry = useUpdateCompanyKnowledge()
  const deleteEntry = useDeleteCompanyKnowledge()
  const schemaQuery = useQuery({
    queryKey: queryKeys.schema.metadata(connectionId),
    queryFn: () => schemaAPI.getSchema(connectionId),
    enabled: Boolean(connectionId),
  })

  // Drives the one-time auto-advance off the default 'background-jobs' tab
  // once the Brain finishes initializing (see setDefaultTab in the store).
  // 404 (no init record yet) is normalized to null, same as BackgroundJobsTab.
  const initStatusQuery = useQuery({
    queryKey: queryKeys.brain.initStatus(connectionId),
    queryFn: () => connectionAPI.getInitStatus(connectionId),
    enabled: Boolean(connectionId),
    retry: false,
    refetchInterval: (query) => {
      const stage = query.state.data?.currentStage
      // Keep polling only while a non-terminal stage is running
      // (COMPLETED / FAILED / NEEDS_ATTENTION / NONE stop).
      if (!stage || stage === 'NONE' || stage === 'COMPLETED'
          || stage === 'FAILED' || stage === 'NEEDS_ATTENTION' || stage === 'ERROR') {
        return false
      }
      return 4000
    },
  })

  useEffect(() => {
    if (userChoseTab || activeTab !== 'background-jobs') {
      return
    }
    // Only auto-advance on full COMPLETED — NEEDS_ATTENTION stays on Initialize
    // so the user sees coverage messaging and can re-init.
    if (initStatusQuery.data?.currentStage === 'COMPLETED') {
      setDefaultTab('schema-context')
    }
  }, [activeTab, initStatusQuery.data, setDefaultTab, userChoseTab])

  const tableOptions = useMemo(
    () => (schemaQuery.data?.schema?.tables || schemaQuery.data?.tables || [])
      .map((table) => {
        const value = canonicalTableReference(table)
        const bare = table.tableName || table.name || ''
        // When the same bare name exists in multiple schemas, force the
        // qualified label so @ suggestions never look ambiguous.
        return {
          label: value,
          bareLabel: bare,
          value,
          columns: (table.columns || []).map((column) => {
            const colName = column.columnName || column.name
            return {
              label: `${value}.${colName}`,
              value: `${value}.${colName}`,
              columnLabel: colName,
              tableValue: value,
            }
          }),
        }
      })
      .filter((table) => table.value),
    [schemaQuery.data],
  )

  const columnOptions = useMemo(
    () => tableOptions.flatMap((table) => table.columns),
    [tableOptions],
  )

  const tableLookup = useMemo(() => buildTableLookup(tableOptions), [tableOptions])
  const columnLookup = useMemo(() => buildColumnLookup(columnOptions), [columnOptions])

  const annotationPreview = useMemo(
    () => extractAnnotationReferences(form.content, tableLookup, columnLookup),
    [columnLookup, form.content, tableLookup],
  )

  const annotationSuggestions = useMemo(() => {
    if (!annotationState.open) {
      return []
    }

    const source = annotationState.kind === 'column' ? columnOptions : tableOptions
    return source
      .map((option) => ({ ...option, score: scoreOption(option, annotationState.query) }))
      .filter((option) => option.score > 0)
      .sort((left, right) => right.score - left.score || left.value.localeCompare(right.value))
      .slice(0, 10)
  }, [annotationState, columnOptions, tableOptions])

  // Search now lives in EntriesTable; this memo only narrows by the cross-tab
  // linked-table / linked-column filter (set when the user clicks a chip
  // elsewhere to "show entries for this object").
  const linkedFilteredEntries = useMemo(() => {
    const normalizedLinkedTable = normalizeValue(linkedTableFilter)
    const normalizedLinkedColumn = normalizeValue(linkedColumnFilter)

    if (!normalizedLinkedTable && !normalizedLinkedColumn) {
      return entries
    }
    return entries.filter((entry) => {
      if (normalizedLinkedTable) {
        const tableMatch = (entry.linkedTables || []).some((linkedTable) =>
          normalizeValue(linkedTable) === normalizedLinkedTable
        ) || (entry.linkedColumns || []).some((linkedColumn) =>
          normalizeValue(linkedColumn).startsWith(`${normalizedLinkedTable}.`)
        )
        if (!tableMatch) {
          return false
        }
      }

      if (normalizedLinkedColumn) {
        const columnMatch = (entry.linkedColumns || []).some((linkedColumn) =>
          normalizeValue(linkedColumn) === normalizedLinkedColumn
        )
        if (!columnMatch) {
          return false
        }
      }

      return true
    })
  }, [entries, linkedColumnFilter, linkedTableFilter])

  const editingEntry = useMemo(
    () => entries.find((entry) => entry.id === editingId) || null,
    [editingId, entries],
  )

  const resetEditor = () => {
    setEditingId(null)
    setForm(EMPTY_FORM)
    setAnnotationState({
      open: false,
      kind: 'table',
      query: '',
      start: 0,
      end: 0,
      selectedIndex: 0,
    })
  }

  const updateAnnotationCursorState = (value, cursor) => {
    const match = annotationMatch(value, cursor)
    if (!match) {
      setAnnotationState((current) => ({ ...current, open: false, query: '' }))
      return
    }
    setAnnotationState((current) => ({
      open: true,
      kind: match.kind,
      query: match.query,
      start: match.start,
      end: match.end,
      selectedIndex: current.kind === match.kind ? current.selectedIndex : 0,
    }))
  }

  const handleStartNew = () => {
    setEditingId('new')
    setForm({
      title: '',
      content: buildAnnotationSeed(linkedTableFilter, linkedColumnFilter),
    })
    setAnnotationState({
      open: false,
      kind: 'table',
      query: '',
      start: 0,
      end: 0,
      selectedIndex: 0,
    })
  }

  const handleEdit = (entry) => {
    setEditingId(entry.id)
    setForm({
      title: entry.title || '',
      content: mergeAnnotationSeed(
        entry.content || '',
        Array.isArray(entry.linkedTables) ? entry.linkedTables : [],
        Array.isArray(entry.linkedColumns) ? entry.linkedColumns : [],
      ),
    })
    setAnnotationState({
      open: false,
      kind: 'table',
      query: '',
      start: 0,
      end: 0,
      selectedIndex: 0,
    })
  }

  const applyAnnotationSuggestion = (option) => {
    const prefix = annotationState.kind === 'column' ? '@@' : '@'
    const replacement = `${prefix}${option.value} `
    const nextContent = `${form.content.slice(0, annotationState.start)}${replacement}${form.content.slice(annotationState.end)}`

    setForm((current) => ({ ...current, content: nextContent }))
    setAnnotationState((current) => ({ ...current, open: false, query: '' }))

    requestAnimationFrame(() => {
      if (textareaRef.current) {
        const nextCursor = annotationState.start + replacement.length
        textareaRef.current.focus()
        textareaRef.current.setSelectionRange(nextCursor, nextCursor)
      }
    })
  }

  const handleSubmit = async (event) => {
    event.preventDefault()
    const payload = {
      connectionId,
      title: form.title.trim(),
      content: form.content.trim(),
      entryType: 'COMPANY_CONTEXT',
      linkedTables: annotationPreview.linkedTables,
      linkedColumns: annotationPreview.linkedColumns,
    }

    if (!payload.title || !payload.content) {
      return
    }

    if (editingId && editingId !== 'new') {
      await updateEntry.mutateAsync({ entryId: editingId, payload })
    } else {
      await createEntry.mutateAsync(payload)
    }
    resetEditor()
  }

  const handleDelete = async (entryId) => {
    await deleteEntry.mutateAsync({ entryId, connectionId })
    if (editingId === entryId) {
      resetEditor()
    }
  }

  const handleContentChange = (event) => {
    const nextValue = event.target.value
    setForm((current) => ({ ...current, content: nextValue }))
    updateAnnotationCursorState(nextValue, event.target.selectionStart)
  }

  const handleContentKeyDown = (event) => {
    if (!annotationState.open || annotationSuggestions.length === 0) {
      return
    }

    if (event.key === 'ArrowDown') {
      event.preventDefault()
      setAnnotationState((current) => ({
        ...current,
        selectedIndex: (current.selectedIndex + 1) % annotationSuggestions.length,
      }))
      return
    }

    if (event.key === 'ArrowUp') {
      event.preventDefault()
      setAnnotationState((current) => ({
        ...current,
        selectedIndex: (current.selectedIndex - 1 + annotationSuggestions.length) % annotationSuggestions.length,
      }))
      return
    }

    if (event.key === 'Enter' || event.key === 'Tab') {
      event.preventDefault()
      applyAnnotationSuggestion(annotationSuggestions[annotationState.selectedIndex] || annotationSuggestions[0])
      return
    }

    if (event.key === 'Escape') {
      event.preventDefault()
      setAnnotationState((current) => ({ ...current, open: false, query: '' }))
    }
  }

  const handleContentCursorUpdate = (event) => {
    updateAnnotationCursorState(form.content, event.target.selectionStart)
  }

  return (
    <div className={sectionStyles.page}>
      <div className={sectionStyles.header}>
        <div className={sectionStyles.eyebrow}>Brain</div>
        <h1 className={sectionStyles.title}>Teach DeepSQL how your business actually works</h1>
        <p className={sectionStyles.subtitle}>
          Add business context in plain language, then reference tables with <code>@table</code> and columns with <code>@@table.column</code> right inside the note.
        </p>
      </div>

      {/*
        Pipeline ribbon. The four tabs are stages of one funnel, not peers:
        Initialize → Add context (write notes / scan code) → Review → Knowledge
        base. Rendering the flow (with arrows + state) is what stops first-timers
        from reading them as five unrelated screens. Internal activeTab keys are
        unchanged ('background-jobs' | 'schema-context' | 'sources' |
        'suggestions' | 'business-rules') so persisted state still resolves.
      */}
      <div className={styles.pipeline} role="tablist" aria-label="Company context pipeline">
        <button
          type="button"
          role="tab"
          aria-selected={activeTab === 'background-jobs'}
          className={`${styles.stage} ${activeTab === 'background-jobs' ? styles.stageActive : ''}`}
          onClick={() => setActiveTab('background-jobs')}
        >
          <span className={styles.stageHead}>
            <span className={styles.stageNum}>1</span><Cog size={14} /> Initialize
          </span>
          <span className={styles.stageMeta}>Index the schema</span>
        </button>

        <ArrowRight size={16} className={styles.pipeArrow} aria-hidden="true" />

        <div className={`${styles.stage} ${(activeTab === 'schema-context' || activeTab === 'sources') ? styles.stageActive : ''}`}>
          <span className={styles.stageHead}>
            <span className={styles.stageNum}>2</span> Add context
          </span>
          <span className={styles.subInputs}>
            <button
              type="button"
              role="tab"
              aria-selected={activeTab === 'schema-context'}
              className={`${styles.subInput} ${activeTab === 'schema-context' ? styles.subInputActive : ''}`}
              onClick={() => setActiveTab('schema-context')}
            >
              <MapIcon size={13} /> Write notes
            </button>
            <button
              type="button"
              role="tab"
              aria-selected={activeTab === 'sources'}
              className={`${styles.subInput} ${activeTab === 'sources' ? styles.subInputActive : ''}`}
              onClick={() => setActiveTab('sources')}
            >
              <FileCode size={13} /> Scan code
            </button>
          </span>
        </div>

        <ArrowRight size={16} className={styles.pipeArrow} aria-hidden="true" />

        <button
          type="button"
          role="tab"
          aria-selected={activeTab === 'suggestions'}
          className={`${styles.stage} ${activeTab === 'suggestions' ? styles.stageActive : ''}`}
          onClick={() => setActiveTab('suggestions')}
        >
          <span className={styles.stageHead}>
            <span className={styles.stageNum}>3</span><Inbox size={14} /> Review
            {pendingSuggestionCount > 0 && (
              <span className={styles.stagePending}>{pendingSuggestionCount}</span>
            )}
          </span>
          <span className={styles.stageMeta}>
            {pendingSuggestionCount > 0 ? `${pendingSuggestionCount} awaiting sign-off` : 'Accept what fits'}
          </span>
        </button>

        <ArrowRight size={16} className={styles.pipeArrow} aria-hidden="true" />

        <button
          type="button"
          role="tab"
          aria-selected={activeTab === 'business-rules'}
          className={`${styles.stage} ${activeTab === 'business-rules' ? styles.stageActive : ''}`}
          onClick={() => setActiveTab('business-rules')}
        >
          <span className={styles.stageHead}>
            <span className={styles.stageNum}>4</span><NotebookText size={14} /> Knowledge base
          </span>
          <span className={styles.stageMeta}>
            {entries.length > 0 ? `${entries.length} grounding answers` : 'Grounds every answer'}
          </span>
        </button>
      </div>

      <p className={styles.flowCaption}>
        Knowledge flows left to right — nothing reaches the knowledge base until you review and accept it.
      </p>

      {activeTab === 'schema-context' && <SchemaContextTab connectionId={connectionId} />}
      {activeTab === 'sources' && <CodeSourcesTab connectionId={connectionId} />}
      {activeTab === 'suggestions' && <SuggestionsQueueTab connectionId={connectionId} />}
      {activeTab === 'background-jobs' && <BackgroundJobsTab connectionId={connectionId} />}

      {activeTab === 'business-rules' && (
      <>
      <div className={styles.toolbar}>
        {(linkedTableFilter || linkedColumnFilter) && (
          <button
            type="button"
            className={styles.secondaryButton}
            onClick={clearLinkedFilters}
          >
            Clear linked filter ({linkedTableFilter || linkedColumnFilter})
          </button>
        )}
        <span className={styles.bulkBarSpacer} />
        <button type="button" className={styles.primaryButton} onClick={handleStartNew}>
          <Plus size={14} />
          New entry
        </button>
      </div>

      {editingId && (
        <form className={styles.editorCard} onSubmit={handleSubmit}>
          <div className={styles.editorHeader}>
            <div>
              <h2 className={styles.editorTitle}>
                {editingId === 'new' ? 'Create company knowledge' : 'Edit company knowledge'}
              </h2>
              <p className={styles.editorSubtitle}>
                Write the context once. Use <code>@</code> for tables and <code>@@</code> for columns, and DeepSQL will link the schema automatically.
              </p>
            </div>
            <button type="button" className={styles.closeButton} onClick={resetEditor}>
              <X size={15} />
            </button>
          </div>

          <div className={styles.formGrid}>
            <label className={`${styles.field} ${styles.fieldFull}`}>
              <span>Title</span>
              <input
                value={form.title}
                onChange={(event) => setForm((current) => ({ ...current, title: event.target.value }))}
                placeholder="Booking funnel definition"
              />
            </label>

            <label className={`${styles.field} ${styles.fieldFull}`}>
              <span>Context</span>
              <div className={styles.annotationEditor}>
                <textarea
                  ref={textareaRef}
                  rows={8}
                  value={form.content}
                  onChange={handleContentChange}
                  onClick={handleContentCursorUpdate}
                  onKeyUp={handleContentCursorUpdate}
                  onKeyDown={handleContentKeyDown}
                  placeholder="Describe the business context in plain language. Type @ to link a table and @@ to link a column."
                />
                {annotationState.open && annotationSuggestions.length > 0 && (
                  <div className={styles.suggestionPanel}>
                    <div className={styles.suggestionHeader}>
                      {annotationState.kind === 'column' ? 'Link a column' : 'Link a table'}
                    </div>
                    {annotationSuggestions.map((option, index) => (
                      <button
                        key={option.value}
                        type="button"
                        className={`${styles.suggestionItem} ${index === annotationState.selectedIndex ? styles.suggestionItemActive : ''}`}
                        onMouseDown={(event) => {
                          event.preventDefault()
                          applyAnnotationSuggestion(option)
                        }}
                      >
                        <span className={styles.suggestionLabel}>{option.label}</span>
                        <span className={styles.suggestionValue}>{option.value}</span>
                      </button>
                    ))}
                  </div>
                )}
              </div>
              <div className={styles.annotationHelper}>
                Use <code>@</code> for tables and <code>@@</code> for columns. Pick from the suggestions to keep references canonical.
              </div>
              {(annotationPreview.linkedTables.length > 0 || annotationPreview.linkedColumns.length > 0) && (
                <div className={styles.annotationPreview}>
                  {annotationPreview.linkedTables.map((table) => (
                    <span key={table} className={styles.linkChip}>{table}</span>
                  ))}
                  {annotationPreview.linkedColumns.map((column) => (
                    <span key={column} className={styles.linkChipMuted}>{column}</span>
                  ))}
                </div>
              )}
            </label>
          </div>

          {editingEntry && getEntryDiagnostics(editingEntry).length > 0 && (
            <div className={`${styles.diagnosticsPanel} ${styles[getDiagnosticTone(editingEntry)]}`}>
              <div className={styles.diagnosticsHeader}>
                Reference check
              </div>
              {getEntryDiagnostics(editingEntry).map((message) => (
                <p key={message} className={styles.diagnosticMessage}>{message}</p>
              ))}
            </div>
          )}

          <div className={styles.editorActions}>
            <button type="button" className={styles.secondaryButton} onClick={resetEditor}>
              Cancel
            </button>
            <button
              type="submit"
              className={styles.primaryButton}
              disabled={createEntry.isPending || updateEntry.isPending}
            >
              {createEntry.isPending || updateEntry.isPending ? (
                <>
                  <Loader2 size={14} className={styles.spinner} />
                  {editingId === 'new' ? 'Creating…' : 'Saving…'}
                </>
              ) : (
                <>
                  <Save size={14} />
                  {editingId === 'new' ? 'Create entry' : 'Save changes'}
                </>
              )}
            </button>
          </div>
        </form>
      )}

      {error || schemaQuery.error ? (
        <div className={styles.errorState}>
          Failed to load company knowledge.
        </div>
      ) : entries.length === 0 && !isLoading && !schemaQuery.isLoading ? (
        <div className={styles.emptyState}>
          <p>No company knowledge entries yet.</p>
          <button type="button" className={styles.primaryButton} onClick={handleStartNew}>
            <Plus size={14} />
            Add the first entry
          </button>
        </div>
      ) : (
        <EntriesTable
          connectionId={connectionId}
          entries={linkedFilteredEntries}
          isLoading={isLoading || schemaQuery.isLoading}
          onEdit={handleEdit}
          onDelete={handleDelete}
        />
      )}
      </>
      )}
    </div>
  )
}
