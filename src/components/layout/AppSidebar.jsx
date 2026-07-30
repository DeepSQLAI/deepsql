import { useState, useEffect, useRef } from 'react'
import { BookOpen, Brain, Code2, Database, Settings, PanelLeftClose, PanelLeftOpen, LogOut, User, ChevronDown, Check, Newspaper, Gauge, Activity, MessageSquare, LayoutDashboard } from 'lucide-react'
import { useActiveSection, useSetActiveSection } from '@/lib/stores/useNavStore'
import { useConnectionManager } from '@/lib/hooks/useConnectionManager'
import { AGENTS_ENABLED, canAccessHomeSection, getConnectionAccessBadge, getConnectionAccessLabel } from '@/lib/features'
import ManageConnectionsModal from '@/components/ManageConnectionsModal'
import SettingsModal from '@/components/SettingsModal'
import { useAuth } from '@/hooks/useAuth'
import styles from './AppSidebar.module.css'

const NAV_ITEMS = [
  { id: 'agent-chat', label: 'Agent', icon: MessageSquare },
  { id: 'dashboards', label: 'Dashboards', icon: LayoutDashboard },
  { id: 'digest',  label: 'Digest',  icon: Newspaper },
  ...(AGENTS_ENABLED ? [{ id: 'brain', label: 'Agents', icon: Brain }] : []),
  { id: 'company-knowledge', label: 'Brain', icon: Brain },
  { id: 'slow-queries', label: 'Slow Queries', icon: Gauge },
  { id: 'workload-analysis', label: 'Workload Analysis', icon: Activity },
  { id: 'editor',  label: 'Editor',  icon: Code2 },
  { id: 'docs',    label: 'Docs',    icon: BookOpen },
]

