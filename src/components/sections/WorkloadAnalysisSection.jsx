import { useConnectionManager } from '@/lib/hooks/useConnectionManager'
import WorkloadAnalysisPanel from '@/components/tabs/Performance/WorkloadAnalysisPanel'
import sectionStyles from './TopLevelSection.module.css'
import styles from './SlowQueriesSection.module.css'

/**
 * Top-level "Workload Analysis" section — the holistic, on-demand report that
 * composes index recommendations, query rewrites, pre-aggregations, and index
 * cleanup over the most-impactful queries. Promoted out of the Slow Queries
 * tab strip into its own sidebar destination (sits right below Slow Queries).
 */
export default function WorkloadAnalysisSection() {
  const { connectionId } = useConnectionManager()

  return (
    <div className={sectionStyles.page}>
      <div className={sectionStyles.header}>
        <div className={sectionStyles.eyebrow}>Workload Analysis</div>
        <h1 className={sectionStyles.title}>Holistic workload analysis</h1>
        <p className={sectionStyles.subtitle}>
          One holistic pass over your most-impactful queries — index
          recommendations, query rewrites, pre-aggregations, and index cleanup,
          composed into a single report.
        </p>
      </div>

      {!connectionId ? (
        <div className={styles.empty}>
          Select a database connection to analyze its workload.
        </div>
      ) : (
        <div className={styles.content}>
          <WorkloadAnalysisPanel connectionId={connectionId} />
        </div>
      )}
    </div>
  )
}
