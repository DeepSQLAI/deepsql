import { useEffect, useState, useRef, useCallback } from 'react'
import { Loader2, LineChart, RefreshCw } from 'lucide-react'
import DashboardArtifact from './DashboardArtifact'

// A chrome-less, read-only, full-page dashboard view shared by the internal
// (/dashboard-view/:id, authed) and public (/share/dashboard/:token) routes.
// `load` returns { title, html } and `queryFn(sql, limit, signal)` runs a query
// through whichever endpoint (authed broker or public token) the route uses.
// `autoRefreshMs` (optional) re-runs the artifact's queries on a fixed cadence
// without a full page reload — used by kiosk mode; `hideChrome` additionally
// strips the header entirely for a wall-mounted display.
export default function DashboardViewer({ load, queryFn, autoRefreshMs, hideChrome }) {
  const [state, setState] = useState({ loading: true, error: null, title: '', html: '' })
  const artifactRef = useRef(null)

  useEffect(() => {
    let alive = true
    setState({ loading: true, error: null, title: '', html: '' })
    load()
      .then((d) => { if (alive) setState({ loading: false, error: null, title: d.title || 'Dashboard', html: d.html || '' }) })
      .catch((e) => { if (alive) setState({ loading: false, error: e?.message || 'Unable to load this dashboard.', title: '', html: '' }) })
    return () => { alive = false }
  }, [load])

  const refreshNow = useCallback(() => artifactRef.current?.reload(), [])

  useEffect(() => {
    if (!autoRefreshMs || state.loading || state.error) return undefined
    const id = setInterval(refreshNow, autoRefreshMs)
    return () => clearInterval(id)
  }, [autoRefreshMs, state.loading, state.error, refreshNow])

  return (
    <div style={{ minHeight: '100vh', background: '#f8fafc', fontFamily: "'Maven Pro', system-ui, sans-serif" }}>
      {!hideChrome && (
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
          {!state.loading && !state.error && (
            <button
              onClick={refreshNow}
              title="Refresh"
              aria-label="Refresh"
              style={{
                display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
                width: 26, height: 26, marginLeft: 8, border: '1px solid #e2e2e2', borderRadius: 7,
                background: '#fff', color: '#555', cursor: 'pointer',
              }}
            ><RefreshCw size={13} style={{ flexShrink: 0 }} /></button>
          )}
          <span style={{ marginLeft: 'auto', fontSize: 12, color: '#8b909b' }}>Powered by DeepSQL</span>
        </header>
      )}

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
        <DashboardArtifact ref={artifactRef} html={state.html} queryFn={queryFn} onError={() => {}} />
      )}
    </div>
  )
}
