import { useEffect, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Edit3, Loader2, RefreshCw, Save, Trash2, Upload, X } from 'lucide-react'
import { codeScanAPI } from '@/lib/api/client'
import { queryKeys } from '@/lib/queryKeys'
import {
  useCodeScanJobStream,
  useCodeScanSources,
  useCreateCodeScanSource,
  useDeleteCodeScanSource,
  useStartCodeScan,
  useUpdateCodeScanFocus,
} from '@/lib/hooks/queries'
import { useCompanyKnowledgeStore } from '@/lib/stores/useCompanyKnowledgeStore'
import styles from './CompanyKnowledgePanel.module.css'

const RUNNING_STATUSES = new Set(['PENDING', 'RUNNING'])

function formatBytes(bytes) {
  if (!bytes) return '—'
  const units = ['B', 'KB', 'MB', 'GB']
  let value = bytes
  let unit = 0
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024
    unit += 1
  }
  return `${value.toFixed(value >= 100 || unit === 0 ? 0 : 1)} ${units[unit]}`
}

function formatTimestamp(value) {
  if (!value) return 'never'
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return value
  return d.toLocaleString()
}

function SourceCard({ source, connectionId, onDelete, externalActiveJobId }) {
  const [error, setError] = useState(null)
  const startScan = useStartCodeScan()
  const updateFocus = useUpdateCodeScanFocus()
  const inputRef = useRef(null)
  const [editingFocus, setEditingFocus] = useState(false)
  const [focusDraft, setFocusDraft] = useState(source.focusText || '')

  // Pull the most-recent job for this source so we can show progress even
  // after a page reload, and detect any running job that wasn't started in
  // this browser session.
  const jobsQuery = useQuery({
    queryKey: queryKeys.codeScan.jobs(connectionId, source.id),
    queryFn: () => codeScanAPI.listJobs({ sourceId: source.id, connectionId }),
    enabled: Boolean(connectionId && source?.id),
    refetchInterval: (q) => {
      const latest = (q.state.data || [])[0]
      return latest && RUNNING_STATUSES.has(latest.status) ? 3000 : false
    },
  })
  const latestJob = (jobsQuery.data || [])[0]

  // Active job for SSE streaming: prefer one we just started in this session,
  // otherwise track the latest job if it's still in progress.
  const trackedJobId =
    externalActiveJobId ||
    (latestJob && RUNNING_STATUSES.has(latestJob.status) ? latestJob.id : null)
  const stream = useCodeScanJobStream({ jobId: trackedJobId, connectionId })

  // Effective state: prefer live SSE data, fall back to the latest persisted
  // job from the polling query. This is what makes the card show progress
  // even when the user lands on the page after a scan was already started.
  const effective = stream || latestJob

  const handleRescan = async (file) => {
    setError(null)
    try {
      await startScan.mutateAsync({ sourceId: source.id, connectionId, file })
      // Refetching jobsQuery on next interval will pick up the new RUNNING job.
      jobsQuery.refetch()
    } catch (err) {
      setError(err?.response?.data?.error || err?.message || 'Scan failed to start')
    }
  }

  const triggerFilePicker = () => inputRef.current?.click()
  const onFileChange = (event) => {
    const file = event.target.files?.[0]
    event.target.value = ''
    if (!file) return
    handleRescan(file)
  }

  const status = effective?.status
  const progress = effective?.progress ?? 0
  const isRunning = RUNNING_STATUSES.has(status)
  const hasJob = Boolean(effective)

  const stepLabel = effective?.currentStep || (status ? status.toLowerCase() : '')
  const counts =
    typeof effective?.suggestionsEmitted === 'number' && effective.suggestionsEmitted > 0
      ? ` · ${effective.suggestionsEmitted} suggestions`
      : ''
  const filesProgress =
    isRunning && effective?.filesTotal
      ? ` · ${effective.filesParsed ?? 0}/${effective.filesTotal} chunks`
      : ''

  // Stale-focus detection: source's focusHash differs from its latest job's
  // focusHash. We compare hashes only — if either is null, no chip shown.
  const staleFocus = Boolean(
    source.focusHash && latestJob?.focusHash && source.focusHash !== latestJob.focusHash,
  )

  const saveFocus = async () => {
    try {
      await updateFocus.mutateAsync({ sourceId: source.id, connectionId, focus: focusDraft })
      setEditingFocus(false)
    } catch (err) {
      setError(err?.response?.data?.error || err?.message || 'Failed to save focus')
    }
  }

  return (
    <div className={styles.sourceCard}>
      <div className={styles.sourceHeader}>
        <div>
          <h3 className={styles.entryTitle}>{source.name}</h3>
          <div className={styles.sourceMeta}>
            {source.kind} · {formatBytes(source.totalBytes)} · {source.fileCount ?? '—'} files ·
            {' '}last scanned {formatTimestamp(source.lastScannedAt)}
          </div>
        </div>
        <div className={styles.entryActions}>
          <input
            ref={inputRef}
            type="file"
            accept=".zip,.tar,.tgz,.gz"
            style={{ display: 'none' }}
            onChange={onFileChange}
          />
          <button
            type="button"
            className={styles.secondaryButton}
            onClick={triggerFilePicker}
            disabled={startScan.isPending || isRunning}
            title="Re-upload the archive to rescan with the current focus. The server doesn't keep a copy of your code between scans."
          >
            {isRunning ? (
              <><Loader2 size={14} className={styles.spinner} /> Scanning…</>
            ) : (
              <><RefreshCw size={14} /> Re-upload &amp; rescan</>
            )}
          </button>
          <button
            type="button"
            className={styles.iconDangerButton}
            onClick={() => onDelete(source.id)}
            disabled={isRunning}
            title="Delete source"
          >
            <Trash2 size={14} />
          </button>
        </div>
      </div>

      {error && <div className={styles.errorState}>{error}</div>}

      <div className={styles.focusBlock}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          <span className={styles.focusLabel}>Focus</span>
          {staleFocus && (
            <span className={styles.staleFocusChip} title="Saved focus differs from this source's last scan">
              focus changed — rescan to refresh
            </span>
          )}
          <span className={styles.bulkBarSpacer} />
          {!editingFocus ? (
            <button
              type="button"
              className={styles.iconButton}
              onClick={() => { setFocusDraft(source.focusText || ''); setEditingFocus(true) }}
              title="Edit focus"
            >
              <Edit3 size={12} />
            </button>
          ) : (
            <>
              <button
                type="button"
                className={styles.iconButton}
                onClick={() => setEditingFocus(false)}
                title="Cancel"
              >
                <X size={12} />
              </button>
              <button
                type="button"
                className={`${styles.iconButton} ${styles.iconButtonApprove}`}
                onClick={saveFocus}
                disabled={updateFocus.isPending}
                title="Save focus"
              >
                <Save size={12} />
              </button>
            </>
          )}
        </div>
        {editingFocus ? (
          <textarea
            className={styles.focusEditor}
            value={focusDraft}
            onChange={(e) => setFocusDraft(e.target.value)}
            placeholder="What matters most for business context? e.g. bookings, payments, cancellations; disambiguate USER_BOOKINGS vs NR_BOOKING"
          />
        ) : (
          <div className={styles.focusBody}>
            {source.focusText ? source.focusText : <span className={styles.muteText}>No focus set — scanner will extract anything it can find.</span>}
          </div>
        )}
      </div>

      {hasJob && (
        <div>
          <div className={styles.sourceMeta}>
            {stepLabel}{filesProgress}{counts}
          </div>
          <div className={styles.progressTrack} aria-hidden="true">
            <div
              className={styles.progressBar}
              style={{ transform: `scaleX(${Math.max(0, Math.min(100, progress)) / 100})` }}
            />
          </div>
          {effective?.message && (
            <div className={styles.sourceMeta} style={{ marginTop: 6 }}>
              {effective.message}
            </div>
          )}
        </div>
      )}
    </div>
  )
}

