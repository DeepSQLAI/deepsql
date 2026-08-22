import { useState } from 'react'
import { BookmarkPlus, X } from 'lucide-react'
import { brainAPI } from '@/lib/api/client'
import styles from './AgentChatPanel.module.css'

/**
 * Non-blocking suggestion chips under an Agent answer. Chat stays usable;
 * clicking a bubble only opens an excerpt of the shared-brain rule that
 * would be created (or merged into an existing one).
 */
export default function AgentRecommendationBubbles({ connectionId, proposal, onDismiss, onAccepted }) {
  const [open, setOpen] = useState(false)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState(null)

  if (!proposal || proposal.status === 'dismissed' || proposal.status === 'saved') {
    return null
  }

  const accept = async (event) => {
    event.preventDefault()
    event.stopPropagation()
    if (saving) return
    setSaving(true)
    setError(null)
    try {
      await brainAPI.acceptNote({
        connectionId,
        scopeType: proposal.scopeType,
        tableName: proposal.tableName,
        columnName: proposal.columnName || undefined,
        noteText: proposal.proposedNoteText,
      })
      onAccepted?.()
    } catch (e) {
      setError(e?.response?.data?.message || e?.message || 'Could not save this definition')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className={styles.recWrap}>
      <div className={styles.recBubbles}>
        <button
          type="button"
          className={`${styles.recBubble} ${open ? styles.recBubbleOpen : ''}`}
          onClick={() => setOpen((value) => !value)}
        >
          <BookmarkPlus size={13} />
          {proposal.bubbleLabel || 'Save definition'}
        </button>
      </div>
      {open && (
        <div className={styles.recCard}>
          <div className={styles.recCardHead}>
            <span className={styles.recCardKicker}>
              {proposal.action === 'MERGE' ? 'Merge into existing context' : 'Shared brain note'}
            </span>
            <button type="button" className={styles.recDismiss} onClick={onDismiss} title="Dismiss">
              <X size={14} />
            </button>
          </div>
          <p className={styles.recTarget}>
            {proposal.columnName
              ? `${proposal.tableName}.${proposal.columnName}`
              : proposal.tableName}
          </p>
          {proposal.overlapReason && (
            <p className={styles.recOverlap}>{proposal.overlapReason}</p>
          )}
          <blockquote className={styles.recExcerpt}>{proposal.excerpt || proposal.proposedNoteText}</blockquote>
          <div className={styles.recActions}>
            <button type="button" className={styles.recAccept} onClick={accept} disabled={saving}>
              {saving ? 'Saving…' : proposal.action === 'MERGE' ? 'Merge and save' : 'Save to brain'}
            </button>
            <button type="button" className={styles.recCancel} onClick={() => setOpen(false)}>
              Not now
            </button>
          </div>
          {error && <p className={styles.recError}>{error}</p>}
        </div>
      )}
    </div>
  )
}
