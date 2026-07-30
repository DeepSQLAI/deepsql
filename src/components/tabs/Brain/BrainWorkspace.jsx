import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Clock3, Database, Loader2, Network, Play, RefreshCw, Sparkles } from 'lucide-react'
import BrainInitModal from '@/components/BrainInitModal'
import { connectionAPI } from '@/lib/api/client'
import { DEFAULT_ROLE_FILTERS, ERD3DErrorBoundary, SchemaDiagramFilter, SchemaERD3D } from './SchemaERD3D'
import styles from './BrainWorkspace.module.css'

const STAGES = [
  { key: 'SCHEMA_SCAN', label: 'Scanning schema', desc: 'Mapping tables, columns, and relationships.' },
  { key: 'DATA_SAMPLING', label: 'Sampling data', desc: 'Profiling representative data for better semantics.' },
  { key: 'KEY_COLUMN_ANALYSIS', label: 'Analyzing key columns', desc: 'Learning which columns drive joins and filters.' },
  { key: 'COLUMN_VALUE_COLLECTION', label: 'Caching value dictionaries', desc: 'Saving low-cardinality values for exact filters.' },
  { key: 'INFERRED_RELATIONSHIPS', label: 'Inferring join paths', desc: 'Learning joins from evidence, not just declared FKs.' },
  { key: 'SCHEMA_CLASSIFICATION', label: 'Classifying schema', desc: 'Assigning table roles and schema patterns.' },
  { key: 'AI_DESCRIPTION', label: 'Generating schema descriptions', desc: 'Writing business-friendly docs for tables and columns.' },
  { key: 'RAG_EMBEDDING', label: 'Refreshing retrieval index', desc: 'Rebuilding semantic retrieval from the latest metadata.' },
  { key: 'BRAIN_ANALYSIS', label: 'Analyzing metadata graph', desc: 'Computing downstream reasoning signals for chat.' },
  { key: 'SEMANTIC_MODELING', label: 'Modeling semantics', desc: 'Building the BI-ready semantic layer in VaultDB.' },
]

const STAGE_LABELS = {
  NONE: 'Not initialized',
  ...Object.fromEntries(STAGES.map((stage) => [stage.key, stage.label])),
  COMPLETED: 'Brain ready',
  FAILED: 'Initialization failed',
}

function stageLabel(stage) {
  return STAGE_LABELS[stage] || 'Preparing Brain'
}

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

