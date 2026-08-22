import { useMemo, useState } from 'react'
import {
  AlertCircle,
  Check,
  Loader2,
  Pin,
  PinOff,
  Search,
  X,
} from 'lucide-react'
import {
  useAllCodeScanSuggestions,
  useBulkDecideCodeScanSuggestions,
  useDecideCodeScanSuggestion,
} from '@/lib/hooks/queries'
import { useCompanyKnowledgeStore } from '@/lib/stores/useCompanyKnowledgeStore'
import styles from './CompanyKnowledgePanel.module.css'

const SEARCH_KEYS = ['title', 'content', 'targetObject']

function confidenceClass(value) {
  if (value >= 0.85) return styles.confidenceHigh
  if (value >= 0.6) return styles.confidenceMid
  return styles.confidenceLow
}

function kindClass(targetKind) {
  if (targetKind === 'SCHEMA_DOC') return styles.kindBadgeSchema
  if (targetKind === 'KNOWLEDGE_ENTRY') return styles.kindBadgeKnowledge
  return ''
}

function kindLabel(targetKind) {
  if (targetKind === 'SCHEMA_DOC') return 'Schema'
  if (targetKind === 'KNOWLEDGE_ENTRY') return 'Knowledge'
  return targetKind
}

function buildSearchHaystack(s) {
  const parts = SEARCH_KEYS.map((k) => s[k] || '')
  if (Array.isArray(s.linkedTables)) parts.push(s.linkedTables.join(' '))
  if (Array.isArray(s.linkedColumns)) parts.push(s.linkedColumns.join(' '))
  if (s.payload && typeof s.payload === 'object') {
    if (Array.isArray(s.payload.businessTerms)) parts.push(s.payload.businessTerms.join(' '))
    if (s.payload.rationale) parts.push(s.payload.rationale)
    if (s.payload.entryType) parts.push(s.payload.entryType)
  }
  return parts.join(' ').toLowerCase()
}

function buildRuleExcerpt(suggestion) {
  if (!suggestion) return null
  const entryType = suggestion.payload?.entryType || 'BUSINESS_RULE'
  if (suggestion.targetKind === 'SCHEMA_DOC') {
    const target = suggestion.targetObject || 'schema object'
    return {
      label: 'Schema note that would be saved',
      headline: target,
      body: suggestion.content,
      meta: suggestion.payload?.businessTerms?.length
        ? `Terms: ${suggestion.payload.businessTerms.join(', ')}`
        : null,
    }
  }
  return {
    label: `${String(entryType).replaceAll('_', ' ').toLowerCase()} that would be saved`,
    headline: suggestion.title,
    body: suggestion.content,
    meta: suggestion.targetObject ? `Linked: ${suggestion.targetObject}` : null,
  }
}