export default function CodeSourcesTab({ connectionId }) {
  const { data: sources = [], isLoading, error } = useCodeScanSources(connectionId)
  const createSource = useCreateCodeScanSource()
  const deleteSource = useDeleteCodeScanSource()
  const startScan = useStartCodeScan()

  const [dragActive, setDragActive] = useState(false)
  const [uploadError, setUploadError] = useState(null)
  // 'uploading' = file moving server-side · 'scanning' = scan kicked off ·
  // 'done' = at least one source successfully started scanning · null = idle
  const [uploadStage, setUploadStage] = useState(null)
  const [uploadProgress, setUploadProgress] = useState(null) // {done, total, currentName}
  const [focusDraft, setFocusDraft] = useState('')
  const dropInputRef = useRef(null)

  // Consume pending focus arriving from the Unresolved panel handoff.
  // Use the React 19 "adjust state during render" pattern to avoid the
  // project's lint rule against setState in useEffect.
  const pendingFocus = useCompanyKnowledgeStore((s) => s.pendingFocusFromAmbiguity)
  const consumePendingFocus = useCompanyKnowledgeStore((s) => s.consumePendingFocus)
  const [consumedFocusToken, setConsumedFocusToken] = useState('')
  if (pendingFocus && pendingFocus !== consumedFocusToken) {
    setConsumedFocusToken(pendingFocus)
    setFocusDraft((cur) => (cur ? cur + '\n\n' : '') + pendingFocus)
    consumePendingFocus()
  }

  // Clear the "done" banner after a short pause so the panel doesn't feel
  // stuck on a stale success state once the source cards take over.
  useEffect(() => {
    if (uploadStage === 'done') {
      const t = setTimeout(() => { setUploadStage(null); setUploadProgress(null) }, 1500)
      return () => clearTimeout(t)
    }
    return undefined
  }, [uploadStage])

  /**
   * Upload one archive AND immediately kick off its scan. Single user action
   * = single user-visible outcome. The backend's create-then-scan split is an
   * internal API quirk, not something the user needs to see.
   */
  const uploadAndScanOne = async (file) => {
    const focus = focusDraft.trim() || undefined
    const created = await createSource.mutateAsync({
      connectionId,
      file,
      name: file.name,
      focus,
    })
    await startScan.mutateAsync({
      sourceId: created.id,
      connectionId,
      file,
      focus,
    })
  }

  const handleUploads = async (rawFiles) => {
    const files = Array.from(rawFiles || []).filter(Boolean)
    if (files.length === 0) return
    setUploadError(null)
    setUploadStage('uploading')
    setUploadProgress({ done: 0, total: files.length, currentName: files[0].name })

    const failed = []
    for (let i = 0; i < files.length; i += 1) {
      const file = files[i]
      setUploadProgress({ done: i, total: files.length, currentName: file.name })
      try {
        // Process sequentially so backend chunking, focus-hashing, and the
        // per-source scan queue all stay ordered. Each source extracts and
        // accumulates signals independently; the brain merges them.
        await uploadAndScanOne(file)
      } catch (err) {
        failed.push({
          name: file.name,
          message: err?.response?.data?.error || err?.message || 'failed',
        })
      }
    }

    setUploadProgress({ done: files.length, total: files.length, currentName: null })
    if (failed.length === files.length) {
      setUploadError(`All uploads failed. ${failed[0]?.message ?? ''}`)
      setUploadStage(null)
    } else {
      if (failed.length > 0) {
        setUploadError(
          `${failed.length} of ${files.length} failed: ${failed.map((f) => `${f.name} (${f.message})`).join('; ')}`,
        )
      }
      setUploadStage('done')
      setFocusDraft('') // focus consumed by this batch — clear for the next
    }
  }

  const handleFilePicked = (event) => {
    const files = event.target.files
    handleUploads(files)
    event.target.value = ''
  }

  const handleDrop = (event) => {
    event.preventDefault()
    setDragActive(false)
    handleUploads(event.dataTransfer?.files)
  }

  const onDelete = async (sourceId) => {
    try {
      await deleteSource.mutateAsync({ sourceId, connectionId })
    } catch (err) {
      setUploadError(err?.response?.data?.error || err?.message || 'Delete failed')
    }
  }

  if (!connectionId) {
    return <div className={styles.emptyState}>Select a connection to manage code sources.</div>
  }

  const dropzoneStatus = (() => {
    if (uploadStage === 'uploading' && uploadProgress) {
      const { done, total, currentName } = uploadProgress
      if (total > 1) return `Uploading ${done + 1} of ${total}: ${currentName ?? ''}`
      return `Uploading ${currentName ?? 'archive'}…`
    }
    if (uploadStage === 'done' && uploadProgress) {
      return uploadProgress.total > 1
        ? `Scanning ${uploadProgress.total} archives — see the source cards below.`
        : 'Scan started — see the source card below.'
    }
    return null
  })()

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
      <input
        ref={dropInputRef}
        type="file"
        accept=".zip,.tar,.tgz,.gz"
        multiple
        style={{ display: 'none' }}
        onChange={handleFilePicked}
      />

      <label className={styles.focusBlock} style={{ marginTop: 0 }}>
        <span className={styles.focusLabel}>Focus these scans (optional)</span>
        <textarea
          className={styles.focusEditor}
          value={focusDraft}
          onChange={(e) => setFocusDraft(e.target.value)}
          placeholder="What matters most for business context? e.g. bookings, payments, cancellations; disambiguate USER_BOOKINGS vs NR_BOOKING. The brain&apos;s known ambiguity will be appended automatically."
        />
      </label>

      <div
        className={`${styles.dropZone} ${dragActive ? styles.dropZoneActive : ''}`}
        onDragOver={(e) => {
          e.preventDefault()
          setDragActive(true)
        }}
        onDragLeave={() => setDragActive(false)}
        onDrop={handleDrop}
        onClick={() => dropInputRef.current?.click()}
        role="button"
        tabIndex={0}
      >
        <Upload size={20} />
        <div className={styles.dropZoneTitle}>Drop one or more archives (.zip / .tar.gz) to scan</div>
        <div>or click to choose files. Scanning starts automatically — the focus above is applied to every archive in this batch. Max 200 MB extracted, 50 000 files per archive.</div>
        {dropzoneStatus && (
          <div style={{ marginTop: 4 }}>
            <Loader2 size={14} className={styles.spinner} /> {dropzoneStatus}
          </div>
        )}
      </div>

      {uploadError && <div className={styles.errorState}>{uploadError}</div>}

      {isLoading ? (
        <div className={styles.loadingState}>
          <Loader2 size={18} className={styles.spinner} /> Loading sources…
        </div>
      ) : error ? (
        <div className={styles.errorState}>Failed to load code sources.</div>
      ) : sources.length === 0 ? (
        <div className={styles.emptyState}>
          No code sources yet. Drop one or more archives above — we&apos;ll extract
          business rules and column descriptions from each, and merge the
          signals into the brain.
        </div>
      ) : (
        <div className={styles.cardGrid}>
          {sources.map((source) => (
            <SourceCard
              key={source.id}
              source={source}
              connectionId={connectionId}
              onDelete={onDelete}
            />
          ))}
        </div>
      )}
    </div>
  )
}
