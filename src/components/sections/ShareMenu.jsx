import { useState, useRef, useEffect } from 'react'
import { Share2, Copy, Check, Loader2, Globe, Users, Lock, Tv } from 'lucide-react'
import { savedDashboardsAPI } from '@/lib/api/client'
import styles from './ShareMenu.module.css'

// Share popover: an internal deep link (login + access required) always, plus an
// opt-in, revocable public link (no login, read-only) toggled per dashboard, with
// an optional password gate on the public link.
export default function ShareMenu({ savedId, initialPublic, initialToken, initialPasswordSet, onPublicChange }) {
  const [open, setOpen] = useState(false)
  const [pub, setPub] = useState(!!initialPublic)
  const [token, setToken] = useState(initialToken || null)
  const [busy, setBusy] = useState(false)
  const [copied, setCopied] = useState('')
  const [hasPassword, setHasPassword] = useState(!!initialPasswordSet)
  const [pwInput, setPwInput] = useState('')
  const [pwEditing, setPwEditing] = useState(false)
  const [pwBusy, setPwBusy] = useState(false)
  const [shareError, setShareError] = useState('')
  const ref = useRef(null)

  useEffect(() => {
    setPub(!!initialPublic); setToken(initialToken || null); setHasPassword(!!initialPasswordSet)
  }, [initialPublic, initialToken, initialPasswordSet])

  useEffect(() => {
    if (!open) return undefined
    const onDoc = (e) => { if (ref.current && !ref.current.contains(e.target)) setOpen(false) }
    document.addEventListener('mousedown', onDoc)
    return () => document.removeEventListener('mousedown', onDoc)
  }, [open])

  const origin = window.location.origin
  const internalUrl = `${origin}/dashboard-view/${savedId}`
  const publicUrl = token ? `${origin}/share/dashboard/${token}` : ''
  const kioskUrl = token ? `${origin}/share/dashboard/${token}?kiosk=1&refresh=60` : ''

  const copy = async (url, key) => {
    try { await navigator.clipboard.writeText(url); setCopied(key); setTimeout(() => setCopied(''), 1500) } catch { /* clipboard blocked */ }
  }

  const togglePublic = async () => {
    if (busy || !savedId) return
    setBusy(true)
    setShareError('')
    try {
      if (!pub) {
        const res = await savedDashboardsAPI.enableShare(savedId)
        setToken(res.shareToken); setPub(true); onPublicChange?.(true)
      } else {
        await savedDashboardsAPI.disableShare(savedId); setPub(false); onPublicChange?.(false)
      }
    } catch (err) {
      const message = err?.response?.data?.message || err?.message || 'Failed to update sharing'
      setShareError(message)
    } finally { setBusy(false) }
  }

  const savePassword = async () => {
    if (pwBusy || !savedId) return
    setPwBusy(true)
    try {
      const res = await savedDashboardsAPI.setSharePassword(savedId, pwInput)
      setHasPassword(!!res.sharePasswordSet)
      setPwInput(''); setPwEditing(false)
    } catch { /* keep editing on failure */ } finally { setPwBusy(false) }
  }

  const removePassword = async () => {
    if (pwBusy || !savedId) return
    setPwBusy(true)
    try {
      await savedDashboardsAPI.setSharePassword(savedId, '')
      setHasPassword(false); setPwInput(''); setPwEditing(false)
    } catch { /* ignore */ } finally { setPwBusy(false) }
  }

  return (
    <div className={styles.wrap} ref={ref}>
      <button className={styles.trigger} disabled={!savedId} onClick={() => setOpen((o) => !o)} title="Share dashboard">
        <Share2 size={14} /> Share
      </button>

      {open && (
        <div className={styles.pop}>
          <div className={styles.section}>
            <div className={styles.rowHead}><Users size={13} /> Internal link</div>
            <div className={styles.hint}>Anyone logged in with access to this connection can open it.</div>
            <div className={styles.linkRow}>
              <input readOnly value={internalUrl} className={styles.input} onFocus={(e) => e.target.select()} />
              <button className={styles.copy} onClick={() => copy(internalUrl, 'int')} title="Copy link">
                {copied === 'int' ? <Check size={14} /> : <Copy size={14} />}
              </button>
            </div>
          </div>

          <div className={styles.divider} />

          <div className={styles.section}>
            <div className={styles.rowHead}>
              <Globe size={13} /> Public link
              <button
                className={`${styles.toggle} ${pub ? styles.on : ''}`}
                onClick={togglePublic}
                disabled={busy}
                role="switch"
                aria-checked={pub}
                title={pub ? 'Unpublish' : 'Publish to the web'}
              >
                <span className={styles.knob}>{busy && <Loader2 size={10} className={styles.spin} />}</span>
              </button>
            </div>
            {shareError ? <div className={styles.error}>{shareError}</div> : null}
            {pub ? (
              <>
                <div className={styles.hint}>Anyone with this link can view — read-only, no login. Turn off to revoke.</div>
                <div className={styles.linkRow}>
                  <input readOnly value={publicUrl} className={styles.input} onFocus={(e) => e.target.select()} />
                  <button className={styles.copy} onClick={() => copy(publicUrl, 'pub')} title="Copy link">
                    {copied === 'pub' ? <Check size={14} /> : <Copy size={14} />}
                  </button>
                </div>

                <div className={styles.pwRow}>
                  <Lock size={12} className={styles.pwIcon} />
                  {hasPassword && !pwEditing ? (
                    <>
                      <span className={styles.pwLabel}>Password protected</span>
                      <button className={styles.pwLink} onClick={() => setPwEditing(true)} disabled={pwBusy}>Change</button>
                      <button className={styles.pwLink} onClick={removePassword} disabled={pwBusy}>Remove</button>
                    </>
                  ) : pwEditing || !hasPassword ? (
                    <>
                      <input
                        type="password"
                        className={styles.pwInput}
                        placeholder={hasPassword ? 'New password' : 'Add a password (optional)'}
                        value={pwInput}
                        onChange={(e) => setPwInput(e.target.value)}
                        onKeyDown={(e) => { if (e.key === 'Enter' && pwInput) savePassword() }}
                      />
                      <button className={styles.pwSet} onClick={savePassword} disabled={pwBusy || !pwInput}>
                        {pwBusy ? <Loader2 size={12} className={styles.spin} /> : 'Set'}
                      </button>
                      {pwEditing && <button className={styles.pwLink} onClick={() => { setPwEditing(false); setPwInput('') }}>Cancel</button>}
                    </>
                  ) : null}
                </div>

                {!hasPassword && (
                  <div className={styles.kioskRow}>
                    <div className={styles.rowHead}><Tv size={13} /> TV / kiosk link</div>
                    <div className={styles.hint}>Chrome-less, auto-refreshing every 60s — for a wall display.</div>
                    <div className={styles.linkRow}>
                      <input readOnly value={kioskUrl} className={styles.input} onFocus={(e) => e.target.select()} />
                      <button className={styles.copy} onClick={() => copy(kioskUrl, 'kiosk')} title="Copy link">
                        {copied === 'kiosk' ? <Check size={14} /> : <Copy size={14} />}
                      </button>
                    </div>
                  </div>
                )}
              </>
            ) : (
              <div className={styles.hint}>Publish to the web to get a link anyone can open without logging in.</div>
            )}
          </div>
        </div>
      )}
    </div>
  )
}
