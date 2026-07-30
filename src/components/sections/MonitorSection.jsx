import { useEffect, useState } from 'react'
import { BarChart2 } from 'lucide-react'
import { slowQueriesAPI } from '@/lib/api/client'
import { useConnectionManager } from '@/lib/hooks/useConnectionManager'
import AnalyticsTab from '@/components/tabs/Monitoring/AnalyticsTab'
import styles from './SectionEmpty.module.css'

export default function MonitorSection() {
  const { connectionId } = useConnectionManager()
  const [hasData, setHasData] = useState(null) // null = loading
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    if (!connectionId) {
      setHasData(false)
      setLoading(false)
      return
    }
    setLoading(true)
    slowQueriesAPI
      .getHistory(connectionId)
      .then((data) => {
        const arr = Array.isArray(data) ? data : data?.content ?? []
        setHasData(arr.length > 0)
      })
      .catch(() => setHasData(false))
      .finally(() => setLoading(false))
  }, [connectionId])

  if (!connectionId) {
    return (
      <div className={styles.root}>
        <div className={styles.iconWrap}>
          <BarChart2 size={26} color="#9ca3af" />
        </div>
        <h2 className={styles.title}>No connection selected</h2>
        <p className={styles.subtitle}>Select a database connection to view monitoring data.</p>
      </div>
    )
  }

  if (loading) {
    return (
      <div className={styles.root}>
        <div className={styles.iconWrap}>
          <BarChart2 size={26} color="#9ca3af" />
        </div>
        <p className={styles.subtitle}>Checking query log configuration…</p>
      </div>
    )
  }

  if (hasData) {
    return <AnalyticsTab connectionId={connectionId} />
  }

  return (
    <div className={styles.root}>
      <div className={styles.iconWrap}>
        <BarChart2 size={26} color="#374151" />
      </div>
      <h2 className={styles.title}>Query logs not configured</h2>
      <p className={styles.subtitle}>
        Connect your slow query logs to unlock performance monitoring, query trends, alert
        thresholds, and optimization recommendations.
      </p>
      <div className={styles.pills}>
        <span className={styles.pill}>Slow query analysis</span>
        <span className={styles.pill}>Query trends</span>
        <span className={styles.pill}>Alert thresholds</span>
        <span className={styles.pill}>AI optimization</span>
      </div>
      <p style={{ fontSize: 13, color: '#6b7280', marginTop: 8 }}>
        Go to <strong>Connections → Settings → Query Logs</strong> to configure ingestion.
      </p>
    </div>
  )
}
