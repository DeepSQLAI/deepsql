import { useState, useEffect, useCallback, useMemo } from 'react'
import {
  X,
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
  { label: 'Use global', value: '' },
]

function cronPresetLabel(cronExpression) {
  if (!cronExpression) return 'Use global'
  const match = CRON_PRESETS.find((p) => p.value === cronExpression)
  return match ? match.label : 'Custom'
}

/**
 * Prefer API connectionName; else resolve from the connections list; else UUID.
 */
function resolveConnectionDisplayName(pref, connections, selectedConnection, connectionId) {
  if (pref?.connectionName && String(pref.connectionName).trim()) {
    return pref.connectionName
  }
  if (!pref?.connectionId) {
    return 'All connections'
  }
  const fromList = connections?.find((c) => c.id === pref.connectionId)
  if (fromList?.connectionName) {
    return fromList.connectionName
  }
  if (pref.connectionId === connectionId && selectedConnection?.connectionName) {
    return selectedConnection.connectionName
  }
  return pref.connectionId
}

export default function DigestPreferencesPanel({ onClose }) {
  const { connectionId, selectedConnection, connections } = useConnectionManager()
  const [preferences, setPreferences] = useState([])
  const [personaTags, setPersonaTags] = useState([])
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
      const [prefs, tags, statusRes] = await Promise.all([
        digestPreferencesAPI.getMyPreferences(),
        digestPreferencesAPI.getPersonaTags(),
        digestPreferencesAPI.getStatus(),
      ])
      setPreferences(prefs || [])
      setPersonaTags(tags || [])
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

  const showSuccess = (msg) => {
    setSuccessMsg(msg)
    setTimeout(() => setSuccessMsg(null), 2500)
  }

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

  const handleUpdateSchedule = async (pref, cronExpression) => {
    setSaving(true)
    try {
      const updated = await digestPreferencesAPI.updatePreference(pref.id, {
        cronExpression: cronExpression ?? '',
      })
      setPreferences((prev) =>
        prev.map((p) => (p.id === pref.id ? updated : p))
      )
      showSuccess('Schedule updated')
    } catch {
      setError('Failed to update schedule')
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

  const currentConnectionPref = preferences.find(
    (p) => p.connectionId === connectionId
  )

  const connectionNameById = useMemo(() => {
    const map = {}
    for (const c of connections || []) {
      if (c?.id) map[c.id] = c.connectionName
    }
    return map
  }, [connections])

  return (
    <div className={styles.panelOverlay} onClick={onClose}>
      <div className={styles.panel} onClick={(e) => e.stopPropagation()}>
        {/* Header */}
        <div className={styles.panelHeader}>
          <div className={styles.headerTitle}>
            <Settings2 size={16} />
            <span>Digest Preferences</span>
          </div>
          <button className={styles.iconBtn} onClick={onClose} type="button">
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
              <button type="button" onClick={() => setError(null)}>×</button>
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
              <Settings2 size={28} color="#d1d5db" />
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
                  type="button"
                >
                  <Sparkles size={14} />
                  {saving ? 'Setting up...' : 'Set up for all my connections'}
                </button>
                <button
                  className={styles.createBtn}
                  onClick={() => setShowCreate(true)}
                  disabled={!connectionId}
                  type="button"
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
                  type="button"
                >
                  <Plus size={14} />
                </button>
              </div>

              {preferences.map((pref) => (
                <PreferenceCard
                  key={pref.id}
                  pref={pref}
                  displayName={resolveConnectionDisplayName(
                    {
                      ...pref,
                      connectionName:
                        pref.connectionName || connectionNameById[pref.connectionId],
                    },
                    connections,
                    selectedConnection,
                    connectionId
                  )}
                  personaTags={personaTags}
                  connectionId={connectionId}
                  onToggle={() => handleToggleEnabled(pref)}
                  onUpdatePersona={(tag) => handleUpdatePersona(pref, tag)}
                  onUpdateSchedule={(cron) => handleUpdateSchedule(pref, cron)}
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
                For:{' '}
                {selectedConnection?.connectionName ||
                  connectionId ||
                  'Select a connection'}
              </p>

              <label className={styles.fieldLabel}>Persona</label>
              <select
                className={styles.select}
                value={newPersona}
                onChange={(e) => setNewPersona(e.target.value)}
              >
                <option value="">Role-based (default)</option>
                {personaTags.map((tag) => (
                  <option key={tag.value} value={tag.value}>
                    {tag.label}
                  </option>
                ))}
              </select>

              <label className={styles.fieldLabel}>Schedule</label>
              <div className={styles.cronPresets}>
                {CRON_PRESETS.map((p) => (
                  <button
                    key={p.label}
                    type="button"
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
                  type="button"
                >
                  Cancel
                </button>
                <button
                  className={styles.saveBtn}
                  onClick={handleCreate}
                  disabled={saving || !connectionId}
                  type="button"
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
  displayName,
  personaTags,
  connectionId,
  onToggle,
  onUpdatePersona,
  onUpdateSchedule,
  onDelete,
}) {
  const isCurrentConnection = pref.connectionId === connectionId
  const scheduleValue = pref.cronExpression || ''
  const knownSchedule = CRON_PRESETS.some((p) => p.value === scheduleValue)

  return (
    <div
      className={`${styles.prefCard} ${
        isCurrentConnection ? styles.prefCardCurrent : ''
      } ${!pref.enabled ? styles.prefCardDisabled : ''}`}
    >
      <div className={styles.prefCardMain}>
        <div className={styles.prefCardHeader}>
          <span className={styles.prefConnName} title={displayName}>
            {displayName}
          </span>
          {isCurrentConnection && (
            <span className={styles.currentBadge}>Current</span>
          )}
        </div>

        <div className={styles.prefCardControls}>
          <label className={styles.controlLabel}>
            <span>Persona</span>
            <select
              className={styles.controlSelect}
              value={pref.personaTag || ''}
              onChange={(e) => onUpdatePersona(e.target.value)}
              title="Persona"
            >
              <option value="">Role-based</option>
              {personaTags.map((tag) => (
                <option key={tag.value} value={tag.value}>
                  {tag.label}
                </option>
              ))}
            </select>
          </label>

          <label className={styles.controlLabel}>
            <span>Schedule</span>
            <select
              className={styles.controlSelect}
              value={knownSchedule ? scheduleValue : '__custom__'}
              onChange={(e) => {
                const v = e.target.value
                if (v === '__custom__') return
                onUpdateSchedule(v)
              }}
              title="Schedule"
            >
              {CRON_PRESETS.map((p) => (
                <option key={p.label} value={p.value}>
                  {p.label}
                </option>
              ))}
              {!knownSchedule && (
                <option value="__custom__">
                  Custom ({cronPresetLabel(pref.cronExpression)})
                </option>
              )}
            </select>
          </label>
        </div>
      </div>

      <div className={styles.prefCardActions}>
        <button
          type="button"
          className={`${styles.toggleBtn} ${
            pref.enabled ? styles.toggleBtnOn : ''
          }`}
          onClick={onToggle}
          title={pref.enabled ? 'Pause digest' : 'Enable digest'}
        >
          {pref.enabled ? 'On' : 'Off'}
        </button>

        <button
          type="button"
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