export default function AppSidebar() {
  const activeSection = useActiveSection()
  const setActiveSection = useSetActiveSection()
  const [showConnections, setShowConnections] = useState(false)
  const [showSettings, setShowSettings] = useState(false)
  const [collapsed, setCollapsed] = useState(false)
  const [showUserMenu, setShowUserMenu] = useState(false)
  const [showConnectionDropdown, setShowConnectionDropdown] = useState(false)
  const userMenuRef = useRef(null)
  const connectionDropdownRef = useRef(null)
  const { logout, role, username, isAdmin } = useAuth()
  const { connections, connectionId, selectedConnection, changeConnection, isLoading, refetch } = useConnectionManager()
  const visibleNavItems = NAV_ITEMS.filter(({ id }) => canAccessHomeSection(id, role, selectedConnection))

  const initials = username.slice(0, 2).toUpperCase()
  const connectionLabel =
    selectedConnection?.connectionName
      || (connections.length === 0 ? 'No connections' : 'Select connection')

  // Close user menu on outside click
  useEffect(() => {
    if (!showUserMenu) return
    const handler = (e) => {
      if (userMenuRef.current && !userMenuRef.current.contains(e.target)) {
        setShowUserMenu(false)
      }
    }
    document.addEventListener('mousedown', handler)
    return () => document.removeEventListener('mousedown', handler)
  }, [showUserMenu])

  // Close connection dropdown on outside click
  useEffect(() => {
    if (!showConnectionDropdown) return
    const handler = (e) => {
      if (connectionDropdownRef.current && !connectionDropdownRef.current.contains(e.target)) {
        setShowConnectionDropdown(false)
      }
    }
    document.addEventListener('mousedown', handler)
    return () => document.removeEventListener('mousedown', handler)
  }, [showConnectionDropdown])

  return (
    <>
      <aside className={`${styles.sidebar} ${collapsed ? styles.sidebarCollapsed : ''}`}>

        {/* Logo row */}
        <div className={styles.logo}>
          <div className={styles.logoLeft}>
            <div className={styles.logoIcon}>
              <Database size={14} color="#fff" />
            </div>
            <span className={`${styles.logoText} ${collapsed ? styles.logoTextHidden : ''}`}>
              DeepSQL
            </span>
          </div>
          <button
            className={styles.collapseBtn}
            onClick={() => setCollapsed((v) => !v)}
            title={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
          >
            {collapsed
              ? <PanelLeftOpen size={15} />
              : <PanelLeftClose size={15} />
            }
          </button>
        </div>

        {/* Primary nav */}
        <nav className={styles.nav}>
          {visibleNavItems.map(({ id, label, icon: Icon }) => {
            // Freeze these sections until the first DB connection is added.
            // Docs is reference content — available even without any connection.
            const requiresConnection = id !== 'agent' && id !== 'digest' && id !== 'docs'
            const isLocked = requiresConnection && connections.length === 0
            const lockTitle = isLocked
              ? 'Add a database connection to unlock'
              : collapsed ? label : undefined

            return (
              <button
                key={id}
                className={`${styles.navItem} ${activeSection === id ? styles.navItemActive : ''} ${isLocked ? styles.navItemDisabled ?? 'opacity-40 cursor-not-allowed' : ''}`}
                onClick={() => !isLocked && setActiveSection(id)}
                title={lockTitle}
                disabled={isLocked}
              >
                <Icon size={15} className={styles.navIcon} />
                <span className={`${styles.navLabel} ${collapsed ? styles.navLabelHidden : ''}`}>
                  {label}
                </span>
                {isLocked && !collapsed && (
                  <span className="ml-auto text-[9px] text-gray-400 font-medium uppercase tracking-wide">
                    locked
                  </span>
                )}
              </button>
            )
          })}
        </nav>

        {/* Bottom: connections + profile */}
        <div className={styles.divider} />
        <div className={styles.bottom}>
          <div className={styles.connectionSwitcher} ref={connectionDropdownRef}>
            <button
              className={styles.bottomItem}
              onClick={() => setShowConnectionDropdown((v) => !v)}
              title={collapsed ? connectionLabel : undefined}
              disabled={isLoading || connections.length === 0}
            >
              <Database size={15} className={styles.navIcon} />
              <span className={`${styles.navLabel} ${collapsed ? styles.navLabelHidden : ''}`}>
                {connectionLabel}
              </span>
              {!collapsed && selectedConnection && (
                <span className={styles.dbTypeBadge}>{getConnectionAccessBadge(selectedConnection) || selectedConnection.dbType}</span>
              )}
              {!collapsed && <ChevronDown size={14} className={styles.chevronIcon} />}
            </button>

            {showConnectionDropdown && !collapsed && connections.length > 0 && (
              <div className={styles.connectionDropdown}>
                <div className={styles.dropdownLabel}>Connections</div>
                {connections.map((conn) => (
                  <button
                    key={conn.id}
                    className={`${styles.dropdownItem} ${conn.id === connectionId ? styles.dropdownItemActive : ''}`}
                    onClick={() => {
                      changeConnection(conn.id)
                      setShowConnectionDropdown(false)
                    }}
                  >
                    <Database size={14} />
                    <span className={styles.dropdownItemName}>
                      {conn.connectionName}
                      {getConnectionAccessLabel(conn) ? ` · ${getConnectionAccessLabel(conn)}` : ''}
                    </span>
                    <span className={styles.dbTypeBadge}>{getConnectionAccessBadge(conn) || conn.dbType}</span>
                    {conn.id === connectionId && <Check size={13} className={styles.dropdownItemCheck} />}
                  </button>
                ))}
              </div>
            )}
          </div>

          <button
            className={styles.bottomItem}
            onClick={() => setShowConnections(true)}
            title={collapsed ? 'Connections' : undefined}
          >
            <Settings size={15} className={styles.navIcon} />
            <span className={`${styles.navLabel} ${collapsed ? styles.navLabelHidden : ''}`}>
              Connections
            </span>
          </button>
          <div className={styles.userMenuWrap} ref={userMenuRef}>
            <button
              className={styles.bottomItem}
              onClick={() => setShowUserMenu((v) => !v)}
              title={collapsed ? username : undefined}
            >
              <div className={styles.avatar}>{initials}</div>
              <span className={`${styles.navLabel} ${collapsed ? styles.navLabelHidden : ''}`}>
                {username}
              </span>
            </button>

            {showUserMenu && (
              <div className={styles.userDropdown}>
                <div className={styles.userDropdownInfo}>
                  <div className={styles.userDropdownAvatar}>
                    <User size={18} />
                  </div>
                  <span className={styles.userDropdownName}>{username}</span>
                </div>
                <div className={styles.userDropdownDivider} />
                <button
                  className={styles.userDropdownItem}
                  onClick={() => { setShowUserMenu(false); setShowSettings(true) }}
                >
                  <Settings size={14} />
                  <span>Settings</span>
                </button>
                <button
                  className={styles.userDropdownItem}
                  onClick={() => { setShowUserMenu(false); logout() }}
                >
                  <LogOut size={14} />
                  <span>Sign out</span>
                </button>
              </div>
            )}
          </div>
        </div>
      </aside>

      {showConnections && (
        <ManageConnectionsModal
          isOpen={showConnections}
          onClose={() => setShowConnections(false)}
          onConnectionSaved={async (newId) => {
            await refetch()
            if (newId) {
              changeConnection(newId)
            }
            setShowConnections(false)
          }}
          onConnectionDeleted={() => {}}
        />
      )}

      <SettingsModal
        isOpen={showSettings}
        onClose={() => setShowSettings(false)}
      />
    </>
  )
}
