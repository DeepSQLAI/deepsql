import { useMemo, useState } from 'react'
import { AlertTriangle, ChevronDown, ChevronRight, Loader2, Send } from 'lucide-react'
import { useSchemaAmbiguity } from '@/lib/hooks/queries'
import { useCompanyKnowledgeStore } from '@/lib/stores/useCompanyKnowledgeStore'
import styles from './CompanyKnowledgePanel.module.css'

const KIND_LABELS = {
  MISSING_TABLE_DESCRIPTION: 'Missing description',
  MISSING_COLUMN_DESCRIPTION: 'Missing column doc',
  SIMILAR_TABLE_NAMES: 'Similar names',
  COLUMN_DISAMBIGUATION: 'Column collision',
  GOD_TABLE: 'God table',
  ANTI_PATTERN: 'Anti-pattern',
}

function severityClass(value) {
  if (value >= 0.75) return styles.confidenceHigh
  if (value >= 0.5) return styles.confidenceMid
  return styles.confidenceLow
}

export default function UnresolvedPanel({ connectionId, onOpenInDocs }) {
  const { data, isLoading, error } = useSchemaAmbiguity(connectionId)
  const setPendingFocus = useCompanyKnowledgeStore((s) => s.setPendingFocusFromAmbiguity)
  const setActiveTab = useCompanyKnowledgeStore((s) => s.setActiveTab)

  const items = data?.items ?? []

  const [expanded, setExpanded] = useState(true)
  const [selected, setSelected] = useState(() => new Set())

  const toggleOne = (id) =>
    setSelected((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })

  const focusText = useMemo(() => {
    if (!selected.size) return ''
    const lines = items
      .filter((it, idx) => selected.has(idItem(it, idx)))
      .map((it) => {
        const head = `[${KIND_LABELS[it.kind] || it.kind}]`
        if (it.targetTable && it.targetColumn) return `${head} ${it.targetTable}.${it.targetColumn} — ${it.title}`
        if (it.targetTable) return `${head} ${it.targetTable} — ${it.title}`
        if (it.targetColumn) return `${head} column ${it.targetColumn} — ${it.title}`
        return `${head} ${it.title}`
      })
    return lines.join('\n')
  }, [items, selected])

  const handleSendToCodeSources = () => {
    setPendingFocus(focusText)
    setActiveTab('sources')
  }

  if (!connectionId) return null

  return (
    <section className={styles.unresolvedPanel}>
      <header className={styles.unresolvedHeader}>
        <button
          type="button"
          className={styles.unresolvedToggle}
          onClick={() => setExpanded((v) => !v)}
        >
          {expanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
          <AlertTriangle size={14} />
          <span className={styles.unresolvedTitle}>
            Unresolved ({isLoading ? '…' : items.length})
          </span>
        </button>
        <span className={styles.muteText}>
          Tables and columns the brain can&apos;t disambiguate yet · pick what code should clarify
        </span>
        <span className={styles.bulkBarSpacer} />
        {selected.size > 0 && (
          <button
            type="button"
            className={styles.primaryButton}
            onClick={handleSendToCodeSources}
          >
            <Send size={14} /> Send {selected.size} to scan focus
          </button>
        )}
      </header>

      {expanded && (
        <div className={styles.unresolvedBody}>
          {isLoading ? (
            <div className={styles.loadingState}>
              <Loader2 size={18} className={styles.spinner} /> Computing ambiguity…
            </div>
          ) : error ? (
            <div className={styles.errorState}>Failed to load ambiguity.</div>
          ) : items.length === 0 ? (
            <div className={styles.emptyState} style={{ minHeight: 80 }}>
              Nothing unresolved right now. Schema is well-documented.
            </div>
          ) : (
            <ul className={styles.unresolvedList}>
              {items.map((item, idx) => {
                const id = idItem(item, idx)
                const isSel = selected.has(id)
                return (
                  <li key={id} className={`${styles.unresolvedItem} ${isSel ? styles.suggestionsRowSelected : ''}`}>
                    <input
                      type="checkbox"
                      className={styles.checkboxInput}
                      checked={isSel}
                      onChange={() => toggleOne(id)}
                      aria-label={`select ${item.title}`}
                    />
                    <span className={`${styles.confidencePill} ${severityClass(item.severity)}`}>
                      {Math.round((item.severity || 0) * 100)}
                    </span>
                    <span className={styles.kindBadge}>
                      {KIND_LABELS[item.kind] || item.kind}
                    </span>
                    <span className={styles.unresolvedTarget}>
                      {item.targetTable && item.targetColumn
                        ? `${item.targetTable}.${item.targetColumn}`
                        : item.targetTable || (item.targetColumn ? `(any).${item.targetColumn}` : '—')}
                    </span>
                    <span className={styles.titleCell}>{item.title}</span>
                    {item.targetTable && onOpenInDocs && (
                      <button
                        type="button"
                        className={styles.iconButton}
                        title="Open in description editor"
                        onClick={() => onOpenInDocs({ table: item.targetTable, column: item.targetColumn })}
                      >
                        <ChevronRight size={14} />
                      </button>
                    )}
                  </li>
                )
              })}
            </ul>
          )}
        </div>
      )}
    </section>
  )
}

function idItem(item, idx) {
  const t = item.targetTable || ''
  const c = item.targetColumn || ''
  return `${item.kind}|${t}|${c}|${idx}`
}
