import { useMemo, useState } from 'react'
import { AlertTriangle, Check, Edit3, Loader2, Pin, PinOff, Trash2, X } from 'lucide-react'
import { companyKnowledgeAPI } from '@/lib/api/client'
import { useQueryClient } from '@tanstack/react-query'
import { queryKeys } from '@/lib/queryKeys'
import styles from './CompanyKnowledgePanel.module.css'

/**
 * Custom grid template for entries (different column shape than suggestions).
 * Order: [checkbox] [type] [links] [title] [warn] [actions]
 */
const ENTRY_GRID_COLUMNS = '32px 110px 140px minmax(0, 2fr) 32px 92px'

const TYPE_LABELS = {
  COMPANY_CONTEXT: 'Context',
  WORKFLOW: 'Workflow',
  BUSINESS_RULE: 'Rule',
  METRIC: 'Metric',
  GLOSSARY: 'Glossary',
}

function entryHas(entry, q) {
  const parts = [
    entry.title,
    entry.content,
    (entry.linkedTables || []).join(' '),
    (entry.linkedColumns || []).join(' '),
    entry.entryType,
  ].join(' ').toLowerCase()
  return parts.includes(q)
}

function describeLinks(entry) {
  const t = entry.linkedTables?.length || 0
  const c = entry.linkedColumns?.length || 0
  if (!t && !c) return '—'
  const bits = []
  if (t) bits.push(`${t} table${t === 1 ? '' : 's'}`)
  if (c) bits.push(`${c} col${c === 1 ? '' : 's'}`)
  return bits.join(' · ')
}

function hasDiagnostics(entry) {
  return Array.isArray(entry.invalidMentions) && entry.invalidMentions.length > 0
}

function PreviewPane({ entry, pinned, onPin, onUnpin, onEdit, onDelete, busy }) {
  if (!entry) {
    return (
      <aside className={styles.previewPane}>
        <div className={styles.previewPaneEmpty}>
          Hover an entry to preview. Click a row to pin it open while you select.
        </div>
      </aside>
    )
  }
  const linkedTables = entry.linkedTables || []
  const linkedColumns = entry.linkedColumns || []
  const diagnostics = hasDiagnostics(entry) ? entry.invalidMentions : []
  return (
    <aside className={styles.previewPane}>
      <div className={styles.previewBadgeRow}>
        <span className={styles.kindBadge}>{TYPE_LABELS[entry.entryType] || entry.entryType}</span>
        {linkedTables.length + linkedColumns.length > 0 && (
          <span className={styles.kindBadge}>
            {describeLinks(entry)}
          </span>
        )}
        <span className={styles.bulkBarSpacer} />
        <button
          type="button"
          className={styles.iconButton}
          onClick={pinned ? onUnpin : onPin}
          title={pinned ? 'Unpin' : 'Pin to keep open'}
        >
          {pinned ? <PinOff size={14} /> : <Pin size={14} />}
        </button>
      </div>
      <h3 className={styles.previewTitle}>{entry.title}</h3>

      <div className={styles.previewSection}>
        <div className={styles.previewLabel}>Content</div>
        <p className={styles.previewBody}>{entry.content}</p>
      </div>

      {(linkedTables.length > 0 || linkedColumns.length > 0) && (
        <div className={styles.previewSection}>
          <div className={styles.previewLabel}>Linked objects</div>
          <div className={styles.linkGroup}>
            {linkedTables.map((t) => (
              <span key={`t-${t}`} className={styles.linkChip}>{t}</span>
            ))}
            {linkedColumns.map((c) => (
              <span key={`c-${c}`} className={styles.linkChipMuted}>{c}</span>
            ))}
          </div>
        </div>
      )}

      {diagnostics.length > 0 && (
        <div className={`${styles.diagnosticsPanel} ${styles.warning}`} style={{ marginTop: 14 }}>
          <div className={styles.diagnosticsHeader}>
            <AlertTriangle size={14} style={{ verticalAlign: 'middle', marginRight: 6 }} />
            Unresolved references
          </div>
          <p className={styles.diagnosticMessage}>
            {diagnostics.slice(0, 6).join(', ')}
          </p>
        </div>
      )}

      <div className={styles.previewActionsRow}>
        <button
          type="button"
          className={styles.secondaryButton}
          onClick={() => onEdit(entry)}
          disabled={busy}
        >
          <Edit3 size={14} /> Edit
        </button>
        <button
          type="button"
          className={styles.secondaryButton}
          onClick={() => onDelete(entry.id)}
          disabled={busy}
        >
          <Trash2 size={14} /> Delete
        </button>
      </div>
    </aside>
  )
}

