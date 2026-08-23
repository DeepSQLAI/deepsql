import { useCallback, useEffect, useRef, useState } from 'react'
import { ArrowLeftRight, Check, ChevronDown, X } from 'lucide-react'
import { useAuth } from '@/hooks/useAuth'
import { getDefaultHomeSection } from '@/lib/features'
import { adminAPI } from '@/lib/api/client'
import { useSetActiveSection } from '@/lib/stores/useNavStore'
import styles from './ProfileSwitch.module.css'

export default function ProfileSwitch() {
  const {
    isAdmin,
    impersonating,
    impersonatorUsername,
    username,
    role,
    startImpersonation,
    stopImpersonation,
  } = useAuth()
  const setActiveSection = useSetActiveSection()
  const [open, setOpen] = useState(false)
  const [candidates, setCandidates] = useState([])
  const [loading, setLoading] = useState(false)
  const [switching, setSwitching] = useState(false)
  const [error, setError] = useState(null)
  const rootRef = useRef(null)

  const canSwitch = isAdmin || impersonating

  const loadCandidates = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const status = await adminAPI.getImpersonationStatus()
      setCandidates(Array.isArray(status?.candidates) ? status.candidates : [])
    } catch (err) {
      setError(err?.response?.data?.message || err.message || 'Could not load users')
      setCandidates([])
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    if (!open) return undefined
    loadCandidates()
    const onPointerDown = (event) => {
      if (rootRef.current && !rootRef.current.contains(event.target)) {
        setOpen(false)
      }
    }
    document.addEventListener('mousedown', onPointerDown)
    return () => document.removeEventListener('mousedown', onPointerDown)
  }, [open, loadCandidates])

  if (!canSwitch) {
    return null
  }

  const handleSelect = async (userId) => {
    if (!userId || switching) return
    setSwitching(true)
    setError(null)
    try {
      const payload = await startImpersonation(userId)
      setOpen(false)
      setActiveSection(getDefaultHomeSection(payload?.role, null, payload?.permissions))
    } catch (err) {
      setError(err?.response?.data?.message || err.message || 'Could not switch profile')
    } finally {
      setSwitching(false)
    }
  }

  const handleStop = async () => {
    if (switching) return
    setSwitching(true)
    setError(null)
    try {
      const payload = await stopImpersonation()
      setOpen(false)
      setActiveSection(getDefaultHomeSection(payload?.role, null, payload?.permissions))
    } catch (err) {
      setError(err?.response?.data?.message || err.message || 'Could not exit profile')
    } finally {
      setSwitching(false)
    }
  }

  if (impersonating) {
    return (
      <div className={styles.banner} ref={rootRef} data-testid="profile-switch-banner">
        <div className={styles.bannerCopy}>
          <span className={styles.bannerLabel}>Viewing as</span>
          <strong className={styles.bannerName}>{username}</strong>
          <span className={styles.roleBadge}>{role}</span>
          {impersonatorUsername && (
            <span className={styles.bannerMeta}>signed in as {impersonatorUsername}</span>
          )}
        </div>
        <div className={styles.bannerActions}>
          {error && <span className={styles.error}>{error}</span>}
          <button
            type="button"
            className={styles.secondaryBtn}
            onClick={() => setOpen((value) => !value)}
            disabled={switching}
          >
            Switch user
            <ChevronDown size={14} />
          </button>
          <button
            type="button"
            className={styles.exitBtn}
            onClick={handleStop}
            disabled={switching}
            data-testid="profile-switch-exit"
          >
            <X size={14} />
            Exit
          </button>
        </div>
        {open && (
          <CandidateMenu
            candidates={candidates}
            loading={loading}
            switching={switching}
            activeUsername={username}
            onSelect={handleSelect}
          />
        )}
      </div>
    )
  }

  return (
    <div className={styles.switchWrap} ref={rootRef} data-testid="profile-switch">
        <button
          type="button"
          className={styles.trigger}
          onClick={() => setOpen((value) => !value)}
          disabled={switching}
          aria-label="View as another user"
          data-testid="profile-switch-trigger"
        >
          <ArrowLeftRight size={14} />
          <span>View as</span>
          <ChevronDown size={14} />
        </button>
      {open && (
        <CandidateMenu
          candidates={candidates}
          loading={loading}
          switching={switching}
          error={error}
          onSelect={handleSelect}
        />
      )}
    </div>
  )
}

function CandidateMenu({ candidates, loading, switching, error, activeUsername, onSelect }) {
  return (
    <div className={styles.menu} role="listbox" aria-label="Users to view as">
      <div className={styles.menuLabel}>Switch into a user</div>
      {loading && <div className={styles.menuEmpty}>Loading users…</div>}
      {!loading && error && <div className={styles.menuError}>{error}</div>}
      {!loading && !error && candidates.length === 0 && (
        <div className={styles.menuEmpty}>No sub-users available</div>
      )}
      {!loading && candidates.map((user) => (
        <button
          key={user.id}
          type="button"
          className={`${styles.menuItem} ${user.username === activeUsername ? styles.menuItemActive : ''}`}
          onClick={() => onSelect(user.id)}
          disabled={switching || user.username === activeUsername}
        >
          <span className={styles.menuItemMain}>
            <span className={styles.menuItemName}>{user.username}</span>
            <span className={styles.menuItemEmail}>{user.email}</span>
          </span>
          <span className={styles.roleBadge}>{user.role}</span>
          {user.username === activeUsername && <Check size={13} />}
        </button>
      ))}
    </div>
  )
}
