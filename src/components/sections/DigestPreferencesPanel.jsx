import { useState, useEffect, useCallback } from 'react'
import {
  X,
  User,
  Bell,
  Clock,
  Check,
  AlertCircle,
  RefreshCw,
  Sparkles,
  Trash2,
  Plus,
  Settings2,
} from 'lucide-react'
import { digestPreferencesAPI } from '@/lib/api/client'
import { useConnectionManager } from '@/lib/hooks/useConnectionManager'
import styles from './DigestPreferencesPanel.module.css'

const CRON_PRESETS = [
  { label: '8 AM daily', value: '0 0 8 * * *' },
  { label: '9 AM daily', value: '0 0 9 * * *' },
  { label: '10 AM daily', value: '0 0 10 * * *' },
  { label: 'Noon daily', value: '0 0 12 * * *' },
  { label: 'Use global', value: null },
]

export default function DigestPreferencesPanel({ onClose }) {
  const { connectionId, selectedConnection } = useConnectionManager()
  const [preferences, setPreferences] = useState([])
  const [personaTags, setPersonaTags] = useState([])
  const [deliveryMethods, setDeliveryMethods] = useState([])
  const [status, setStatus] = useState(null)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState(null)
  const [successMsg, setSuccessMsg] = useState(null)
  const [showCreate, setShowCreate] = useState(false)

  // Create form state
  const [newPersona, setNewPersona] = useState('')
  const [newCron, setNewCron] = useState('')
  const [newCronPreset, setNewCronPreset] = useState('Use global')

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const [prefs, tags, methods, statusRes] = await Promise.all([
        digestPreferencesAPI.getMyPreferences(),
        digestPreferencesAPI.getPersonaTags(),
        digestPreferencesAPI.getDeliveryMethods(),
        digestPreferencesAPI.getStatus(),
      ])
      setPreferences(prefs || [])
      setPersonaTags(tags || [])
      setDeliveryMethods(methods || [])
      setStatus(statusRes)
    } catch (err) {
      setError(err?.response?.data?.message || 'Failed to load preferences')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    load()
  }, [load])

  const handleToggleEnabled = async (pref) => {
    try {
      const updated = await digestPreferencesAPI.setEnabled(pref.id, !pref.enabled)
      setPreferences((prev) =>
        prev.map((p) => (p.id === pref.id ? updated : p))
      )
      showSuccess(pref.enabled ? 'Digest paused' : 'Digest enabled')
    } catch {
      setError('Failed to update preference')
    }
  }

  const handleUpdatePersona = async (pref, personaTag) => {
    setSaving(true)
    try {
      const updated = await digestPreferencesAPI.updatePreference(pref.id, {
        personaTag: personaTag || null,
      })
      setPreferences((prev) =>
        prev.map((p) => (p.id === pref.id ? updated : p))
      )
      showSuccess('Persona updated')
    } catch {
      setError('Failed to update persona')
    } finally {
      setSaving(false)
    }
  }

  const handleDelete = async (pref) => {
    if (!window.confirm('Delete this digest preference? You can recreate it later.')) {
      return
    }
    try {
      await digestPreferencesAPI.deletePreference(pref.id)
      setPreferences((prev) => prev.filter((p) => p.id !== pref.id))
      showSuccess('Preference deleted')
    } catch {
      setError('Failed to delete preference')
    }
  }

  const handleCreate = async () => {
    if (!connectionId) {
      setError('Please select a connection first')
      return
    }
    setSaving(true)
    setError(null)
    try {
      const created = await digestPreferencesAPI.createPreference({
        connectionId,
        deliveryMethod: 'SLACK_DM',
        personaTag: newPersona || null,
        cronExpression: newCron || null,
      })
      setPreferences((prev) => [...prev, created])
      setShowCreate(false)
      setNewPersona('')
      setNewCron('')
      setNewCronPreset('Use global')
      showSuccess('Digest preference created')
    } catch (err) {
      setError(err?.response?.data?.message || 'Failed to create preference')
    } finally {
      setSaving(false)
    }
  }

  const handleSeedForMe = async () => {
    setSaving(true)
    setError(null)
    try {
      const result = await digestPreferencesAPI.seedForMe()
      if (result.preferencesCreated > 0) {
        showSuccess(`Created ${result.preferencesCreated} digest preference(s)`)
        await load()
      } else {
        showSuccess('No new preferences needed')
      }
    } catch (err) {
      setError(err?.response?.data?.message || 'Failed to seed preferences')
    } finally {
      setSaving(false)
    }
  }

  const handleCronPreset = (preset) => {
    setNewCronPreset(preset.label)
    setNewCron(preset.value || '')
  }

  const showSuccess = (msg) => {
    setSuccessMsg(msg)
    setTimeout(() => setSuccessMsg(null), 2500)
  }

  const currentConnectionPref = preferences.find(
    (p) => p.connectionId === connectionId
  )

  return (
    <div className={styles.panelOverlay} onClick={onClose}>
      <div className={styles.panel} onClick={(e) => e.stopPropagation()}>
        {/* Header */}
        <div className={styles.panelHeader}>
          <div className={styles.headerTitle}>
            <Bell size={16} />
            <span>Digest Preferences</span>
          </div>
          <button className={styles.iconBtn} onClick={onClose}>
            <X size={15} />
          </button>
        </div>

        {/* Body */}
        <div className={styles.panelBody}>
          {/* Status banner */}
          {status && (
            <div className={styles.statusBanner}>
              <Settings2 size={14} />
              <span>
                {status.perUserMode
                  ? `Personalized mode: ${status.enabledPreferences} preference(s) for ${status.distinctUsers} user(s)`
                  : 'Legacy mode: global digest only'}
              </span>
            </div>
          )}

          {loading && (
            <div className={styles.loadingState}>
              <RefreshCw size={18} className={styles.spinning} />
              <span>Loading preferences...</span>
            </div>
          )}

          {error && (
            <div className={styles.errorBanner}>
              <AlertCircle size={14} />
              <span>{error}</span>
              <button onClick={() => setError(null)}>×</button>
            </div>
          )}

          {successMsg && (
            <div className={styles.successBanner}>
              <Check size={14} />
              <span>{successMsg}</span>
            </div>
          )}

          {!loading && preferences.length === 0 && (
            <div className={styles.emptyState}>
              <Bell size={28} color="#d1d5db" />
              <h4>No digest preferences yet</h4>
              <p>
                Set up personalized digests to receive database insights via Slack
                DM, tailored to your role.
              </p>
              <div className={styles.emptyActions}>
                <button
                  className={styles.seedBtn}
                  onClick={handleSeedForMe}
                  disabled={saving}
                >
                  <Sparkles size={14} />
                  {saving ? 'Setting up...' : 'Set up for all my connections'}
                </button>
                <button
                  className={styles.createBtn}
                  onClick={() => setShowCreate(true)}
                  disabled={!connectionId}
                >
                  <Plus size={14} />
                  Create for current connection
                </button>
              </div>
            </div>
          )}

          {/* Preference list */}
          {!loading && preferences.length > 0 && (
            <div className={styles.prefList}>
              <div className={styles.prefListHeader}>
                <span>Your digest subscriptions</span>
                <button
                  className={styles.addBtn}
                  onClick={() => setShowCreate(true)}
                  disabled={!connectionId || currentConnectionPref}
                  title={
                    currentConnectionPref
                      ? 'Already configured for this connection'
                      : 'Add preference for current connection'
                  }
                >
                  <Plus size={14} />
                </button>
              </div>

              {preferences.map((pref) => (
                <PreferenceCard
                  key={pref.id}
                  pref={pref}
                  personaTags={personaTags}
                  selectedConnection={selectedConnection}
                  connectionId={connectionId}
                  onToggle={() => handleToggleEnabled(pref)}
                  onUpdatePersona={(tag) => handleUpdatePersona(pref, tag)}
                  onDelete={() => handleDelete(pref)}
                />
              ))}
            </div>
          )}

          {/* Create form */}
          {showCreate && (
            <div className={styles.createForm}>
              <h4>New digest preference</h4>
              <p className={styles.createHint}>
                For: {selectedConnection?.connectionName || connectionId || 'Select a connection'}
              </p>

              <label className={styles.fieldLabel}>Persona (optional)</label>
              <select
                className={styles.select}
                value={newPersona}
                onChange={(e) => setNewPersona(e.target.value)}
              >
                <option value="">Use my role only</option>
                {personaTags.map((tag) => (
                  <option key={tag.value} value={tag.value}>
                    {tag.label} — {tag.description}
                  </option>
                ))}
              </select>

              <label className={styles.fieldLabel}>Schedule</label>
              <div className={styles.cronPresets}>
                {CRON_PRESETS.map((p) => (
                  <button
                    key={p.label}
                    className={`${styles.presetBtn} ${
                      newCronPreset === p.label ? styles.presetBtnActive : ''
                    }`}
                    onClick={() => handleCronPreset(p)}
                  >
                    {p.label}
                  </button>
                ))}
              </div>

              {newCronPreset !== 'Use global' && (
                <>
                  <input
                    className={styles.cronInput}
                    value={newCron}
                    onChange={(e) => {
                      setNewCron(e.target.value)
                      setNewCronPreset('Custom')
                    }}
                    placeholder="0 0 9 * * *"
                    spellCheck={false}
                  />
                  <p className={styles.cronHint}>
                    Format: seconds minutes hours day month weekday
                  </p>
                </>
              )}

              <div className={styles.formActions}>
                <button
                  className={styles.cancelBtn}
                  onClick={() => setShowCreate(false)}
                >
                  Cancel
                </button>
                <button
                  className={styles.saveBtn}
                  onClick={handleCreate}
                  disabled={saving || !connectionId}
                >
                  {saving ? 'Creating...' : 'Create preference'}
                </button>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}

function PreferenceCard({
  pref,
  personaTags,
  selectedConnection,
  connectionId,
  onToggle,
  onUpdatePersona,
  onDelete,
}) {
  const isCurrentConnection = pref.connectionId === connectionId
  const connName =
    isCurrentConnection && selectedConnection
      ? selectedConnection.connectionName
      : pref.connectionId || 'All connections'

  const personaLabel =
    personaTags.find((t) => t.value === pref.personaTag)?.label || 'Role-based'

  const deliveryLabel =
    pref.deliveryMethod === 'SLACK_DM'
      ? 'Slack DM'
      : pref.deliveryMethod === 'SLACK_CHANNEL'
      ? 'Channel'
      : pref.deliveryMethod || 'Slack'

  return (
    <div
      className={`${styles.prefCard} ${
        isCurrentConnection ? styles.prefCardCurrent : ''
      } ${!pref.enabled ? styles.prefCardDisabled : ''}`}
    >
      <div className={styles.prefCardMain}>
        <div className={styles.prefCardHeader}>
          <span className={styles.prefConnName}>{connName}</span>
          {isCurrentConnection && (
            <span className={styles.currentBadge}>Current</span>
          )}
          <span
            className={`${styles.statusDot} ${
              pref.enabled ? styles.statusDotActive : ''
            }`}
          />
        </div>

        <div className={styles.prefCardMeta}>
          <span className={styles.metaItem}>
            <User size={12} />
            {personaLabel}
          </span>
          <span className={styles.metaItem}>
            <Bell size={12} />
            {deliveryLabel}
          </span>
          {pref.cronExpression && (
            <span className={styles.metaItem}>
              <Clock size={12} />
              Custom schedule
            </span>
          )}
        </div>
      </div>

      <div className={styles.prefCardActions}>
        <select
          className={styles.personaSelect}
          value={pref.personaTag || ''}
          onChange={(e) => onUpdatePersona(e.target.value)}
          title="Change persona"
        >
          <option value="">Role-based</option>
          {personaTags.map((tag) => (
            <option key={tag.value} value={tag.value}>
              {tag.label}
            </option>
          ))}
        </select>

        <button
          className={`${styles.toggleBtn} ${
            pref.enabled ? styles.toggleBtnOn : ''
          }`}
          onClick={onToggle}
          title={pref.enabled ? 'Pause digest' : 'Enable digest'}
        >
          {pref.enabled ? 'On' : 'Off'}
        </button>

        <button
          className={styles.deleteBtn}
          onClick={onDelete}
          title="Delete preference"
        >
          <Trash2 size={14} />
        </button>
      </div>
    </div>
  )
}
