import { useState, useEffect, useCallback } from 'react'
import { Newspaper, RefreshCw, Settings, X, Check, Clock, AlertCircle, Zap } from 'lucide-react'
import { slackDigestAPI } from '@/lib/api/client'
import { useConnectionManager } from '@/lib/hooks/useConnectionManager'
import styles from './DigestSection.module.css'

// ─────────────────────────────────────────────
// Mrkdwn inline renderer
// ─────────────────────────────────────────────
function renderInline(text) {
  const parts = []
  const re = /(\*[^*]+\*|_[^_]+_|`[^`]+`)/g
  let last = 0, m, key = 0
  while ((m = re.exec(text)) !== null) {
    if (m.index > last) parts.push(text.slice(last, m.index))
    const raw = m[0]
    if (raw.startsWith('*') && raw.endsWith('*'))
      parts.push(<strong key={key++}>{raw.slice(1, -1)}</strong>)
    else if (raw.startsWith('_') && raw.endsWith('_'))
      parts.push(<em key={key++}>{raw.slice(1, -1)}</em>)
    else if (raw.startsWith('`') && raw.endsWith('`'))
      parts.push(<code key={key++} className={styles.inlineCode}>{raw.slice(1, -1)}</code>)
    last = m.index + raw.length
  }
  if (last < text.length) parts.push(text.slice(last))
  return parts.length === 1 && typeof parts[0] === 'string' ? parts[0] : parts
}

function MrkdwnLine({ line }) {
  if (line.match(/^─+$/)) return <hr className={styles.hr} />
  return <p className={styles.mrkdwnLine}>{renderInline(line)}</p>
}

// ─────────────────────────────────────────────
// Parse digest content into named sections
// ─────────────────────────────────────────────
const SECTION_HEADER_RE = /^\*([🔦🐢🎯📈🔧][^*]+)\*$/

function parseDigestContent(content) {
  if (!content) return { header: [], sections: [] }
  const lines = content.split('\n')

  const header = []
  const sections = []
  let currentSection = null
  let headerDone = false

  for (const line of lines) {
    if (!headerDone) {
      if (SECTION_HEADER_RE.test(line.trim())) {
        headerDone = true
      } else {
        header.push(line)
        continue
      }
    }
    const m = SECTION_HEADER_RE.exec(line.trim())
    if (m) {
      currentSection = { title: m[1], lines: [] }
      sections.push(currentSection)
    } else if (currentSection) {
      currentSection.lines.push(line)
    }
  }

  // Drop trailing footer from last section
  if (sections.length > 0) {
    const last = sections[sections.length - 1]
    while (last.lines.length > 0 && last.lines[last.lines.length - 1].startsWith('_Powered by')) {
      last.lines.pop()
    }
  }

  return { header, sections }
}

// ─────────────────────────────────────────────
// Digest card
// ─────────────────────────────────────────────
function DigestCard({ digest }) {
  const { header, sections } = parseDigestContent(digest.content)

  // Extract title and subtitle from header
  const titleLine = header.find(l => l.startsWith('*🗄️'))?.replace(/\*/g, '') ?? 'DB Health Briefing'
  const subtitleLine = header.find(l => l.startsWith('_') && l.includes('·'))
  const subtitle = subtitleLine ? subtitleLine.replace(/^_/, '').replace(/_$/, '') : ''
  const datePart = subtitle.split('·')[0]?.trim() ?? ''
  const headlinePart = subtitle.split('·').slice(1).join('·').trim()

  const timeAgo = formatTimeAgo(digest.sentAt)
  const isFailed = digest.status === 'FAILED'

  return (
    <article className={`${styles.card} ${isFailed ? styles.cardFailed : ''}`}>
      {/* Card header */}
      <div className={styles.cardHeader}>
        <div className={styles.cardHeaderLeft}>
          <span className={styles.cardIcon}>🗄️</span>
          <div>
            <div className={styles.cardTitle}>{titleLine.replace('🗄️ ', '')}</div>
            {headlinePart && (
              <div className={styles.cardHeadline}>{headlinePart}</div>
            )}
          </div>
        </div>
        <div className={styles.cardHeaderRight}>
          <div className={styles.cardMeta}>
            {isFailed
              ? <span className={styles.badgeFailed}><AlertCircle size={10} /> Failed</span>
              : <span className={styles.badgeSent}><Check size={10} /> Sent</span>
            }
            <span className={styles.timeAgo}><Clock size={11} />{timeAgo}</span>
          </div>
        </div>
      </div>

      {/* Card body */}
      <div className={styles.cardBody}>
        {isFailed && digest.errorMessage && (
          <div className={styles.errorBanner}>
            <AlertCircle size={13} />
            <span>{digest.errorMessage}</span>
          </div>
        )}

        {sections.length === 0 && (
          <div className={styles.rawContent}>
            {digest.content.split('\n').map((line, i) => (
              <MrkdwnLine key={i} line={line} />
            ))}
          </div>
        )}

        {sections.map((section, i) => (
          <DigestSection key={i} section={section} />
        ))}

        <div className={styles.cardFooter}>
          {digest.channelId && <span>#{digest.channelId}</span>}
          {datePart && <span>{datePart}</span>}
        </div>
      </div>
    </article>
  )
}

function DigestSection({ section }) {
  const nonEmpty = section.lines.filter(l => l.trim())
  return (
    <div className={styles.section}>
      <div className={styles.sectionTitle}>{section.title}</div>
      <div className={styles.sectionBody}>
        {nonEmpty.map((line, i) => (
          <MrkdwnLine key={i} line={line} />
        ))}
      </div>
    </div>
  )
}

// ─────────────────────────────────────────────
// Schedule config panel
// ─────────────────────────────────────────────
const PRESETS = [
  { label: '8 AM daily', value: '0 0 8 * * *' },
  { label: '9 AM daily', value: '0 0 9 * * *' },
  { label: '10 AM daily', value: '0 0 10 * * *' },
  { label: '6 AM daily', value: '0 0 6 * * *' },
  { label: 'Noon daily', value: '0 0 12 * * *' },
  { label: 'Custom', value: 'custom' },
]

function SchedulePanel({ onClose }) {
  const [config, setConfig] = useState(null)
  const [cron, setCron] = useState('')
  const [preset, setPreset] = useState('custom')
  const [saving, setSaving] = useState(false)
  const [saved, setSaved] = useState(false)
  const [error, setError] = useState(null)

  useEffect(() => {
    slackDigestAPI.getConfig().then(cfg => {
      setConfig(cfg)
      setCron(cfg.cronExpression ?? '0 0 9 * * *')
      const match = PRESETS.find(p => p.value === cfg.cronExpression)
      setPreset(match ? match.value : 'custom')
    }).catch(() => setError('Could not load config'))
  }, [])

  const handlePreset = (value) => {
    setPreset(value)
    if (value !== 'custom') setCron(value)
  }

  const save = async () => {
    setSaving(true)
    setError(null)
    try {
      await slackDigestAPI.updateConfig({ cronExpression: cron })
      setSaved(true)
      setTimeout(() => setSaved(false), 2500)
    } catch {
      setError('Failed to save')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className={styles.panelOverlay} onClick={onClose}>
      <div className={styles.panel} onClick={e => e.stopPropagation()}>
        <div className={styles.panelHeader}>
          <span className={styles.panelTitle}>Digest Schedule</span>
          <button className={styles.iconBtn} onClick={onClose}><X size={15} /></button>
        </div>

        <div className={styles.panelBody}>
          <p className={styles.panelHint}>
            Choose when the daily digest is sent to Slack. Changes take effect on the next server restart.
          </p>

          <label className={styles.fieldLabel}>Quick presets</label>
          <div className={styles.presets}>
            {PRESETS.map(p => (
              <button
                key={p.value}
                className={`${styles.presetBtn} ${preset === p.value ? styles.presetBtnActive : ''}`}
                onClick={() => handlePreset(p.value)}
              >
                {p.label}
              </button>
            ))}
          </div>

          <label className={styles.fieldLabel}>Cron expression</label>
          <input
            className={styles.cronInput}
            value={cron}
            onChange={e => { setCron(e.target.value); setPreset('custom') }}
            placeholder="0 0 9 * * *"
            spellCheck={false}
          />
          <p className={styles.cronHint}>Format: seconds minutes hours day month weekday</p>

          {error && <p className={styles.errorText}>{error}</p>}

          <button
            className={styles.saveBtn}
            onClick={save}
            disabled={saving || !cron}
          >
            {saving ? 'Saving…' : saved ? '✓ Saved' : 'Save schedule'}
          </button>
        </div>
      </div>
    </div>
  )
}

// ─────────────────────────────────────────────
// Main section
// ─────────────────────────────────────────────
export default function DigestFeedSection() {
  const { connectionId, selectedConnection } = useConnectionManager()
  const [digests, setDigests] = useState([])
  const [loading, setLoading] = useState(false)
  const [triggering, setTriggering] = useState(false)
  const [triggerMsg, setTriggerMsg] = useState(null)
  const [showSettings, setShowSettings] = useState(false)
  const [error, setError] = useState(null)

  const load = useCallback(async () => {
    if (!connectionId) {
      setDigests([])
      setError(null)
      setLoading(false)
      return
    }
    setDigests([])
    setLoading(true)
    setError(null)
    try {
      const result = await slackDigestAPI.listDigests({ connectionId, page: 0, size: 7 })
      const items = result.content ?? []
      const seen = new Set()
      const latestUnique = items.filter((digest) => {
        if (!digest?.id || seen.has(digest.id)) return false
        seen.add(digest.id)
        return true
      }).slice(0, 7)
      setDigests(latestUnique)
    } catch {
      setError('Could not load digests')
    } finally {
      setLoading(false)
    }
  }, [connectionId])

  useEffect(() => { load() }, [load])

  const triggerNow = async () => {
    setTriggering(true)
    setTriggerMsg(null)
    try {
      const result = await slackDigestAPI.triggerDigest()
      const message = result?.message || (result?.triggered ? 'Digest sent!' : 'Digest could not be triggered.')
      setTriggerMsg(message)
      setTimeout(() => {
        setTriggerMsg(null)
        if (result?.triggered) {
          load()
        }
      }, 1500)
    } catch {
      setTriggerMsg('Failed to trigger')
      setTimeout(() => setTriggerMsg(null), 2500)
    } finally {
      setTriggering(false)
    }
  }

  return (
    <div className={styles.root}>
      {/* Top bar */}
      <div className={styles.topBar}>
        <div className={styles.topBarLeft}>
          <Newspaper size={17} className={styles.topBarIcon} />
          <span className={styles.topBarTitle}>DB Digest</span>
          {digests.length > 0 && (
            <span className={styles.countBadge}>{digests.length}</span>
          )}
          {selectedConnection?.connectionName && (
            <span className={styles.triggerMsg}>for {selectedConnection.connectionName}</span>
          )}
        </div>
        <div className={styles.topBarActions}>
          {triggerMsg && <span className={styles.triggerMsg}>{triggerMsg}</span>}
          <button
            className={styles.actionBtn}
            onClick={() => load()}
            disabled={loading}
            title="Refresh"
          >
            <RefreshCw size={14} className={loading ? styles.spinning : ''} />
          </button>
          <button
            className={`${styles.actionBtn} ${styles.actionBtnPrimary}`}
            onClick={triggerNow}
            disabled={triggering}
            title="Run digest now"
          >
            <Zap size={14} />
            <span>{triggering ? 'Sending…' : 'Run Now'}</span>
          </button>
          <button
            className={styles.actionBtn}
            onClick={() => setShowSettings(true)}
            title="Configure schedule"
          >
            <Settings size={14} />
          </button>
        </div>
      </div>

      {/* Feed */}
      <div className={styles.feed}>
        {error && (
          <div className={styles.emptyState}>
            <AlertCircle size={26} color="#9ca3af" />
            <p>{error}</p>
          </div>
        )}

        {!error && !loading && !connectionId && (
          <div className={styles.emptyState}>
            <Newspaper size={32} color="#d1d5db" />
            <h3>No connection selected</h3>
            <p>Select a connection to view its digest history.</p>
          </div>
        )}

        {!error && !loading && !!connectionId && digests.length === 0 && (
          <div className={styles.emptyState}>
            <Newspaper size={32} color="#d1d5db" />
            <h3>No digests yet</h3>
            <p>
              Run the first digest for {selectedConnection?.connectionName ?? 'this connection'} to see it here.
              Digests are also sent automatically on your configured schedule.
            </p>
            <button className={`${styles.actionBtn} ${styles.actionBtnPrimary}`} onClick={triggerNow} disabled={triggering}>
              <Zap size={14} />
              <span>{triggering ? 'Sending…' : 'Send first digest'}</span>
            </button>
          </div>
        )}

        {digests.map(digest => (
          <DigestCard key={digest.id} digest={digest} />
        ))}

        {loading && digests.length === 0 && (
          <div className={styles.loadingState}>
            <RefreshCw size={20} className={styles.spinning} color="#9ca3af" />
            <span>Loading digests…</span>
          </div>
        )}
      </div>

      {showSettings && <SchedulePanel onClose={() => setShowSettings(false)} />}
    </div>
  )
}

// ─────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────
function formatTimeAgo(dateStr) {
  if (!dateStr) return ''
  const date = new Date(dateStr)
  const now = new Date()
  const diff = Math.floor((now - date) / 1000)
  if (diff < 60) return 'just now'
  if (diff < 3600) return `${Math.floor(diff / 60)}m ago`
  if (diff < 86400) return `${Math.floor(diff / 3600)}h ago`
  return `${Math.floor(diff / 86400)}d ago`
}
