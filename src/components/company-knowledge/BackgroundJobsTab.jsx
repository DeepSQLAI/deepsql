import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  CheckCircle2,
  ChevronDown,
  ChevronRight,
  Clock3,
  Loader2,
  Play,
  AlertTriangle,
  RefreshCw,
  RotateCw,
  StopCircle,
} from 'lucide-react'
import { connectionAPI } from '@/lib/api/client'
import styles from './CompanyKnowledgePanel.module.css'

function formatTimestamp(value) {
  if (!value) return ''
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) return ''
  return parsed.toLocaleString(undefined, {
    month: 'short',
    day: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
  })
}

/**
 * Human-readable label for each InitStage enum value. Falls back to the
 * raw enum name (with dashes for readability) for unknown values so a
 * newly-added backend stage doesn't render as blank.
 */
const STAGE_LABELS = {
  SCHEMA_SCAN: 'Schema scan',
  DATA_SAMPLING: 'Data sampling',
  KEY_COLUMN_ANALYSIS: 'Key column analysis',
  COLUMN_VALUE_COLLECTION: 'Column value collection',
  INFERRED_RELATIONSHIPS: 'Inferred relationships',
  SCHEMA_CLASSIFICATION: 'Schema classification',
  AI_DESCRIPTION: 'AI description',
  RAG_EMBEDDING: 'RAG embedding',
  BRAIN_ANALYSIS: 'Brain analysis',
  SEMANTIC_MODELING: 'Semantic modeling',
  NEEDS_ATTENTION: 'Needs attention',
  COMPLETED: 'Complete',
  FAILED: 'Failed',
}
function stageLabel(stage) {
  if (!stage) return 'Pending'
  return STAGE_LABELS[stage] || stage.replace(/_/g, ' ').toLowerCase()
}

/**
 * Canonical stage order — matches the backend InitStage enum exactly
 * (com.dbaagent.model.InitStage), excluding the terminal COMPLETED/FAILED
 * states which aren't pipeline steps. Any drift here is how "Complete 100%"
 * used to render with grey/pending dots for stages the frontend didn't know
 * existed.
 */
const STAGE_ORDER = [
  'SCHEMA_SCAN',
  'DATA_SAMPLING',
  'KEY_COLUMN_ANALYSIS',
  'COLUMN_VALUE_COLLECTION',
  'INFERRED_RELATIONSHIPS',
  'SCHEMA_CLASSIFICATION',
  'AI_DESCRIPTION',
  'RAG_EMBEDDING',
  'BRAIN_ANALYSIS',
  'SEMANTIC_MODELING',
]

