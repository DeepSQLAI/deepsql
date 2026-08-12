import { useState, useEffect, useCallback, useRef } from 'react'
import { Plus, Sparkles, Clock, RefreshCw, Trash2, Loader2, LayoutDashboard, AlertTriangle } from 'lucide-react'
import { savedDashboardsAPI } from '@/lib/api/client'
import styles from './DashboardsHome.module.css'

// A dashboard is "live" once it's published to the web (has a public link).
function statusOf(d) { return d.isPublic ? 'live' : 'draft' }

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

// A tiny hash of the dashboard's own id, so each card's thumbnail bars look
// distinct and stable across reloads rather than identical or random.
function seedFrom(id) {
  let h = 0
  for (let i = 0; i < (id || '').length; i++) h = (h * 31 + id.charCodeAt(i)) >>> 0
  return h
}

// Dashboard content isn't parseable from dashboardConfig (it's an opaque HTML
// artifact, not spec data) — so the thumbnail is a stylized mini-preview, not a
// real render. Deterministic per-id bar heights keep the gallery from looking
// like every card is the exact same placeholder.
function Thumb({ id }) {
  const seed = seedFrom(id)
  // Unsigned shift + byte mask — a signed >> with a big shift/modulo combo can
  // yield negative numbers in JS, which collapse a bar to 0 height.
  const heights = [0, 1, 2, 3, 4].map((i) => 30 + (((seed >>> (i * 4)) & 0xff) % 60))
  return (
    <div className={styles.thumbMock}>
      <div className={styles.thumbMockKpis}>
        <span className={styles.thumbMockKpi} />
        <span className={styles.thumbMockKpi} />
        <span className={styles.thumbMockKpi} />
      </div>
      <div className={styles.thumbBars}>
        {heights.map((h, i) => (
          <span key={i} style={{ height: `${Math.round(h * 0.34)}px` }} className={i === 2 ? styles.thumbBarAccent : styles.thumbBar} />
        ))}
      </div>
    </div>
  )
}

export default function DashboardsHome({ connectionId, onOpen }) {
  const [dashboards, setDashboards] = useState([])
  const [loading, setLoading] = useState(false)
  const [filter, setFilter] = useState('all')
  const [deletingId, setDeletingId] = useState(null)
  // Id of the card showing its inline "delete this?" popover (native window.confirm
  // reads as out-of-place browser chrome next to the rest of this redesigned UI, and
  // some embedded/webview hosts suppress it outright, silently no-opping the delete).
  const [confirmId, setConfirmId] = useState(null)
  const [deleteError, setDeleteError] = useState(null)
  const confirmRef = useRef(null)

  useEffect(() => {
    if (!confirmId) return
    const onDocClick = (e) => { if (confirmRef.current && !confirmRef.current.contains(e.target)) setConfirmId(null) }
    const onKey = (e) => { if (e.key === 'Escape') setConfirmId(null) }
    document.addEventListener('mousedown', onDocClick)
    document.addEventListener('keydown', onKey)
    return () => { document.removeEventListener('mousedown', onDocClick); document.removeEventListener('keydown', onKey) }
  }, [confirmId])

  const remove = useCallback(async (d, e) => {
    e?.stopPropagation()
    if (deletingId) return
    setConfirmId(null)
    setDeleteError(null)
    setDeletingId(d.id)
    try {
      await savedDashboardsAPI.deleteDashboard(d.id)
      setDashboards((list) => list.filter((x) => x.id !== d.id))
    } catch (err) {
      setDeleteError(err?.response?.data?.message || err?.message || 'Couldn’t delete this dashboard.')
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
        <div className={styles.headerText}>
          <h1 className={styles.title}>Dashboards</h1>
          {dashboards.length > 0 && (
            <p className={styles.subtitle}>{dashboards.length} dashboard{dashboards.length === 1 ? '' : 's'} for this connection</p>
          )}
        </div>
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

      {!loading && dashboards.length === 0 ? (
        <div className={styles.emptyState}>
          <span className={styles.emptyIcon}><LayoutDashboard size={22} /></span>
          <h2 className={styles.emptyTitle}>No dashboards yet</h2>
          <p className={styles.emptySub}>Describe what you want to see and the DeepSQL agent will build it — grounded on your schema, verified against your data.</p>
          <button className={styles.primary} onClick={() => onOpen('new')}>
            <Plus size={15} /> New dashboard
          </button>
        </div>
      ) : (
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
            <div className={styles.deleteWrap} ref={confirmId === d.id ? confirmRef : undefined}>
              <button
                className={styles.cardDelete}
                onClick={(e) => { e.stopPropagation(); if (!deletingId) setConfirmId(d.id) }}
                disabled={deletingId === d.id}
                title="Delete dashboard"
                aria-label={`Delete ${d.name || 'dashboard'}`}
              >
                {deletingId === d.id ? <Loader2 size={14} className={styles.spin} /> : <Trash2 size={14} />}
              </button>
              {confirmId === d.id && (
                <div className={styles.confirmPopover} onClick={(e) => e.stopPropagation()}>
                  <p className={styles.confirmText}>Delete “{d.name || 'this dashboard'}”? This can’t be undone.</p>
                  <div className={styles.confirmActions}>
                    <button className={styles.confirmCancel} onClick={() => setConfirmId(null)}>Cancel</button>
                    <button className={styles.confirmDelete} onClick={(e) => remove(d, e)}>Delete</button>
                  </div>
                </div>
              )}
            </div>
            <div className={styles.thumb}><Thumb id={d.id} /></div>
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
      )}

      {!loading && dashboards.length > 0 && shown.length === 0 && (
        <div className={styles.emptyNote}>No {filter} dashboards — try a different filter.</div>
      )}

      {deleteError && (
        <div className={styles.errorToast}>
          <AlertTriangle size={14} />
          <span>{deleteError}</span>
          <button className={styles.errorDismiss} onClick={() => setDeleteError(null)} aria-label="Dismiss">×</button>
        </div>
      )}
    </div>
  )
}