function PreviewPane({ suggestion, pinned, onPin, onUnpin, onDecide, decidePending }) {
  if (!suggestion) {
    return (
      <aside className={styles.previewPane}>
        <div className={styles.previewPaneEmpty}>
          Click a suggestion bubble to preview the business rule or schema note that would be created.
        </div>
      </aside>
    )
  }
  const linkedTables = suggestion.linkedTables || []
  const linkedColumns = suggestion.linkedColumns || []
  const sources = suggestion.sourceFiles || []
  const businessTerms = suggestion.payload?.businessTerms || []
  const rationale = suggestion.payload?.rationale
  const excerpt = buildRuleExcerpt(suggestion)
  const mergedWithExisting = Boolean(suggestion.payload?.mergedWithExisting)
  const existingExcerpt = suggestion.payload?.existingExcerpt
  const existingTitle = suggestion.payload?.existingTitle
  return (
    <aside className={styles.previewPane}>
      <div className={styles.previewBadgeRow}>
        <span className={`${styles.kindBadge} ${kindClass(suggestion.targetKind)}`}>
          {kindLabel(suggestion.targetKind)}
        </span>
        <span className={`${styles.confidencePill} ${confidenceClass(suggestion.confidence)}`}>
          {Math.round((suggestion.confidence || 0) * 100)}%
        </span>
        {mergedWithExisting && (
          <span className={styles.mergeBadge}>Merges existing</span>
        )}
        {suggestion.payload?.entryType && (
          <span className={styles.kindBadge}>{suggestion.payload.entryType}</span>
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
      <h3 className={styles.previewTitle}>{suggestion.title}</h3>
      {suggestion.targetObject && (
        <div className={styles.previewTarget}>{suggestion.targetObject}</div>
      )}

      {mergedWithExisting && existingExcerpt && (
        <div className={`${styles.previewSection} ${styles.previewSectionExisting}`}>
          <div className={styles.previewLabel}>Already in knowledge base</div>
          {existingTitle && <div className={styles.previewExistingTitle}>{existingTitle}</div>}
          <p className={styles.previewBodyMuted}>{existingExcerpt}</p>
          <p className={styles.previewMergeHint}>
            Approving merges this scan finding into the existing rule instead of creating a duplicate.
          </p>
        </div>
      )}

      {excerpt && (
        <div className={`${styles.previewSection} ${styles.previewSectionExcerpt}`}>
          <div className={styles.previewLabel}>{excerpt.label}</div>
          <div className={styles.ruleExcerptCard}>
            <div className={styles.ruleExcerptHeadline}>{excerpt.headline}</div>
            <p className={styles.ruleExcerptBody}>{excerpt.body}</p>
            {excerpt.meta && <div className={styles.ruleExcerptMeta}>{excerpt.meta}</div>}
          </div>
        </div>
      )}

      <div className={styles.previewSection}>
        <div className={styles.previewLabel}>Full description</div>
        <p className={styles.previewBody}>{suggestion.content}</p>
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

      {businessTerms.length > 0 && (
        <div className={styles.previewSection}>
          <div className={styles.previewLabel}>Business terms</div>
          <div className={styles.linkGroup}>
            {businessTerms.map((bt) => (
              <span key={bt} className={styles.linkChipMuted}>{bt}</span>
            ))}
          </div>
        </div>
      )}

      {rationale && (
        <div className={styles.previewSection}>
          <div className={styles.previewLabel}>Why</div>
          <p className={styles.previewBody}>{rationale}</p>
        </div>
      )}

      {sources.length > 0 && (
        <div className={styles.previewSection}>
          <div className={styles.previewLabel}>Source ({sources.length})</div>
          {sources.map((sf, idx) => (
            <div key={`${sf.path}-${idx}`} className={styles.previewSourceItem}>
              <code>{sf.path}</code>:{sf.startLine}–{sf.endLine}
              {sf.rationale && (
                <div className={styles.previewSourceRationale}>{sf.rationale}</div>
              )}
            </div>
          ))}
        </div>
      )}

      {suggestion.status === 'PENDING' && (
        <div className={styles.previewActionsRow}>
          <button
            type="button"
            className={styles.secondaryButton}
            onClick={() => onDecide(suggestion.id, 'REJECTED')}
            disabled={decidePending}
          >
            <X size={14} /> Reject
          </button>
          <button
            type="button"
            className={styles.primaryButton}
            onClick={() => onDecide(suggestion.id, 'APPROVED')}
            disabled={decidePending}
          >
            <Check size={14} /> Approve
          </button>
        </div>
      )}
      <div className={styles.previewPinHint}>Click a bubble to preview · pin keeps the excerpt open</div>
    </aside>
  )
}

export default function SuggestionsQueueTab({ connectionId }) {
  const status = useCompanyKnowledgeStore((s) => s.suggestionStatusFilter)
  const setStatus = useCompanyKnowledgeStore((s) => s.setSuggestionStatusFilter)

  const { data: items = [], isLoading, error } = useAllCodeScanSuggestions({
    connectionId,
    status,
  })
  const decide = useDecideCodeScanSuggestion()
  const bulkDecide = useBulkDecideCodeScanSuggestions()

  const [search, setSearch] = useState('')
  const [minConfidence, setMinConfidence] = useState(0)
  const [selected, setSelected] = useState(() => new Set())
  const [activeId, setActiveId] = useState(null)
  const [pinnedId, setPinnedId] = useState(null)
  const [bulkError, setBulkError] = useState(null)
  const [bulkSuccess, setBulkSuccess] = useState(null)

  // Reset selection when filter inputs change so we don't carry over hidden ids.
  // React-19-friendly "derived state": adjust state during render rather than
  // in an effect (no cascading renders, no useEffect dance).
  const filterSignature = `${status}|${search}|${minConfidence}`
  const [lastSignature, setLastSignature] = useState(filterSignature)
  if (lastSignature !== filterSignature) {
    setLastSignature(filterSignature)
    setSelected(new Set())
  }

  // Decorate each item with its lowercased haystack once.
  const indexed = useMemo(
    () => items.map((s) => ({ ...s, _hay: buildSearchHaystack(s) })),
    [items],
  )

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase()
    return indexed.filter((s) => {
      if ((s.confidence || 0) < minConfidence) return false
      if (!q) return true
      return s._hay.includes(q)
    })
  }, [indexed, search, minConfidence])

  const selectedIds = useMemo(
    () => filtered.filter((s) => selected.has(s.id)).map((s) => s.id),
    [filtered, selected],
  )

  const allVisibleSelected = filtered.length > 0 && filtered.every((s) => selected.has(s.id))
  const someVisibleSelected = !allVisibleSelected && filtered.some((s) => selected.has(s.id))

  const focusedId = pinnedId || activeId || filtered[0]?.id || null
  const focused = useMemo(
    () => indexed.find((s) => s.id === focusedId) || null,
    [indexed, focusedId],
  )

  // ---- handlers ----

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
      const allOn = filtered.every((s) => next.has(s.id))
      if (allOn) {
        filtered.forEach((s) => next.delete(s.id))
      } else {
        filtered.forEach((s) => next.add(s.id))
      }
      return next
    })

  const handleBulk = (decision) => {
    if (selectedIds.length === 0) return
    setBulkError(null)
    setBulkSuccess(null)
    bulkDecide.mutate(
      { connectionId, ids: selectedIds, decision },
      {
        onSuccess: (data) => {
          const succeeded = data?.succeeded ?? 0
          const requested = data?.requested ?? selectedIds.length
          const failed = data?.failed ?? Math.max(0, requested - succeeded)
          const verb = decision === 'APPROVED' ? 'Approved' : 'Rejected'
          const summary = `${verb} ${succeeded} of ${requested}`
          if (failed > 0 || succeeded === 0) {
            const details = Array.isArray(data?.failures)
              ? data.failures
                  .slice(0, 3)
                  .map((f) => f?.error || f?.id)
                  .filter(Boolean)
                  .join(' · ')
              : ''
            setBulkError(
              details
                ? `${summary}. ${failed} failed: ${details}`
                : `${summary}. ${failed} failed — check server logs for details.`,
            )
            setBulkSuccess(null)
          } else {
            setBulkSuccess(summary)
          }
          setSelected(new Set())
        },
        onError: (err) => {
          setBulkError(err?.response?.data?.error || err?.message || 'Bulk action failed')
        },
      },
    )
  }

  const handleSingleDecide = (id, decision) => {
    decide.mutate(
      { suggestionId: id, connectionId, decision },
      {
        onSuccess: () => {
          setSelected((prev) => {
            const next = new Set(prev)
            next.delete(id)
            return next
          })
          if (pinnedId === id) setPinnedId(null)
          setBulkError(null)
          setBulkSuccess(decision === 'APPROVED' ? 'Approved 1 suggestion' : 'Rejected 1 suggestion')
        },
        onError: (err) => {
          setBulkSuccess(null)
          setBulkError(err?.response?.data?.error || err?.message || 'Decision failed')
        },
      },
    )
  }

  // ---- render ----

  if (!connectionId) {
    return <div className={styles.emptyState}>Select a connection to review suggestions.</div>
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
      <div className={styles.toolbar}>
        <div style={{ position: 'relative', display: 'flex', alignItems: 'center', flex: '1 1 240px' }}>
          <Search size={14} style={{ position: 'absolute', left: 12, color: '#9ca3af' }} />
          <input
            className={styles.searchInputLarge}
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search title, target, content, business terms…"
            style={{ paddingLeft: 32 }}
          />
        </div>
        <label className={styles.statusFilter}>
          Status
          <select
            value={status}
            onChange={(e) => setStatus(e.target.value)}
            style={{ border: 'none', background: 'transparent', fontWeight: 600 }}
          >
            <option value="PENDING">Pending</option>
            <option value="APPROVED">Approved</option>
            <option value="REJECTED">Rejected</option>
            <option value="SUPERSEDED">Superseded</option>
          </select>
        </label>
        <label className={styles.confidenceFilter}>
          Min confidence
          <input
            type="range"
            min={0}
            max={100}
            step={1}
            value={Math.round(minConfidence * 100)}
            onChange={(e) => setMinConfidence(Number(e.target.value) / 100)}
          />
          <span>{Math.round(minConfidence * 100)}%</span>
        </label>
        <span className={styles.muteText}>
          {filtered.length} of {items.length} shown
        </span>
      </div>

      {selectedIds.length > 0 && (
        <div className={styles.bulkBar}>
          <span className={styles.bulkBarCount}>{selectedIds.length} selected</span>
          <span className={styles.bulkBarSpacer} />
          <button
            type="button"
            className={styles.secondaryButton}
            onClick={() => handleBulk('REJECTED')}
            disabled={bulkDecide.isPending}
          >
            {bulkDecide.isPending ? (
              <><Loader2 size={14} className={styles.spinner} /> Rejecting…</>
            ) : (
              <><X size={14} /> Reject {selectedIds.length}</>
            )}
          </button>
          <button
            type="button"
            className={styles.primaryButton}
            onClick={() => handleBulk('APPROVED')}
            disabled={bulkDecide.isPending}
          >
            {bulkDecide.isPending ? (
              <><Loader2 size={14} className={styles.spinner} /> Approving…</>
            ) : (
              <><Check size={14} /> Approve {selectedIds.length}</>
            )}
          </button>
        </div>
      )}

      {bulkError && (
        <div className={`${styles.diagnosticsPanel} ${styles.danger}`}>
          <div className={styles.diagnosticsHeader}>
            <AlertCircle size={14} style={{ verticalAlign: 'middle', marginRight: 6 }} />
            Bulk action failed
          </div>
          <p className={styles.diagnosticMessage}>{bulkError}</p>
        </div>
      )}
      {bulkSuccess && !bulkDecide.isPending && (
        <div className={`${styles.diagnosticsPanel} ${styles.muted}`}>
          <div className={styles.diagnosticsHeader}>
            <Check size={14} style={{ verticalAlign: 'middle', marginRight: 6 }} />
            {bulkSuccess}
          </div>
        </div>
      )}

      {isLoading ? (
        <div className={styles.loadingState}>
          <Loader2 size={18} className={styles.spinner} /> Loading suggestions…
        </div>
      ) : error ? (
        <div className={styles.errorState}>Failed to load suggestions.</div>
      ) : items.length === 0 ? (
        <div className={styles.emptyState}>
          {status === 'PENDING'
            ? 'No suggestions waiting for review. Run a code scan to generate some.'
            : `No ${status.toLowerCase()} suggestions yet.`}
        </div>
      ) : (
        <div className={styles.suggestionsLayout}>
          <div className={styles.suggestionsBubblePanel}>
            <div className={styles.suggestionsBubbleToolbar}>
              <label className={styles.bubbleSelectAll}>
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
                Select all visible
              </label>
            </div>
            {filtered.length === 0 ? (
              <div className={styles.emptyState} style={{ minHeight: 100 }}>
                Nothing matches that filter.
              </div>
            ) : (
              <div className={styles.suggestionBubbles} role="list">
                {filtered.map((s) => {
                  const isSel = selected.has(s.id)
                  const isActive = focusedId === s.id
                  const merged = Boolean(s.payload?.mergedWithExisting)
                  return (
                    <div key={s.id} className={styles.suggestionBubbleWrap} role="listitem">
                      <label className={styles.suggestionBubbleCheckbox} onClick={(e) => e.stopPropagation()}>
                        <input
                          type="checkbox"
                          className={styles.checkboxInput}
                          checked={isSel}
                          onChange={() => toggleOne(s.id)}
                          aria-label={`select ${s.title}`}
                        />
                      </label>
                      <button
                        type="button"
                        className={`${styles.suggestionBubble} ${isActive ? styles.suggestionBubbleActive : ''} ${isSel ? styles.suggestionBubbleSelected : ''}`}
                        onClick={() => {
                          setActiveId(s.id)
                          setPinnedId((cur) => (cur === s.id ? cur : null))
                        }}
                        title={s.content}
                      >
                        <span className={styles.suggestionBubbleTop}>
                          <span className={`${styles.kindBadge} ${kindClass(s.targetKind)}`}>
                            {kindLabel(s.targetKind)}
                          </span>
                          <span className={`${styles.confidencePill} ${confidenceClass(s.confidence)}`}>
                            {Math.round((s.confidence || 0) * 100)}%
                          </span>
                        </span>
                        <span className={styles.suggestionBubbleTitle}>{s.title}</span>
                        {s.targetObject && (
                          <span className={styles.suggestionBubbleTarget}>{s.targetObject}</span>
                        )}
                        {merged && <span className={styles.suggestionBubbleMerge}>Merges existing</span>}
                      </button>
                    </div>
                  )
                })}
              </div>
            )}
          </div>

          <PreviewPane
            suggestion={focused}
            pinned={Boolean(pinnedId) && pinnedId === focused?.id}
            onPin={() => focused && setPinnedId(focused.id)}
            onUnpin={() => setPinnedId(null)}
            onDecide={handleSingleDecide}
            decidePending={decide.isPending}
          />
        </div>
      )}
    </div>
  )
}