function formatDuration(ms) {
  if (typeof ms !== 'number' || !Number.isFinite(ms) || ms < 0) return ''
  if (ms < 1000) return `${ms} ms`
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)} s`
  return `${Math.floor(ms / 60_000)}m ${Math.round((ms % 60_000) / 1000)}s`
}

export default function BackgroundJobsTab({ connectionId }) {
  const [jobs, setJobs] = useState([])
  const [jobsLoading, setJobsLoading] = useState(false)
  const [jobsError, setJobsError] = useState(null)
  const [jobActionKey, setJobActionKey] = useState(null)
  const [jobNotice, setJobNotice] = useState(null)
  const [selectedJobsFilter, setSelectedJobsFilter] = useState('all')

  // Brain init status — feeds the progress bar at the top. Null until the
  // first fetch resolves; 404 from the API (no init has ever run for this
  // connection) is normalized to null too.
  const [initStatus, setInitStatus] = useState(null)
  const [initStatusLoading, setInitStatusLoading] = useState(false)
  // Stages-timeline collapse + action-button busy/notice state.
  const [stagesExpanded, setStagesExpanded] = useState(false)
  // Scheduled maintenance jobs are power-user content — ten job cards
  // shouldn't dominate the first paint of "teach your business". Collapsed
  // by default; the count in the toggle label is enough for most visits.
  const [jobsExpanded, setJobsExpanded] = useState(false)
  const [initActionBusy, setInitActionBusy] = useState(null)  // 'reinit' | 'cancel' | 'refresh' | null
  const [initActionNotice, setInitActionNotice] = useState(null)

  const loadJobs = useCallback(async ({ silent = false } = {}) => {
    if (!connectionId) return
    if (!silent) setJobsLoading(true)
    try {
      const next = await connectionAPI.getBrainJobs(connectionId)
      setJobs(Array.isArray(next) ? next : [])
      setJobsError(null)
    } catch (err) {
      setJobsError(err?.response?.data?.message || 'Unable to load scheduled Brain jobs right now.')
    } finally {
      if (!silent) setJobsLoading(false)
    }
  }, [connectionId])

  const loadInitStatus = useCallback(async ({ silent = false } = {}) => {
    if (!connectionId) return
    if (!silent) setInitStatusLoading(true)
    try {
      const status = await connectionAPI.getInitStatus(connectionId)
      setInitStatus(status || null)
    } catch (err) {
      // 404 = no init record for this connection yet; treat as "not started"
      if (err?.response?.status === 404) {
        setInitStatus(null)
      }
      // Other errors are silent — the progress bar is decorative, the
      // scheduled-jobs view below is the real content of this tab.
    } finally {
      if (!silent) setInitStatusLoading(false)
    }
  }, [connectionId])

  useEffect(() => {
    void loadJobs()
    void loadInitStatus()
  }, [loadJobs, loadInitStatus])

  // Poll while any job is running OR while init is in progress, otherwise
  // refresh occasionally so the "next run" timestamps stay current without
  // hammering the API.
  useEffect(() => {
    if (!connectionId) return undefined
    const anyRunning = jobs.some((job) => job.status === 'running')
    const initActive = initStatus
      && initStatus.currentStage !== 'COMPLETED'
      && initStatus.currentStage !== 'FAILED'
      && initStatus.currentStage !== 'NEEDS_ATTENTION'
      && initStatus.currentStage !== 'ERROR'
      && !initStatus.completedAt
    const intervalMs = (anyRunning || initActive) ? 4000 : 15000
    const intervalId = window.setInterval(() => {
      void loadJobs({ silent: true })
      void loadInitStatus({ silent: true })
    }, intervalMs)
    return () => window.clearInterval(intervalId)
  }, [connectionId, jobs, initStatus, loadJobs, loadInitStatus])

  const handleRunJob = useCallback(async (jobKey) => {
    if (!connectionId || !jobKey) return
    setJobNotice(null)
    setJobActionKey(jobKey)
    try {
      const result = await connectionAPI.runBrainJob(connectionId, jobKey)
      setJobNotice({
        tone: 'success',
        message: result?.message || 'Background job started.',
      })
      await loadJobs({ silent: true })
    } catch (err) {
      setJobNotice({
        tone: 'error',
        message: err?.response?.data?.message || 'Unable to start that background job right now.',
      })
    } finally {
      setJobActionKey(null)
    }
  }, [connectionId, loadJobs])

  // Hard re-init: wipes the in-progress / FAILED row and schedules a fresh
  // pipeline starting from SCHEMA_SCAN. Confirmed because it discards
  // any partial progress and re-runs the long stages (AI_DESCRIPTION +
  // EMBEDDINGS can each take 5-15 min).
  const handleReinit = useCallback(async () => {
    if (!connectionId) return
    const confirmed = window.confirm(
      'Re-initialize the Brain for this connection?\n\n'
      + 'This wipes the current init progress and runs the full pipeline '
      + 'from SCHEMA_SCAN. Long stages (AI_DESCRIPTION, EMBEDDINGS) can '
      + 'take 5-15 minutes each.'
    )
    if (!confirmed) return
    setInitActionBusy('reinit')
    setInitActionNotice(null)
    try {
      const res = await connectionAPI.forceRebuild(connectionId)
      setInitActionNotice({
        tone: 'success',
        message: res?.message || 'Re-initialization started.',
      })
      await loadInitStatus({ silent: true })
    } catch (err) {
      setInitActionNotice({
        tone: 'error',
        message: err?.response?.data?.message
          || err?.message
          || 'Could not start re-initialization.',
      })
    } finally {
      setInitActionBusy(null)
    }
  }, [connectionId, loadInitStatus])

  // Cancel a running init. Backend sets cancel_requested; the next stage
  // boundary picks it up. The progress bar will reflect FAILED with a
  // "cancelled" message once the worker stops.
  const handleCancelInit = useCallback(async () => {
    if (!connectionId) return
    const confirmed = window.confirm(
      'Cancel the running Brain initialization?\n\n'
      + 'The current stage will finish, then the pipeline stops. Partial '
      + 'data already written (schema scan, key columns, etc.) is kept; '
      + 'remaining stages are not run.'
    )
    if (!confirmed) return
    setInitActionBusy('cancel')
    setInitActionNotice(null)
    try {
      const res = await connectionAPI.cancelInit(connectionId)
      setInitActionNotice({
        tone: 'success',
        message: res?.message || 'Cancellation requested.',
      })
      await loadInitStatus({ silent: true })
    } catch (err) {
      setInitActionNotice({
        tone: 'error',
        message: err?.response?.data?.message
          || err?.message
          || 'Could not cancel.',
      })
    } finally {
      setInitActionBusy(null)
    }
  }, [connectionId, loadInitStatus])

  // Force-refresh status + jobs. The polling loop already runs every 4-15 s
  // automatically, but the button gives users explicit "fetch now" control
  // when they want immediate feedback (e.g. after triggering a re-init).
  const handleManualRefresh = useCallback(async () => {
    if (!connectionId) return
    setInitActionBusy('refresh')
    setInitActionNotice(null)
    try {
      await Promise.all([loadInitStatus({ silent: true }), loadJobs({ silent: true })])
    } finally {
      setInitActionBusy(null)
    }
  }, [connectionId, loadInitStatus, loadJobs])

  const stats = useMemo(() => {
    const activeCount = jobs.filter((job) => job.status === 'active' || job.status === 'running').length
    const runningCount = jobs.filter((job) => job.status === 'running').length
    return { totalCount: jobs.length, activeCount, runningCount }
  }, [jobs])

  const filteredJobs = useMemo(() => {
    const byNextRun = [...jobs].sort((left, right) => {
      const leftTime = left?.nextRunAt ? new Date(left.nextRunAt).getTime() : Number.MAX_SAFE_INTEGER
      const rightTime = right?.nextRunAt ? new Date(right.nextRunAt).getTime() : Number.MAX_SAFE_INTEGER
      return leftTime - rightTime
    })
    switch (selectedJobsFilter) {
      case 'active':
        return jobs.filter((job) => job.status === 'active' || job.status === 'running')
      case 'running':
        return jobs.filter((job) => job.status === 'running')
      case 'next':
        return byNextRun
      case 'all':
      default:
        return jobs
    }
  }, [jobs, selectedJobsFilter])

  if (!connectionId) {
    return <div className={styles.emptyState}>Select a connection to view scheduled Brain jobs.</div>
  }

  // Progress bar inputs — defensive defaults so the section renders even
  // when init-status has never been written for this connection.
  const stage = initStatus?.currentStage
  const percent = typeof initStatus?.progressPercent === 'number'
    ? Math.max(0, Math.min(100, initStatus.progressPercent))
    : 0
  const isComplete = stage === 'COMPLETED'
  const isNeedsAttention = stage === 'NEEDS_ATTENTION'
  const isFailed = (stage === 'FAILED' || !!initStatus?.errorMessage) && !isNeedsAttention
  const isRunning = !!initStatus && !isComplete && !isFailed && !isNeedsAttention
  const schemaScanDetails = initStatus?.stageDetails?.SCHEMA_SCAN || {}
  const coverageLine = (() => {
    const live = schemaScanDetails.liveUserTableCount
    const discovered = schemaScanDetails.baseTablesDiscovered ?? schemaScanDetails.tablesDiscovered
    const coverage = schemaScanDetails.coveragePercent
    const schemas = Array.isArray(schemaScanDetails.schemasScanned)
      ? schemaScanDetails.schemasScanned.length
      : null
    if (typeof live === 'number' && typeof discovered === 'number') {
      const schemaBit = schemas != null ? ` across ${schemas} schema${schemas === 1 ? '' : 's'}` : ''
      const covBit = typeof coverage === 'number' ? ` (${coverage}%)` : ''
      return `Indexed ${discovered}/${live} base tables${covBit}${schemaBit}`
    }
    return null
  })()
  const initStartedText = formatTimestamp(initStatus?.startedAt)
  const initCompletedText = formatTimestamp(initStatus?.completedAt)

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
      <section className={styles.brainInitCard}>
        <div className={styles.brainInitHeader}>
          <div className={styles.brainInitHeading}>
            {isComplete ? (
              <CheckCircle2 size={16} className={styles.brainInitIconOk} />
            ) : isNeedsAttention ? (
              <AlertTriangle size={16} className={styles.brainInitIconFail} />
            ) : isFailed ? (
              <AlertTriangle size={16} className={styles.brainInitIconFail} />
            ) : isRunning ? (
              <Loader2 size={16} className={`${styles.brainInitIconRunning} ${styles.spinner}`} />
            ) : (
              <Clock3 size={16} className={styles.brainInitIconIdle} />
            )}
            <strong>Brain enrichment</strong>
            <span className={styles.brainInitStage}>
              {initStatus ? stageLabel(stage) : 'No init record yet for this connection'}
            </span>
          </div>
          <div className={styles.brainInitActions}>
            <span className={styles.brainInitPercent}>
              {initStatus ? `${percent}%` : '—'}
            </span>
            <button
              type="button"
              className={styles.brainInitIconBtn}
              onClick={handleManualRefresh}
              disabled={initActionBusy !== null}
              title="Refresh status now"
              aria-label="Refresh status"
            >
              {initActionBusy === 'refresh'
                ? <Loader2 size={13} className={styles.spinner} />
                : <RefreshCw size={13} />}
            </button>
            {isRunning && (
              <button
                type="button"
                className={styles.brainInitBtnDanger}
                onClick={handleCancelInit}
                disabled={initActionBusy !== null}
                title="Stop the running init at the next stage boundary"
              >
                {initActionBusy === 'cancel'
                  ? <Loader2 size={13} className={styles.spinner} />
                  : <StopCircle size={13} />}
                Cancel
              </button>
            )}
            {/*
              Re-init button is always available when not currently running;
              users may want to refresh enrichment after schema changes even
              if the previous run completed cleanly. Disabled mid-run to
              avoid stomping on an in-progress pipeline.
            */}
            {(!isRunning || isFailed || isNeedsAttention) && (
              <button
                type="button"
                className={styles.brainInitBtn}
                onClick={handleReinit}
                disabled={initActionBusy !== null}
                title="Wipe progress and re-run the full init pipeline from SCHEMA_SCAN"
              >
                {initActionBusy === 'reinit'
                  ? <Loader2 size={13} className={styles.spinner} />
                  : <RotateCw size={13} />}
                Re-initialize
              </button>
            )}
          </div>
        </div>

        <div className={styles.brainInitProgressTrack}>
          <div
            className={`${styles.brainInitProgressFill} ${
              isFailed
                ? styles.brainInitProgressFillFailed
                : isComplete
                  ? styles.brainInitProgressFillDone
                  : ''
            }`}
            style={{ width: `${initStatus ? percent : 0}%` }}
          />
        </div>

        {(initStatus?.stageMessage || initStatus?.errorMessage || coverageLine || initStartedText || initCompletedText) && (
          <div className={styles.brainInitMeta}>
            {initStatus?.errorMessage ? (
              <span className={styles.brainInitError}>{initStatus.errorMessage}</span>
            ) : initStatus?.stageMessage ? (
              <span>{initStatus.stageMessage}</span>
            ) : null}
            {coverageLine && (
              <span className={isNeedsAttention ? styles.brainInitError : undefined}>
                {coverageLine}
              </span>
            )}
            <span className={styles.brainInitTimings}>
              {initStartedText && <>Started {initStartedText}</>}
              {initStartedText && initCompletedText && <> · </>}
              {initCompletedText && <>Completed {initCompletedText}</>}
              {initStatusLoading && (
                <> · <Loader2 size={11} className={styles.spinner} /> Refreshing</>
              )}
            </span>
          </div>
        )}

        {initActionNotice && (
          <div
            className={`${styles.brainInitNotice} ${
              initActionNotice.tone === 'error' ? styles.brainInitNoticeError : ''
            }`}
          >
            {initActionNotice.message}
          </div>
        )}

        {/* Stages timeline — collapsible. Reads from stage_timings JSON.
            Renders every InitStage in the canonical pipeline order so the
            user sees the full sequence even before later stages have run. */}
        {initStatus && (
          <div className={styles.brainInitStages}>
            <button
              type="button"
              className={styles.brainInitStagesToggle}
              onClick={() => setStagesExpanded(v => !v)}
              aria-expanded={stagesExpanded}
            >
              {stagesExpanded ? <ChevronDown size={12} /> : <ChevronRight size={12} />}
              {stagesExpanded ? 'Hide stages' : 'Show stages'} ({STAGE_ORDER.length})
            </button>
            {stagesExpanded && (
              <ol className={styles.brainInitStagesList}>
                {STAGE_ORDER.map(stageKey => {
                  const timing = initStatus.stageTimings?.[stageKey]
                  const isCurrent = stage === stageKey
                  // Once the pipeline reports COMPLETED, every stage is done —
                  // regardless of whether its individual timing entry made it
                  // into stage_timings (e.g. an older init run predating a
                  // stage, or a timing write that raced completion). Without
                  // this, a fully-finished Brain could still show grey/pending
                  // dots for stages the backend has already finished.
                  const isDone = isComplete || !!timing?.endedAt
                  const dotClass = isDone
                    ? styles.brainInitStageDotDone
                    : isCurrent
                      ? styles.brainInitStageDotCurrent
                      : styles.brainInitStageDotPending
                  return (
                    <li key={stageKey} className={styles.brainInitStageRow}>
                      <span className={`${styles.brainInitStageDot} ${dotClass}`} />
                      <span className={styles.brainInitStageName}>{stageLabel(stageKey)}</span>
                      <span className={styles.brainInitStageDuration}>
                        {isDone && timing?.durationMs != null
                          ? formatDuration(timing.durationMs)
                          : isCurrent
                            ? 'running…'
                            : ''}
                      </span>
                    </li>
                  )
                })}
              </ol>
            )}
          </div>
        )}
      </section>

      {/* Scheduled maintenance — collapsed by default. This is power-user
          content (recurring jobs like re-scans and re-embeddings); the
          "teach your business" flow above is what most visits are for. */}
      <button
        type="button"
        className={styles.brainInitStagesToggle}
        onClick={() => setJobsExpanded(v => !v)}
        aria-expanded={jobsExpanded}
      >
        {jobsExpanded ? <ChevronDown size={12} /> : <ChevronRight size={12} />}
        {jobsExpanded ? 'Hide' : 'Show'} scheduled maintenance ({stats.totalCount})
        {jobsLoading && <Loader2 size={12} className={styles.spinner} />}
      </button>

      {jobsExpanded && (
        <>
          <div className={styles.bgJobsStatsRow}>
            <button
              type="button"
              className={`${styles.bgJobsStat} ${selectedJobsFilter === 'all' ? styles.bgJobsStatActive : ''}`}
              onClick={() => setSelectedJobsFilter('all')}
            >
              <span className={styles.bgJobsStatLabel}>Scheduled</span>
              <strong className={styles.bgJobsStatValue}>{stats.totalCount}</strong>
            </button>
            <button
              type="button"
              className={`${styles.bgJobsStat} ${selectedJobsFilter === 'active' ? styles.bgJobsStatActive : ''}`}
              onClick={() => setSelectedJobsFilter('active')}
            >
              <span className={styles.bgJobsStatLabel}>Active</span>
              <strong className={styles.bgJobsStatValue}>{stats.activeCount}</strong>
            </button>
            <button
              type="button"
              className={`${styles.bgJobsStat} ${selectedJobsFilter === 'running' ? styles.bgJobsStatActive : ''}`}
              onClick={() => setSelectedJobsFilter('running')}
            >
              <span className={styles.bgJobsStatLabel}>Running</span>
              <strong className={styles.bgJobsStatValue}>{stats.runningCount}</strong>
            </button>
            <button
              type="button"
              className={`${styles.bgJobsStat} ${selectedJobsFilter === 'next' ? styles.bgJobsStatActive : ''}`}
              onClick={() => setSelectedJobsFilter('next')}
            >
              <span className={styles.bgJobsStatLabel}>By next run</span>
              <strong className={styles.bgJobsStatValue}>Sort</strong>
            </button>
            {jobsLoading && (
              <span className={styles.bgJobsLoading}>
                <Loader2 size={14} className={styles.spinner} /> Refreshing
              </span>
            )}
          </div>

          {jobNotice && (
            <div className={`${styles.diagnosticsPanel} ${jobNotice.tone === 'error' ? styles.danger : styles.muted}`}>
              <div className={styles.diagnosticsHeader}>{jobNotice.message}</div>
            </div>
          )}

          {jobsError && (
            <div className={`${styles.diagnosticsPanel} ${styles.danger}`}>
              <div className={styles.diagnosticsHeader}>{jobsError}</div>
            </div>
          )}

          {filteredJobs.length === 0 ? (
            <div className={styles.emptyState}>
              {jobs.length === 0
                ? 'No scheduled Brain jobs are registered for this connection yet.'
                : 'No jobs match this filter right now.'}
            </div>
          ) : (
            <div className={styles.bgJobsList}>
              {filteredJobs.map((job) => {
                const nextRunText = formatTimestamp(job.nextRunAt)
                const lastSuccessText = formatTimestamp(job.lastSuccessAt)
                const lastFailureText = formatTimestamp(job.lastFailureAt)
                const running = job.status === 'running'
                return (
                  <article key={job.key} className={styles.bgJobCard}>
                    <div className={styles.bgJobTopRow}>
                      <div className={styles.bgJobTitleBlock}>
                        <div className={styles.bgJobTitleRow}>
                          <h3 className={styles.bgJobTitle}>{job.title}</h3>
                          <span className={styles.bgJobScope}>
                            {job.scope === 'global' ? 'All connections' : 'This connection'}
                          </span>
                        </div>
                        <p className={styles.bgJobDescription}>{job.description}</p>
                      </div>
                      <span
                        className={`${styles.bgJobStatusBadge} ${
                          running
                            ? styles.bgJobStatusRunning
                            : job.status === 'active'
                              ? styles.bgJobStatusActive
                              : styles.bgJobStatusInactive
                        }`}
                      >
                        {job.status}
                      </span>
                    </div>

                    <div className={styles.bgJobMeta}>
                      <span>
                        <Clock3 size={13} />
                        {nextRunText ? ` Next run ${nextRunText}` : ' No next run scheduled'}
                      </span>
                      {lastSuccessText && <span>Last success {lastSuccessText}</span>}
                      {lastFailureText && <span>Last failure {lastFailureText}</span>}
                      {job.consecutiveFailures > 0 && (
                        <span>{job.consecutiveFailures} consecutive failure{job.consecutiveFailures > 1 ? 's' : ''}</span>
                      )}
                    </div>

                    {job.statusReason && (
                      <p className={styles.bgJobReason}>{job.statusReason}</p>
                    )}

                    <div className={styles.bgJobActions}>
                      <button
                        type="button"
                        className={styles.secondaryButton}
                        onClick={() => handleRunJob(job.key)}
                        disabled={jobActionKey === job.key || running}
                      >
                        {jobActionKey === job.key ? (
                          <><Loader2 size={14} className={styles.spinner} /> Starting…</>
                        ) : (
                          <><Play size={14} /> Run now</>
                        )}
                      </button>
                    </div>
                  </article>
                )
              })}
            </div>
          )}
        </>
      )}
    </div>
  )
}
