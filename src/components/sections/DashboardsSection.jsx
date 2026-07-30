import { useState, useCallback, useEffect } from 'react'
import { LayoutDashboard } from 'lucide-react'
import { useConnectionManager } from '@/lib/hooks/useConnectionManager'
import { useSetImmersive } from '@/lib/stores/useNavStore'
import DashboardsHome from './DashboardsHome'
import DashboardWorkspace from './DashboardWorkspace'
import emptyStyles from './SectionEmpty.module.css'

// Lovable-style flow: a gallery home (app chrome visible) and, on opening a
// dashboard, a focused full-bleed builder workspace (sidebar hidden via the
// nav store's immersive flag).
export default function DashboardsSection() {
  const { connectionId } = useConnectionManager()
  const setImmersive = useSetImmersive()
  const [open_, setOpen] = useState(null) // null = gallery; 'new' | dashboard object = workspace

  const open = useCallback((payload) => { setOpen(payload); setImmersive(true) }, [setImmersive])
  const close = useCallback(() => { setOpen(null); setImmersive(false) }, [setImmersive])

  // Drop back to the gallery (and restore chrome) if the connection changes.
  useEffect(() => { close() }, [connectionId, close])
  // Safety: never leave the app in immersive mode when this section unmounts.
  useEffect(() => () => setImmersive(false), [setImmersive])

  if (!connectionId) {
    return (
      <div className={emptyStyles.root}>
        <div className={emptyStyles.iconWrap}><LayoutDashboard size={26} color="#9ca3af" /></div>
        <h2 className={emptyStyles.title}>No connection selected</h2>
        <p className={emptyStyles.subtitle}>Select a database connection to view and build dashboards.</p>
      </div>
    )
  }

  return open_ ? (
    <DashboardWorkspace
      connectionId={connectionId}
      dashboard={open_ === 'new' ? null : open_}
      onClose={close}
    />
  ) : (
    <DashboardsHome connectionId={connectionId} onOpen={open} />
  )
}
