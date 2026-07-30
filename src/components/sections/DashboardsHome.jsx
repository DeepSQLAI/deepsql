import { useState, useEffect, useCallback } from 'react'
import { Plus, Sparkles, Clock, RefreshCw, Trash2, Loader2 } from 'lucide-react'
import { savedDashboardsAPI } from '@/lib/api/client'
import styles from './DashboardsHome.module.css'

function parseConfig(d) {
  try { return typeof d.dashboardConfig === 'string' ? JSON.parse(d.dashboardConfig || '{}') : (d.dashboardConfig || {}) }
  catch { return {} }
}
// A dashboard is "live" once it's published to the web (has a public link).
function statusOf(d) { return d.isPublic ? 'live' : 'draft' }
function chartCount(d) { return (parseConfig(d).charts || []).length }

function relTime(iso) {
  if (!iso) return ''
  const diff = Date.now() - new Date(iso).getTime()
  const m = Math.round(diff / 60000)
  if (m < 1) return 'just now'
  if (m < 60) return `${m}m ago`
  const h = Math.round(m / 60)
  if (h < 24) return `${h}h ago`
  return `${Math.round(h / 24)}d ago`
}

function Thumb({ charts }) {
  if (charts <= 0) {
    return <svg viewBox="0 0 160 64" width="100%" height="64" preserveAspectRatio="none" aria-hidden="true">
      <path d="M2,46 L40,48 L78,38 L116,42 L158,24 L158,64 L2,64 Z" fill="#EEEDFE" />
      <polyline points="2,46 40,48 78,38 116,42 158,24" fill="none" stroke="#7F77DD" strokeWidth="2" />
    </svg>
  }
  const heights = [40, 62, 80, 55, 34]
  return (
    <div className={styles.thumbBars}>
      {heights.map((h, i) => <span key={i} style={{ height: `${h}%`, background: i === 2 ? '#534AB7' : i % 2 ? '#7F77DD' : '#AFA9EC' }} />)}
    </div>
  )
}

export default function DashboardsHome({ connectionId, onOpen }) {
  const [dashboards, setDashboards] = useState([])
  const [loading, setLoading] = useState(false)
  const [filter, setFilter] = useState('all')
  const [deletingId, setDeletingId] = useState(null)

  const remove = useCallback(async (d, e) => {
    e?.stopPropagation()
    if (deletingId) return
    if (!window.confirm(`Delete “${d.name || 'this dashboard'}”? This can’t be undone.`)) return
    setDeletingId(d.id)
    try {
      await savedDashboardsAPI.deleteDashboard(d.id)
      setDashboards((list) => list.filter((x) => x.id !== d.id))
    } catch (err) {
      window.alert(`Couldn’t delete: ${err?.response?.data?.message || err?.message || 'error'}`)
    } finally {
      setDeletingId(null)
    }
  }, [deletingId])

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const res = await savedDashboardsAPI.getDashboardsByConnection(connectionId)
      setDashboards(res?.dashboards || [])
    } catch {
      setDashboards([])
    } finally {
      setLoading(false)
    }
  }, [connectionId])

  useEffect(() => { load() }, [load])

  const shown = dashboards.filter((d) => filter === 'all' || statusOf(d) === filter)

  return (
    <div className={styles.root}>
      <header className={styles.header}>
        <h1 className={styles.title}>Dashboards</h1>
        <div className={styles.actions}>
          <button className={styles.ghost} onClick={load} title="Refresh" aria-label="Refresh">
            <RefreshCw size={15} className={loading ? styles.spin : undefined} />
          </button>
          <button className={styles.primary} onClick={() => onOpen('new')}>
            <Plus size={15} /> New dashboard
          </button>
        </div>
      </header>

      <div className={styles.filters}>
        {['all', 'live', 'draft'].map((f) => (
          <button key={f} className={`${styles.chip} ${filter === f ? styles.chipActive : ''}`} onClick={() => setFilter(f)}>
            {f[0].toUpperCase() + f.slice(1)}
          </button>
        ))}
      </div>

      <div className={styles.grid}>
        {shown.map((d) => (
          <div
            key={d.id}
            className={styles.card}
            role="button"
            tabIndex={0}
            onClick={() => onOpen(d)}
            onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onOpen(d) } }}
          >
            <button
              className={styles.cardDelete}
              onClick={(e) => remove(d, e)}
              disabled={deletingId === d.id}
              title="Delete dashboard"
              aria-label={`Delete ${d.name || 'dashboard'}`}
            >
              {deletingId === d.id ? <Loader2 size={14} className={styles.spin} /> : <Trash2 size={14} />}
            </button>
            <div className={styles.thumb}><Thumb charts={chartCount(d)} /></div>
            <div className={styles.cardBody}>
              <div className={styles.cardTop}>
                <span className={styles.cardName}>{d.name || 'Untitled'}</span>
                <span className={statusOf(d) === 'live' ? styles.badgeLive : styles.badgeDraft}>
                  {statusOf(d) === 'live' ? 'Live' : 'Draft'}
                </span>
              </div>
              <div className={styles.cardMeta}><Clock size={12} /> Edited {relTime(d.updatedAt)}</div>
            </div>
          </div>
        ))}

        <button className={styles.ctaCard} onClick={() => onOpen('new')}>
          <span className={styles.ctaIcon}><Sparkles size={18} /></span>
          <span className={styles.ctaTitle}>Ask the agent</span>
          <span className={styles.ctaSub}>“Build a revenue dashboard for last quarter”</span>
        </button>
      </div>

      {!loading && shown.length === 0 && (
        <div className={styles.emptyNote}>No dashboards here yet — create one, or ask the agent to build it.</div>
      )}
    </div>
  )
}
