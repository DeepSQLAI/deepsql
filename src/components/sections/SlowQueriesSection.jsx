import { useState } from 'react'
import { LineChart, Settings, Users } from 'lucide-react'
import { useConnectionManager } from '@/lib/hooks/useConnectionManager'
import QueryTrendsTab from '@/components/tabs/Performance/QueryTrendsTab'
import CustomerExplorer from '@/components/tabs/Performance/CustomerExplorer'
import SlowQuerySettingsPanel from '@/components/tabs/Performance/SlowQuerySettingsPanel'
import sectionStyles from './TopLevelSection.module.css'
import styles from './SlowQueriesSection.module.css'

const TABS = [
  { id: 'trends', label: 'Query Trends', icon: LineChart },
  { id: 'customers', label: 'By Customer', icon: Users },
  { id: 'settings', label: 'Settings', icon: Settings },
]

/**
 * Top-level "Slow Queries" section — the 30-day slow-query analytics surface,
 * promoted out of the Brain tab strip into its own sidebar destination.
 *
 * Two sub-views: the query trends/timeline/regressions, and the settings
 * panel where the tenant column for per-customer attribution is configured.
 */
export default function SlowQueriesSection() {
  const { connectionId } = useConnectionManager()
  const [tab, setTab] = useState('trends')

  return (
    <div className={sectionStyles.page}>
      <div className={sectionStyles.header}>
        <div className={sectionStyles.eyebrow}>Slow Queries</div>
        <h1 className={sectionStyles.title}>Slow query analytics</h1>
        <p className={sectionStyles.subtitle}>
          Per-query performance trends over the last 30 days, regression detection,
          and per-customer breakdown.
        </p>
      </div>

      {!connectionId ? (
        <div className={styles.empty}>
          Select a database connection to see slow query analytics.
        </div>
      ) : (
        <>
          <div className={styles.tabBar} role="tablist" aria-label="Slow query views">
            {TABS.map((t) => {
              const Icon = t.icon
              const active = tab === t.id
              return (
                <button
                  key={t.id}
                  type="button"
                  role="tab"
                  aria-selected={active}
                  className={`${styles.tabButton} ${active ? styles.tabButtonActive : ''}`}
                  onClick={() => setTab(t.id)}
                >
                  <Icon size={14} />
                  <span>{t.label}</span>
                </button>
              )
            })}
          </div>

          <div className={styles.content}>
            {tab === 'trends' && <QueryTrendsTab connectionId={connectionId} />}
            {tab === 'customers' && <CustomerExplorer connectionId={connectionId} />}
            {tab === 'settings' && <SlowQuerySettingsPanel connectionId={connectionId} />}
          </div>
        </>
      )}
    </div>
  )
}
