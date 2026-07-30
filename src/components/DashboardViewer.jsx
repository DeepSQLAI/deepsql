import { useEffect, useState } from 'react'
import { Loader2, LineChart } from 'lucide-react'
import DashboardArtifact from './DashboardArtifact'

// A chrome-less, read-only, full-page dashboard view shared by the internal
// (/dashboard-view/:id, authed) and public (/share/dashboard/:token) routes.
// `load` returns { title, html } and `queryFn(sql, limit, signal)` runs a query
// through whichever endpoint (authed broker or public token) the route uses.
export default function DashboardViewer({ load, queryFn }) {
  const [state, setState] = useState({ loading: true, error: null, title: '', html: '' })

  useEffect(() => {
    let alive = true
    setState({ loading: true, error: null, title: '', html: '' })
    load()
      .then((d) => { if (alive) setState({ loading: false, error: null, title: d.title || 'Dashboard', html: d.html || '' }) })
      .catch((e) => { if (alive) setState({ loading: false, error: e?.message || 'Unable to load this dashboard.', title: '', html: '' }) })
    return () => { alive = false }
  }, [load])

  return (
    <div style={{ minHeight: '100vh', background: '#f8fafc', fontFamily: "'Maven Pro', system-ui, sans-serif" }}>
      <header style={{
        display: 'flex', alignItems: 'center', gap: 10, padding: '14px 22px',
        borderBottom: '1px solid #e7e9ee', background: '#fff', position: 'sticky', top: 0, zIndex: 10,
      }}>
        <span style={{
          display: 'inline-flex', width: 26, height: 26, borderRadius: 7, background: '#111318',
          alignItems: 'center', justifyContent: 'center',
        }}><LineChart size={14} color="#fff" /></span>
        <span style={{ fontWeight: 700, fontSize: 15, color: '#111318' }}>
          {state.loading ? 'Loading…' : (state.title || 'Dashboard')}
        </span>
        <span style={{ marginLeft: 'auto', fontSize: 12, color: '#8b909b' }}>Powered by DeepSQL</span>
      </header>

      {state.loading && (
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, color: '#5b616e', padding: 40, justifyContent: 'center' }}>
          <Loader2 size={16} style={{ animation: 'spin 1s linear infinite' }} /> Loading dashboard…
          <style>{'@keyframes spin{to{transform:rotate(360deg)}}'}</style>
        </div>
      )}
      {!state.loading && state.error && (
        <div style={{ color: '#6b7280', padding: 48, textAlign: 'center', fontSize: 15 }}>{state.error}</div>
      )}
      {!state.loading && !state.error && (
        <DashboardArtifact html={state.html} queryFn={queryFn} onError={() => {}} />
      )}
    </div>
  )
}
