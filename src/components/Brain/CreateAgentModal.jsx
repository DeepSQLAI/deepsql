import { useState, useEffect } from 'react'
import { X } from 'lucide-react'
import { generateId } from './brainAgentStore'
import styles from './CreateAgentModal.module.css'

const SCHEDULES = [
  { value: 'manual',  label: 'Manual only' },
  { value: 'hourly',  label: 'Every hour' },
  { value: 'daily',   label: 'Daily' },
  { value: 'weekly',  label: 'Weekly (Monday)' },
  { value: 'monthly', label: 'Monthly (1st)' },
]

const EXAMPLE_TASKS = [
  'Find all new customers added in the last 30 days and analyze their product engagement metrics.',
  'Check all subscription invoices raised and detect accounts with unpaid invoices older than 30 days.',
  'Mine user activity logs, identify unusual access patterns or anomalies compared to historical baselines.',
  'Summarize the top 10 slowest queries from the last 24 hours and suggest index optimizations.',
  'Identify tables with the highest row growth rate over the past 7 days and flag potential capacity issues.',
]

export default function CreateAgentModal({ connections, existingAgent, initialValues, onSave, onClose }) {
  const seed = existingAgent || initialValues || {}
  const [name, setName]             = useState(seed.name || '')
  const [description, setDesc]      = useState(seed.description || seed.desc || '')
  const [task, setTask]             = useState(seed.task || '')
  const [connectionId, setConn]     = useState(existingAgent?.connectionId || seed.connectionId || connections[0]?.id || '')
  const [schedule, setSchedule]     = useState(seed.schedule || 'manual')
  const [scheduleTime, setTime]     = useState(existingAgent?.scheduleTime || '09:00')

  // Pre-fill example task on click
  const fillExample = (t) => setTask(t)

  const canSave = name.trim() && task.trim() && connectionId

  const handleSave = () => {
    if (!canSave) return
    onSave({
      id: existingAgent?.id || generateId(),
      name: name.trim(),
      description: description.trim(),
      task: task.trim(),
      connectionId,
      schedule,
      scheduleTime: (schedule !== 'manual' && schedule !== 'hourly') ? scheduleTime : null,
      enabled: existingAgent?.enabled ?? true,
    })
  }

  // Close on Escape
  useEffect(() => {
    const handler = (e) => { if (e.key === 'Escape') onClose() }
    document.addEventListener('keydown', handler)
    return () => document.removeEventListener('keydown', handler)
  }, [onClose])

  return (
    <div className={styles.overlay} onClick={(e) => e.target === e.currentTarget && onClose()}>
      <div className={styles.modal}>
        <div className={styles.header}>
          <h2 className={styles.title}>
            {existingAgent ? 'Edit Agent' : 'New Agent'}
          </h2>
          <button className={styles.closeBtn} onClick={onClose}>
            <X size={16} />
          </button>
        </div>

        <div className={styles.body}>
          {/* Name */}
          <div className={styles.field}>
            <label className={styles.label}>
              Agent name <span className={styles.required}>*</span>
            </label>
            <input
              className={styles.input}
              placeholder="e.g. BI Agent, Accounts Agent, Logs Agent"
              value={name}
              onChange={(e) => setName(e.target.value)}
              autoFocus
            />
          </div>

          {/* Description */}
          <div className={styles.field}>
            <label className={styles.label}>Description</label>
            <input
              className={styles.input}
              placeholder="Short description of what this agent does"
              value={description}
              onChange={(e) => setDesc(e.target.value)}
            />
          </div>

          {/* Task */}
          <div className={styles.field}>
            <label className={styles.label}>
              Task instructions <span className={styles.required}>*</span>
            </label>
            <textarea
              className={styles.textarea}
              placeholder="Describe exactly what this agent should do. Be specific about the data to look at, time ranges, metrics to compute, and what kind of insights to surface."
              value={task}
              onChange={(e) => setTask(e.target.value)}
              rows={5}
            />
            <p className={styles.hint}>
              Examples — click to use:
            </p>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 5 }}>
              {EXAMPLE_TASKS.map((t, i) => (
                <button
                  key={i}
                  style={{
                    textAlign: 'left',
                    padding: '7px 10px',
                    borderRadius: 6,
                    border: '1px solid #f0f0f0',
                    background: '#fafafa',
                    fontSize: 11,
                    color: '#6b7280',
                    cursor: 'pointer',
                    lineHeight: 1.4,
                    fontFamily: 'var(--font-family-sans)',
                  }}
                  onClick={() => fillExample(t)}
                >
                  {t}
                </button>
              ))}
            </div>
          </div>

          {/* Connection */}
          <div className={styles.field}>
            <label className={styles.label}>
              Database connection <span className={styles.required}>*</span>
            </label>
            <select
              className={styles.select}
              value={connectionId}
              onChange={(e) => setConn(e.target.value)}
            >
              {connections.length === 0 && (
                <option value="">No connections available</option>
              )}
              {connections.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.connectionName} ({c.dbType})
                </option>
              ))}
            </select>
          </div>

          {/* Schedule */}
          <div className={styles.row}>
            <div className={styles.field}>
              <label className={styles.label}>Schedule</label>
              <select
                className={styles.select}
                value={schedule}
                onChange={(e) => setSchedule(e.target.value)}
              >
                {SCHEDULES.map((s) => (
                  <option key={s.value} value={s.value}>{s.label}</option>
                ))}
              </select>
            </div>

            {(schedule === 'daily' || schedule === 'weekly' || schedule === 'monthly') && (
              <div className={styles.field}>
                <label className={styles.label}>Run time</label>
                <input
                  className={styles.input}
                  type="time"
                  value={scheduleTime}
                  onChange={(e) => setTime(e.target.value)}
                />
              </div>
            )}
          </div>
        </div>

        <div className={styles.footer}>
          <button className={styles.cancelBtn} onClick={onClose}>Cancel</button>
          <button className={styles.saveBtn} onClick={handleSave} disabled={!canSave}>
            {existingAgent ? 'Save changes' : 'Create agent'}
          </button>
        </div>
      </div>
    </div>
  )
}
