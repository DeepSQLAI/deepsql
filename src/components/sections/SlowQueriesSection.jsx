import { useRef, useState } from 'react'
import { Activity, FileText, LineChart, Settings, Users } from 'lucide-react'
import { useConnectionManager } from '@/lib/hooks/useConnectionManager'
import { useSlowLogSourceConfig } from '@/lib/hooks/queries'
import QueryTrendsTab from '@/components/tabs/Performance/QueryTrendsTab'
import CustomerExplorer from '@/components/tabs/Performance/CustomerExplorer'
import SlowQuerySettingsPanel from '@/components/tabs/Performance/SlowQuerySettingsPanel'
import WorkloadAnalysisPanel from '@/components/tabs/Performance/WorkloadAnalysisPanel'
import SlowQuerySourceModal from '@/components/SlowQuerySourceModal'
import { HelpTooltip } from '@/components/tabs/Brain/components/HelpTooltip'
import sectionStyles from './TopLevelSection.module.css'
import styles from './SlowQueriesSection.module.css'

const TABS = [
  { id: 'trends', label: 'Query Trends', icon: LineChart },
  { id: 'customers', label: 'By Customer', icon: Users },
  { id: 'workload', label: 'Workload', icon: Activity },
  { id: 'settings', label: 'Settings', icon: Settings },
]

const LOG_SOURCE_HELP = {
  title: 'Slow query log',
  description:
    'Query trends, per-customer load, and workload analysis all read from ingested slow-query logs. Attach CloudWatch, S3, Azure, GCP, Datadog, Elasticsearch, or a file upload before those views can run.',
}

/**
 * Combined Performance section — Slow Queries + Workload Analysis.
 *
 * Both surfaces need a slow-query log source. If none is attached, the page
 * is a single empty state whose CTA opens SlowQuerySourceModal.
 */
export default function SlowQueriesSection() {
  const { connectionId, selectedConnection } = useConnectionManager()
  const [tab, setTab] = useState('trends')
  const tabRefs = useRef({})

  /** Arrow / Home / End move between tabs, as the ARIA tabs pattern expects. */
  const onTabKeyDown = (e) => {
    const step = { ArrowRight: 1, ArrowLeft: -1 }[e.key]
    if (step === undefined && e.key !== 'Home' && e.key !== 'End') return
    e.preventDefault()
    // Resolve the next tab from the *current* state, not the `tab` captured when this
    // handler was created: two keypresses within one render would otherwise both move
    // relative to the same starting index and selection would stick after the first.
    setTab((current) => {
      const i = TABS.findIndex((t) => t.id === current)
      const next = e.key === 'Home' ? 0
        : e.key === 'End' ? TABS.length - 1
        : (i + step + TABS.length) % TABS.length
      const id = TABS[next].id
      // Focus follows selection, per the ARIA tabs pattern. Deferred so the tab is
      // already rendered with tabIndex=0 when we focus it.
      queueMicrotask(() => tabRefs.current[id]?.focus())
      return id
    })
  }
  const [logSourceModalOpen, setLogSourceModalOpen] = useState(false)
  const logSourceQ = useSlowLogSourceConfig(connectionId)
  const hasLogSource = Boolean(logSourceQ.data?.id)

  return (
    <div className={sectionStyles.page}>
      <div className={sectionStyles.header}>
        <div className={sectionStyles.eyebrow}>Performance</div>
        <h1 className={sectionStyles.title}>Slow queries &amp; workload</h1>
        <p className={sectionStyles.subtitle}>
          Per-query trends, regressions, customer attribution, and a holistic
          workload report — all from the same slow-query log.
        </p>
      </div>

      {!connectionId ? (
        <div className={styles.empty}>
          Select a database connection to see performance analytics.
        </div>
      ) : logSourceQ.isLoading ? (
        <div className={styles.empty}>Checking slow query log source…</div>
      ) : logSourceQ.isError ? (
        <div className={styles.empty}>
          Could not load the slow query log configuration for this connection.
        </div>
      ) : !hasLogSource ? (
        <div className={styles.setupCard}>
          <FileText size={32} className={styles.setupIcon} />
          <h2 className={styles.setupTitle}>Configure slow queries</h2>
          <p className={styles.setupCopy}>
            <HelpTooltip content={LOG_SOURCE_HELP}>
              <span>
                Attach a slow-query log source to unlock query trends, per-customer
                breakdown, and workload analysis.
              </span>
            </HelpTooltip>
            {' '}
            DeepSQL pulls from CloudWatch, S3, Azure Blob, GCP, Datadog,
            Elasticsearch, or a file you upload.
          </p>
          <button
            type="button"
            className={styles.setupCta}
            data-testid="configure-slow-queries"
            onClick={() => setLogSourceModalOpen(true)}
          >
            <FileText size={14} />
            Configure slow queries
          </button>
        </div>
      ) : (
        <>
          <div className={styles.toolbar}>
            <div
              className={styles.tabBar}
              role="tablist"
              aria-label="Performance views"
              onKeyDown={onTabKeyDown}
            >
              {TABS.map((t) => {
                const Icon = t.icon
                const active = tab === t.id
                return (
                  <button
                    key={t.id}
                    id={`perf-tab-${t.id}`}
                    type="button"
                    role="tab"
                    aria-selected={active}
                    aria-controls={`perf-panel-${t.id}`}
                    // Roving tabindex: the tablist is one tab stop and the arrow keys move
                    // within it, per the ARIA tabs pattern. Without it every tab was its
                    // own stop and arrow keys did nothing.
                    tabIndex={active ? 0 : -1}
                    ref={(el) => { tabRefs.current[t.id] = el }}
                    className={`${styles.tabButton} ${active ? styles.tabButtonActive : ''}`}
                    onClick={() => setTab(t.id)}
                  >
                    <Icon size={14} />
                    <span>{t.label}</span>
                  </button>
                )
              })}
            </div>
            <button
              type="button"
              className={styles.logSourceBtn}
              onClick={() => setLogSourceModalOpen(true)}
            >
              <FileText size={13} />
              Log source
            </button>
          </div>

          <div
            className={styles.content}
            role="tabpanel"
            id={`perf-panel-${tab}`}
            aria-labelledby={`perf-tab-${tab}`}
            tabIndex={0}
          >
            {tab === 'trends' && <QueryTrendsTab connectionId={connectionId} />}
            {tab === 'customers' && <CustomerExplorer connectionId={connectionId} />}
            {tab === 'workload' && <WorkloadAnalysisPanel connectionId={connectionId} />}
            {tab === 'settings' && <SlowQuerySettingsPanel connectionId={connectionId} />}
          </div>
        </>
      )}

      {logSourceModalOpen && connectionId && (
        <SlowQuerySourceModal
          connectionId={connectionId}
          connectionName={selectedConnection?.connectionName}
          dbType={selectedConnection?.dbType || 'mysql'}
          onClose={() => {
            setLogSourceModalOpen(false)
            logSourceQ.refetch()
          }}
        />
      )}
    </div>
  )
}