export default function BrainWorkspace({ connectionId }) {
  const jobsSectionRef = useRef(null)
  const [roleFilters, setRoleFilters] = useState(DEFAULT_ROLE_FILTERS)
  const [status, setStatus] = useState(null)
  const [summary, setSummary] = useState(null)
  const [history, setHistory] = useState([])
  const [jobs, setJobs] = useState([])
  const [loading, setLoading] = useState(true)
  const [jobsLoading, setJobsLoading] = useState(false)
  const [jobsError, setJobsError] = useState(null)
  const [jobActionKey, setJobActionKey] = useState(null)
  const [jobNotice, setJobNotice] = useState(null)
  const [selectedJobsFilter, setSelectedJobsFilter] = useState('all')
  const [modalConfig, setModalConfig] = useState(null)
  const [forceRebuildConfirmOpen, setForceRebuildConfirmOpen] = useState(false)
  const [forceRebuilding, setForceRebuilding] = useState(false)

  const loadWorkspace = useCallback(async ({ silent = false } = {}) => {
    if (!connectionId) return
    if (!silent) {
      setLoading(true)
      setJobsLoading(true)
    }
    let nextJobsError = null
    try {
      const [nextStatus, nextSummary, nextHistory, nextJobs] = await Promise.all([
        connectionAPI.getInitStatus(connectionId).catch(() => null),
        connectionAPI.getInitSummary(connectionId).catch(() => null),
        connectionAPI.getInitHistory(connectionId).catch(() => []),
        connectionAPI.getBrainJobs(connectionId).catch((err) => {
          nextJobsError = err?.response?.data?.message || 'Unable to load scheduled Brain jobs right now.'
          return []
        }),
      ])
      setStatus(nextStatus)
      setSummary(nextSummary)
      setHistory(Array.isArray(nextHistory) ? nextHistory : [])
      setJobs(Array.isArray(nextJobs) ? nextJobs : [])
      setJobsError(nextJobsError)
    } finally {
      if (!silent) {
        setLoading(false)
        setJobsLoading(false)
      }
    }
  }, [connectionId])

  useEffect(() => {
    void loadWorkspace()
  }, [loadWorkspace])

  useEffect(() => {
    if (!connectionId) {
      return undefined
    }
    const currentStage = status?.currentStage || status?.stage || 'NONE'
    const isRunning = currentStage && !['NONE', 'COMPLETED', 'FAILED'].includes(currentStage)
    const intervalMs = isRunning ? 4000 : 15000
    const intervalId = window.setInterval(() => {
      void loadWorkspace({ silent: true })
    }, intervalMs)
    return () => window.clearInterval(intervalId)
  }, [connectionId, loadWorkspace, status])

  const hasCompletedInit = useMemo(
    () => history.some((run) => run?.finalStage === 'COMPLETED'),
    [history],
  )

  const currentStage = status?.currentStage || status?.stage || 'NONE'
  const isRunning = currentStage && !['NONE', 'COMPLETED', 'FAILED'].includes(currentStage)
  const isReady = currentStage === 'COMPLETED' || hasCompletedInit
  const progress = Math.max(0, Math.min(100, Number(status?.progressPercent ?? status?.progress ?? (isReady ? 100 : 0))))
  const ctaLabel = isRunning ? 'View current status' : isReady ? 'View completed stages' : currentStage === 'FAILED' ? 'View failure details' : 'Initialize Brain'
  const primaryAutoStart = !isRunning && !isReady && currentStage !== 'FAILED'
  const statusText = isRunning
    ? `${stageLabel(currentStage)} · ${progress}%`
    : currentStage === 'FAILED'
      ? 'The last Brain init failed. You can retry safely.'
      : isReady
        ? 'Schema knowledge is ready for chat, docs, and semantic retrieval.'
        : 'Build schema knowledge before you rely on docs and semantic chat.'
  const openStatusModal = useCallback((autoStart = false) => {
    setModalConfig({ autoStart, closeOnDone: false })
  }, [])

  const handleForceRebuild = useCallback(async () => {
    if (!connectionId || forceRebuilding) return
    setForceRebuilding(true)
    setForceRebuildConfirmOpen(false)
    try {
      await connectionAPI.forceRebuild(connectionId)
      await loadWorkspace({ silent: true })
    } catch (err) {
      console.error('Force rebuild failed:', err)
    } finally {
      setForceRebuilding(false)
    }
  }, [connectionId, forceRebuilding, loadWorkspace])

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
      await loadWorkspace({ silent: true })
    } catch (err) {
      setJobNotice({
        tone: 'error',
        message: err?.response?.data?.message || 'Unable to start that background job right now.',
      })
    } finally {
      setJobActionKey(null)
    }
  }, [connectionId, loadWorkspace])

  const jobsSectionSubtitle = useMemo(() => {
    const activeJobs = jobs.filter((job) => job.status === 'active' || job.status === 'running').length
    if (!jobs.length) {
      return 'No scheduled Brain jobs are registered yet.'
    }
    return `${activeJobs}/${jobs.length} jobs active. Run them manually when you want to refresh metadata without waiting for the next schedule.`
  }, [jobs])

  const scheduledStats = useMemo(() => {
    const activeCount = jobs.filter((job) => job.status === 'active' || job.status === 'running').length
    const runningCount = jobs.filter((job) => job.status === 'running').length
    const nextRun = jobs
      .map((job) => job.nextRunAt)
      .filter(Boolean)
      .map((value) => new Date(value))
      .filter((value) => !Number.isNaN(value.getTime()))
      .sort((left, right) => left.getTime() - right.getTime())[0]

    return {
      totalCount: jobs.length,
      activeCount,
      runningCount,
      nextRunText: nextRun ? formatTimestamp(nextRun.toISOString()) : 'No next run scheduled',
    }
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

  const jobsFilterLabel = useMemo(() => {
    switch (selectedJobsFilter) {
      case 'active':
        return 'Showing active and currently running jobs.'
      case 'running':
        return 'Showing only jobs that are running right now.'
      case 'next':
        return 'Showing jobs ordered by their next scheduled run.'
      case 'all':
      default:
        return jobsSectionSubtitle
    }
  }, [jobsSectionSubtitle, selectedJobsFilter])

  const handleJobsDrilldown = useCallback((filterKey) => {
    setSelectedJobsFilter(filterKey)
    jobsSectionRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }, [])

  return (
    <div className={styles.page}>
      <div className={styles.hero}>
        <div className={styles.ctaCard}>
          <div className={styles.heroIntro}>
            <div className={styles.eyebrow}>Brain</div>
            <h1 className={styles.title}>Schema knowledge, kept simple</h1>
            <p className={styles.subtitle}>
              Initialize Brain to map schema relationships, generate docs, and keep the semantic layer ready for chat.
            </p>
          </div>
          <div className={styles.ctaHeader}>
            <div className={styles.ctaIcon}>
              <Sparkles size={16} />
            </div>
            <div>
              <div className={styles.ctaTitle}>{stageLabel(currentStage)}</div>
              <div className={styles.ctaCopy}>{statusText}</div>
            </div>
          </div>
          <div className={styles.progressRow}>
            <div className={styles.progressTrack}>
              <div className={styles.progressFill} style={{ width: `${progress}%` }} />
            </div>
            <span className={styles.progressValue}>{progress}%</span>
          </div>
          <div className={styles.ctaMeta}>
            {summary?.tableDescriptions != null && (
              <span><Database size={13} /> {summary.tableDescriptions} documented tables</span>
            )}
            {summary?.columnDescriptions != null && (
              <span><Network size={13} /> {summary.columnDescriptions} documented columns</span>
            )}
          </div>
          <div className={styles.heroStatsGrid}>
            <button
              type="button"
              className={`${styles.heroStatCard} ${selectedJobsFilter === 'all' ? styles.heroStatCardActive : ''}`}
              onClick={() => handleJobsDrilldown('all')}
            >
              <span className={styles.heroStatLabel}>Scheduled jobs</span>
              <strong className={styles.heroStatValue}>{scheduledStats.totalCount}</strong>
            </button>
            <button
              type="button"
              className={`${styles.heroStatCard} ${selectedJobsFilter === 'active' ? styles.heroStatCardActive : ''}`}
              onClick={() => handleJobsDrilldown('active')}
            >
              <span className={styles.heroStatLabel}>Active now</span>
              <strong className={styles.heroStatValue}>
                {jobsLoading && !jobs.length ? 'Loading…' : `${scheduledStats.activeCount} active`}
              </strong>
            </button>
            <button
              type="button"
              className={`${styles.heroStatCard} ${selectedJobsFilter === 'running' ? styles.heroStatCardActive : ''}`}
              onClick={() => handleJobsDrilldown('running')}
            >
              <span className={styles.heroStatLabel}>Running now</span>
              <strong className={styles.heroStatValue}>
                {jobsLoading && !jobs.length ? 'Loading…' : `${scheduledStats.runningCount} running`}
              </strong>
            </button>
            <button
              type="button"
              className={`${styles.heroStatCard} ${selectedJobsFilter === 'next' ? styles.heroStatCardActive : ''}`}
              onClick={() => handleJobsDrilldown('next')}
            >
              <span className={styles.heroStatLabel}>Next scheduled run</span>
              <strong className={styles.heroStatValue}>{scheduledStats.nextRunText}</strong>
            </button>
          </div>
          <div className={styles.buttonRow}>
            <button
              className={styles.ctaButton}
              onClick={() => openStatusModal(primaryAutoStart)}
              disabled={loading && !status}
            >
              {isRunning ? <Loader2 size={15} className={styles.spinningIcon} /> : <Sparkles size={15} />}
              {ctaLabel}
            </button>
            {(!isRunning && (isReady || currentStage === 'FAILED' || currentStage === 'NONE')) && (
              <button
                className={styles.secondaryButton}
                onClick={() => openStatusModal(true)}
                disabled={loading}
              >
                <RefreshCw size={15} />
                {isReady ? 'Refresh Brain' : currentStage === 'FAILED' ? 'Retry Brain init' : 'Start Brain init'}
              </button>
            )}
            {(!isRunning && !forceRebuilding) && (
              <button
                className={styles.forceRebuildButton}
                onClick={() => setForceRebuildConfirmOpen(true)}
                disabled={loading}
                title="Force Full Rebuild — regenerates everything from scratch"
              >
                <RefreshCw size={15} />
                Force Rebuild
              </button>
            )}
            {forceRebuilding && (
              <button className={styles.forceRebuildButton} disabled>
                <Loader2 size={15} className={styles.spinningIcon} />
                Starting…
              </button>
            )}
          </div>
        </div>
      </div>

      <section className={styles.diagramSection}>
        <div className={styles.diagramHeader}>
          <div>
            <h2 className={styles.sectionTitle}>Schema diagram</h2>
            <p className={styles.sectionSubtitle}>
              Start here. Use the diagram to inspect entities, roles, and joins before editing docs or refining semantic knowledge.
            </p>
          </div>
          <SchemaDiagramFilter roleFilters={roleFilters} setRoleFilters={setRoleFilters} />
        </div>
        <div className={styles.diagramWrap}>
          <ERD3DErrorBoundary>
            <SchemaERD3D
              connectionId={connectionId}
              hideTitle
              height={720}
              roleFilters={roleFilters}
              setRoleFilters={setRoleFilters}
            />
          </ERD3DErrorBoundary>
        </div>
      </section>

      <section ref={jobsSectionRef} className={styles.jobsSection}>
        <div className={styles.jobsHeader}>
          <div>
            <h2 className={styles.sectionTitle}>Scheduled jobs</h2>
            <p className={styles.sectionSubtitle}>{jobsFilterLabel}</p>
          </div>
          {(jobsLoading || loading) && (
            <span className={styles.jobsLoading}>
              <Loader2 size={14} className={styles.spinningIcon} />
              Refreshing
            </span>
          )}
        </div>

        {selectedJobsFilter !== 'all' && (
          <div className={styles.filterBar}>
            <span className={styles.filterPill}>
              {selectedJobsFilter === 'active' && 'Active jobs'}
              {selectedJobsFilter === 'running' && 'Running jobs'}
              {selectedJobsFilter === 'next' && 'Sorted by next run'}
            </span>
            <button
              type="button"
              className={styles.clearFilterButton}
              onClick={() => setSelectedJobsFilter('all')}
            >
              Show all jobs
            </button>
          </div>
        )}

        {jobNotice && (
          <div className={`${styles.notice} ${jobNotice.tone === 'error' ? styles.noticeError : styles.noticeSuccess}`}>
            {jobNotice.message}
          </div>
        )}

        {jobsError && (
          <div className={`${styles.notice} ${styles.noticeError}`}>
            {jobsError}
          </div>
        )}

        {filteredJobs.length === 0 ? (
          <div className={styles.emptyJobsState}>
            No jobs match this filter right now.
          </div>
        ) : (
          <div className={styles.jobsList}>
            {filteredJobs.map((job) => {
            const nextRunText = formatTimestamp(job.nextRunAt)
            const lastSuccessText = formatTimestamp(job.lastSuccessAt)
            const lastFailureText = formatTimestamp(job.lastFailureAt)
            return (
              <article key={job.key} className={styles.jobCard}>
                <div className={styles.jobTopRow}>
                  <div className={styles.jobTitleBlock}>
                    <div className={styles.jobTitleRow}>
                      <h3 className={styles.jobTitle}>{job.title}</h3>
                      <span className={styles.jobScope}>
                        {job.scope === 'global' ? 'All connections' : 'This connection'}
                      </span>
                    </div>
                    <p className={styles.jobDescription}>{job.description}</p>
                  </div>
                  <span
                    className={`${styles.jobStatusBadge} ${
                      job.status === 'running'
                        ? styles.jobStatusRunning
                        : job.status === 'active'
                          ? styles.jobStatusActive
                          : styles.jobStatusInactive
                    }`}
                  >
                    {job.status}
                  </span>
                </div>

                <div className={styles.jobMeta}>
                  <span>
                    <Clock3 size={13} />
                    {nextRunText ? `Next run ${nextRunText}` : 'No next run scheduled'}
                  </span>
                  {lastSuccessText && <span>Last success {lastSuccessText}</span>}
                  {lastFailureText && <span>Last failure {lastFailureText}</span>}
                  {job.consecutiveFailures > 0 && (
                    <span>{job.consecutiveFailures} consecutive failure{job.consecutiveFailures > 1 ? 's' : ''}</span>
                  )}
                </div>

                {job.statusReason && (
                  <p className={styles.jobReason}>{job.statusReason}</p>
                )}

                <div className={styles.jobActions}>
                  <button
                    className={styles.jobRunButton}
                    onClick={() => handleRunJob(job.key)}
                    disabled={jobActionKey === job.key}
                  >
                    {jobActionKey === job.key ? (
                      <Loader2 size={15} className={styles.spinningIcon} />
                    ) : (
                      <Play size={15} />
                    )}
                    Run now
                  </button>
                </div>
              </article>
            )
            })}
          </div>
        )}
      </section>

      {modalConfig && (
        <BrainInitModal
          connectionId={connectionId}
          autoStart={modalConfig.autoStart}
          closeOnDone={modalConfig.closeOnDone}
          onClose={() => {
            setModalConfig(null)
            void loadWorkspace({ silent: true })
          }}
        />
      )}

      {forceRebuildConfirmOpen && (
        <div className={styles.modalOverlay} onClick={() => setForceRebuildConfirmOpen(false)}>
          <div className={styles.modalCard} onClick={(e) => e.stopPropagation()}>
            <div className={styles.modalIconRow}>
              <div className={styles.modalIconWrap}>
                <RefreshCw size={18} />
              </div>
              <h3 className={styles.modalTitle}>Force Full Rebuild?</h3>
            </div>
            <p className={styles.modalBody}>
              This will rebuild <strong>everything from scratch</strong> — schema scan, data
              sampling, AI descriptions, embeddings, and all analysis stages.
            </p>
            <p className={styles.modalNote}>
              Expect this to take <strong>30 minutes to 1 hour</strong> depending on the size of
              your database. The Brain will be unavailable for queries during this time.
            </p>
            <div className={styles.modalActions}>
              <button
                className={styles.modalCancel}
                onClick={() => setForceRebuildConfirmOpen(false)}
              >
                Cancel
              </button>
              <button className={styles.modalConfirm} onClick={handleForceRebuild}>
                Yes, Force Rebuild
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