export default function EntriesTable({ connectionId, entries, isLoading, onEdit, onDelete }) {
  const queryClient = useQueryClient()

  const [search, setSearch] = useState('')
  const [typeFilter, setTypeFilter] = useState('ALL')
  const [selected, setSelected] = useState(() => new Set())
  const [hoveredId, setHoveredId] = useState(null)
  const [pinnedId, setPinnedId] = useState(null)
  const [bulkBusy, setBulkBusy] = useState(false)
  const [bulkProgress, setBulkProgress] = useState(null) // {done, total}
  const [bulkError, setBulkError] = useState(null)
  const [bulkSuccess, setBulkSuccess] = useState(null)

  // Reset selection when filter inputs change.
  const filterSig = `${search}|${typeFilter}`
  const [lastSig, setLastSig] = useState(filterSig)
  if (lastSig !== filterSig) {
    setLastSig(filterSig)
    setSelected(new Set())
  }

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase()
    return entries.filter((e) => {
      if (typeFilter !== 'ALL' && e.entryType !== typeFilter) return false
      if (!q) return true
      return entryHas(e, q)
    })
  }, [entries, search, typeFilter])

  const selectedIds = useMemo(
    () => filtered.filter((e) => selected.has(e.id)).map((e) => e.id),
    [filtered, selected],
  )

  const allVisibleSelected = filtered.length > 0 && filtered.every((e) => selected.has(e.id))
  const someVisibleSelected = !allVisibleSelected && filtered.some((e) => selected.has(e.id))

  const focusedId = pinnedId || hoveredId
  const focused = useMemo(() => entries.find((e) => e.id === focusedId) || null, [entries, focusedId])

  const toggleOne = (id) =>
    setSelected((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })

  const toggleAllVisible = () =>
    setSelected((prev) => {
      const next = new Set(prev)
      const allOn = filtered.every((e) => next.has(e.id))
      if (allOn) filtered.forEach((e) => next.delete(e.id))
      else filtered.forEach((e) => next.add(e.id))
      return next
    })

  const handleBulkDelete = async () => {
    if (selectedIds.length === 0) return
    setBulkBusy(true)
    setBulkError(null)
    setBulkSuccess(null)
    setBulkProgress({ done: 0, total: selectedIds.length })
    let done = 0
    let failures = 0
    for (const id of selectedIds) {
      try {
        await companyKnowledgeAPI.delete(id, connectionId)
      } catch (err) {
        failures += 1
        // keep going — caller probably wants partial progress, not abort
        // (one bad row shouldn't block the rest, same philosophy as suggestions)
      }
      done += 1
      setBulkProgress({ done, total: selectedIds.length })
    }
    queryClient.invalidateQueries({ queryKey: queryKeys.companyKnowledge.all(connectionId) })
    setBulkBusy(false)
    setBulkProgress(null)
    setSelected(new Set())
    if (failures > 0) {
      setBulkError(`Deleted ${done - failures} of ${selectedIds.length}; ${failures} failed`)
    } else {
      setBulkSuccess(`Deleted ${done} entr${done === 1 ? 'y' : 'ies'}`)
    }
  }

  // Toolbar: search + type filter + counts
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
      <div className={styles.toolbar}>
        <input
          className={styles.searchInputLarge}
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder="Search title, content, linked tables, columns…"
        />
        <label className={styles.statusFilter}>
          Type
          <select
            value={typeFilter}
            onChange={(e) => setTypeFilter(e.target.value)}
            style={{ border: 'none', background: 'transparent', fontWeight: 600 }}
          >
            <option value="ALL">All</option>
            {Object.entries(TYPE_LABELS).map(([value, label]) => (
              <option key={value} value={value}>{label}</option>
            ))}
          </select>
        </label>
        <span className={styles.muteText}>
          {filtered.length} of {entries.length} shown
        </span>
      </div>

      {selectedIds.length > 0 && (
        <div className={styles.bulkBar}>
          <span className={styles.bulkBarCount}>{selectedIds.length} selected</span>
          <span className={styles.bulkBarSpacer} />
          <button
            type="button"
            className={styles.secondaryButton}
            onClick={handleBulkDelete}
            disabled={bulkBusy}
          >
            {bulkBusy && bulkProgress ? (
              <><Loader2 size={14} className={styles.spinner} /> Deleting {bulkProgress.done}/{bulkProgress.total}…</>
            ) : (
              <><Trash2 size={14} /> Delete {selectedIds.length}</>
            )}
          </button>
        </div>
      )}

      {bulkError && (
        <div className={`${styles.diagnosticsPanel} ${styles.danger}`}>
          <div className={styles.diagnosticsHeader}>Bulk delete partly failed</div>
          <p className={styles.diagnosticMessage}>{bulkError}</p>
        </div>
      )}
      {bulkSuccess && !bulkBusy && (
        <div className={`${styles.diagnosticsPanel} ${styles.muted}`}>
          <div className={styles.diagnosticsHeader}>
            <Check size={14} style={{ verticalAlign: 'middle', marginRight: 6 }} />
            {bulkSuccess}
          </div>
        </div>
      )}

      {isLoading ? (
        <div className={styles.loadingState}>
          <Loader2 size={18} className={styles.spinner} /> Loading entries…
        </div>
      ) : entries.length === 0 ? (
        <div className={styles.emptyState}>
          <p>No company knowledge entries yet.</p>
        </div>
      ) : (
        <div className={styles.suggestionsLayout}>
          <div className={styles.suggestionsList}>
            <div
              className={styles.suggestionsHeader}
              style={{ gridTemplateColumns: ENTRY_GRID_COLUMNS }}
            >
              <div className={styles.checkboxCell}>
                <input
                  type="checkbox"
                  className={styles.checkboxInput}
                  checked={allVisibleSelected}
                  ref={(el) => {
                    if (el) el.indeterminate = someVisibleSelected
                  }}
                  onChange={toggleAllVisible}
                  aria-label="select all visible"
                />
              </div>
              <div>Type</div>
              <div>Linked</div>
              <div>Title</div>
              <div />
              <div style={{ textAlign: 'center' }}>Actions</div>
            </div>
            {filtered.length === 0 ? (
              <div className={styles.emptyState} style={{ minHeight: 100 }}>
                Nothing matches that filter.
              </div>
            ) : (
              filtered.map((e) => {
                const isSel = selected.has(e.id)
                const isPin = pinnedId === e.id
                const warn = hasDiagnostics(e)
                return (
                  <div
                    key={e.id}
                    className={`${styles.suggestionsRow} ${isSel ? styles.suggestionsRowSelected : ''} ${isPin ? styles.suggestionsRowPinned : ''}`}
                    style={{ gridTemplateColumns: ENTRY_GRID_COLUMNS }}
                    onMouseEnter={() => setHoveredId(e.id)}
                    onClick={(ev) => {
                      if (ev.target.closest('button, input')) return
                      setPinnedId((cur) => (cur === e.id ? null : e.id))
                    }}
                  >
                    <div className={styles.checkboxCell} onClick={(ev) => ev.stopPropagation()}>
                      <input
                        type="checkbox"
                        className={styles.checkboxInput}
                        checked={isSel}
                        onChange={() => toggleOne(e.id)}
                        aria-label={`select ${e.title}`}
                      />
                    </div>
                    <div>
                      <span className={styles.kindBadge}>
                        {TYPE_LABELS[e.entryType] || e.entryType}
                      </span>
                    </div>
                    <div className={styles.muteText}>{describeLinks(e)}</div>
                    <div className={styles.titleCell}>{e.title}</div>
                    <div style={{ display: 'flex', justifyContent: 'center' }}>
                      {warn && (
                        <AlertTriangle
                          size={14}
                          style={{ color: '#b45309' }}
                          aria-label="has unresolved references"
                        />
                      )}
                    </div>
                    <div className={styles.actionsCell} onClick={(ev) => ev.stopPropagation()}>
                      <button
                        type="button"
                        className={styles.iconButton}
                        onClick={() => onEdit(e)}
                        title="Edit"
                      >
                        <Edit3 size={14} />
                      </button>
                      <button
                        type="button"
                        className={`${styles.iconButton} ${styles.iconButtonReject}`}
                        onClick={() => onDelete(e.id)}
                        title="Delete"
                      >
                        <X size={14} />
                      </button>
                    </div>
                  </div>
                )
              })
            )}
          </div>

          <PreviewPane
            entry={focused}
            pinned={Boolean(pinnedId) && pinnedId === focused?.id}
            onPin={() => focused && setPinnedId(focused.id)}
            onUnpin={() => setPinnedId(null)}
            onEdit={onEdit}
            onDelete={onDelete}
            busy={bulkBusy}
          />
        </div>
      )}
    </div>
  )
}
