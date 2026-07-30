import { useCallback, useRef } from 'react'
import { useParams } from 'react-router-dom'
import { savedDashboardsAPI, dashboardQueryAPI } from '@/lib/api/client'
import DashboardViewer from '@/components/DashboardViewer'

// Internal standalone dashboard view (authed): a shareable deep link to one
// dashboard that logged-in users with access can open, rendered read-only.
export default function SharedDashboardPage() {
  const { id } = useParams()
  const connRef = useRef(null)

  const load = useCallback(async () => {
    const res = await savedDashboardsAPI.getDashboardById(id)
    const d = res?.savedDashboard || res?.dashboard || res
    if (!d) throw new Error('Dashboard not found.')
    let cfg = {}
    try { cfg = typeof d.dashboardConfig === 'string' ? JSON.parse(d.dashboardConfig || '{}') : (d.dashboardConfig || {}) } catch { cfg = {} }
    if (!cfg.html) throw new Error('This dashboard has no content.')
    connRef.current = d.connectionId
    return { title: d.name || cfg.title || 'Dashboard', html: cfg.html }
  }, [id])

  const queryFn = useCallback((sql, limit, signal) => dashboardQueryAPI.run(connRef.current, sql, limit, signal), [])

  return <DashboardViewer load={load} queryFn={queryFn} />
}
