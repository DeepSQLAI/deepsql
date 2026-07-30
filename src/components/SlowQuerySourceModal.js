"use client";

import { useState, useRef, useEffect, useCallback } from "react";
import {
  X,
  Cloud,
  Upload,
  CheckCircle,
  Clock,
  Info,
  Loader,
  Play,
  History,
  ChevronDown,
  ChevronRight,
  RefreshCw,
} from "lucide-react";
import {
  useSlowLogSourceConfig,
  useSaveSlowLogSourceConfig,
} from "@/lib/hooks/queries";
import {
  getProviderLabel,
  getProviderSource,
  formatTimestamp,
} from "@/components/tabs/Performance/SlowQueries/utils";
import { DEFAULT_SOURCE_CONFIG } from "@/components/tabs/Performance/SlowQueries/constants";
import { IamPolicyModal } from "@/components/tabs/Performance/SlowQueries";
import { slowQueriesAPI, slowLogSourceAPI } from "@/lib/api/client";
import styles from "./SlowQuerySourceModal.module.css";

const TIME_RANGES = [
  { key: "SINCE_LAST", label: "Since last ingestion" },
  { key: "LAST_24_HOURS", label: "Last 24 hours" },
  { key: "LAST_7_DAYS", label: "Last 7 days" },
  { key: "LAST_30_DAYS", label: "Last 30 days" },
];

/**
 * Unified modal for slow query source configuration and manual file upload.
 * Combines SourceConfigModal + UploadModal into one place.
 */
export default function SlowQuerySourceModal({
  connectionId,
  connectionName,
  dbType = "mysql",
  onClose,
}) {
  const {
    data: sourceConfigData,
    isLoading,
    refetch,
  } = useSlowLogSourceConfig(connectionId);
  const saveConfigMutation = useSaveSlowLogSourceConfig();

  const [localConfig, setLocalConfig] = useState(null);
  const [editing, setEditing] = useState(false);
  const [saveStatus, setSaveStatus] = useState(null);
  const [showIamPolicyModal, setShowIamPolicyModal] = useState(false);

  // Upload state
  const [uploading, setUploading] = useState(false);
  const [uploadStatus, setUploadStatus] = useState(null);
  const fileInputRef = useRef(null);

  // Ingestion history & ingest-now state
  const [history, setHistory] = useState([]);
  const [showHistory, setShowHistory] = useState(true);
  const [ingesting, setIngesting] = useState(false);
  const [ingestStatus, setIngestStatus] = useState(null);
  const [timeRange, setTimeRange] = useState("SINCE_LAST");

  const hasConfig = Boolean(sourceConfigData?.id);

  const loadHistory = useCallback(async () => {
    if (!connectionId) return;
    try {
      const data = await slowLogSourceAPI.getJobHistory(connectionId);
      setHistory(Array.isArray(data) ? data : []);
    } catch {
      setHistory([]);
    }
  }, [connectionId]);

  // Load history when toggled on
  useEffect(() => {
    if (showHistory) loadHistory();
  }, [showHistory, loadHistory]);

  const handleIngest = async () => {
    if (!connectionId || !sourceConfigData) return;
    setIngesting(true);
    setIngestStatus(null);
    try {
      const provider = sourceConfigData.providerType;
      const payload = {
        connectionId,
        timeRange,
        includeCloudWatch: provider === "CLOUDWATCH",
        includeS3: provider === "S3",
      };
      const result = await slowLogSourceAPI.startAsyncIngestion(payload);
      setIngestStatus({
        success: true,
        message: `Ingestion started (Job ${result.jobId ?? result.id ?? "submitted"}). Check history for progress.`,
      });
      // Auto-show and refresh history
      setShowHistory(true);
      loadHistory();
    } catch (err) {
      setIngestStatus({
        success: false,
        message: err.message || "Failed to start ingestion.",
      });
    } finally {
      setIngesting(false);
    }
  };

  const handleStartEdit = () => {
    if (hasConfig) {
      setLocalConfig({
        ...sourceConfigData,
        accessKeyId: "",
        secretAccessKey: "",
        sessionToken: "",
      });
    } else {
      setLocalConfig({ ...DEFAULT_SOURCE_CONFIG });
    }
    setEditing(true);
    setSaveStatus(null);
  };

  const handleSaveConfig = async () => {
    try {
      setSaveStatus(null);
      const payload = { ...localConfig, connectionId };
      // Don't send blank credentials — would overwrite stored values
      if (!payload.accessKeyId) delete payload.accessKeyId;
      if (!payload.secretAccessKey) delete payload.secretAccessKey;
      if (!payload.sessionToken) delete payload.sessionToken;
      await saveConfigMutation.mutateAsync(payload);
      setSaveStatus({
        success: true,
        message: "Configuration saved successfully",
      });
      setEditing(false);
      refetch();
    } catch (err) {
      setSaveStatus({
        success: false,
        message: err.message || "Failed to save configuration",
      });
    }
  };

  const handleFileUpload = async (event) => {
    const file = event.target.files[0];
    if (!file) return;

    try {
      setUploading(true);
      setUploadStatus(null);
      await slowQueriesAPI.analyzeLogFile(connectionId, file, dbType);
      setUploadStatus({
        success: true,
        message: `Successfully analyzed "${file.name}"`,
      });
    } catch (err) {
      setUploadStatus({
        success: false,
        message: err.message || "Upload failed",
      });
    } finally {
      setUploading(false);
      // Reset file input so same file can be re-uploaded
      if (fileInputRef.current) fileInputRef.current.value = "";
    }
  };

  return (
    <div className={styles.overlay} onClick={onClose}>
      <div className={styles.modal} onClick={(e) => e.stopPropagation()}>
        {/* Header */}
        <div className={styles.header}>
          <div className={styles.headerLeft}>
            <Cloud size={22} />
            <h2>Slow Query Source</h2>
            {connectionName && (
              <span className={styles.connectionBadge}>{connectionName}</span>
            )}
          </div>
          <button className={styles.closeButton} onClick={onClose}>
            <X size={20} />
          </button>
        </div>

        {/* Body */}
        <div className={styles.body}>
          {/* Current config status */}
          {isLoading ? (
            <div className={styles.uploadProgress}>
              <Loader size={16} className={styles.spinner} />
              <span>Loading configuration...</span>
            </div>
          ) : hasConfig && !editing ? (
            <div className={styles.currentConfig}>
              <div className={styles.configStatusRow}>
                <CheckCircle size={16} className={styles.configuredIcon} />
                <strong>
                  {getProviderLabel(sourceConfigData.providerType)}
                </strong>
                <code>{getProviderSource(sourceConfigData)}</code>
              </div>
              {sourceConfigData.s3Region && (
                <div className={styles.configStatusRow}>
                  <span>Region:</span>
                  <code>{sourceConfigData.s3Region}</code>
                </div>
              )}
              <div className={styles.configStatusRow}>
                <Clock size={14} />
                <span className={styles.configMuted}>
                  {sourceConfigData.lastProcessedAt
                    ? `Last ingested ${formatTimestamp(sourceConfigData.lastProcessedAt)}`
                    : "Never ingested"}
                </span>
              </div>
              <div className={styles.configStatusRow}>
                <span
                  className={`${styles.autoIngestBadge} ${sourceConfigData.autoScheduleEnabled ? styles.autoIngestOn : styles.autoIngestOff}`}
                >
                  {sourceConfigData.autoScheduleEnabled
                    ? "Auto-ingest ON"
                    : "Auto-ingest OFF"}
                </span>
                <span className={styles.configMuted}>
                  Every {sourceConfigData.refreshFrequencyMinutes || 1440} min
                </span>
              </div>
              <div className={styles.configActions} style={{ marginTop: 12 }}>
                <button className={styles.saveButton} onClick={handleStartEdit}>
                  Edit Configuration
                </button>
              </div>
            </div>
          ) : null}

          {/* Auto-ingest configuration form */}
          {(editing || (!hasConfig && !isLoading)) && (
            <div className={styles.section}>
              <div className={styles.sectionHeader}>
                <Cloud size={18} />
                <h3>Auto-Ingest Configuration</h3>
              </div>

              <SourceConfigForm
                config={localConfig || { ...DEFAULT_SOURCE_CONFIG }}
                setConfig={(updater) => {
                  if (!localConfig) {
                    const base = { ...DEFAULT_SOURCE_CONFIG };
                    setLocalConfig(
                      typeof updater === "function" ? updater(base) : updater,
                    );
                  } else {
                    setLocalConfig(updater);
                  }
                }}
                onSave={handleSaveConfig}
                onCancel={
                  hasConfig
                    ? () => {
                        setEditing(false);
                        setSaveStatus(null);
                      }
                    : null
                }
                saving={saveConfigMutation.isPending}
                onShowIamPolicy={() => setShowIamPolicyModal(true)}
              />
            </div>
          )}

          {/* Status message for config save */}
          {saveStatus && (
            <div
              className={`${styles.statusMessage} ${saveStatus.success ? styles.statusSuccess : styles.statusError}`}
            >
              {saveStatus.message}
            </div>
          )}

          {/* Divider */}
          <div className={styles.divider}>Manual Upload</div>

          {/* Compact manual upload */}
          <label
            className={styles.uploadRow}
            htmlFor={`file-upload-${connectionId}`}
          >
            <Upload size={16} />
            <span>Upload slow query log file</span>
            <small>(.log, .txt, .csv)</small>
            <input
              ref={fileInputRef}
              id={`file-upload-${connectionId}`}
              type="file"
              accept=".log,.txt,.csv"
              onChange={handleFileUpload}
              className={styles.uploadZoneInput}
              disabled={uploading}
            />
          </label>

          {uploading && (
            <div className={styles.uploadProgress}>
              <Loader size={16} className={styles.spinner} />
              <span>Analyzing file...</span>
            </div>
          )}

          {uploadStatus && (
            <div
              className={`${styles.statusMessage} ${uploadStatus.success ? styles.statusSuccess : styles.statusError}`}
            >
              {uploadStatus.message}
            </div>
          )}

          {/* Ingest Now + History (only when config exists) */}
          {hasConfig && !editing && (
            <>
              <div className={styles.divider}>Ingestion</div>

              {/* Ingest Now */}
              <div className={styles.ingestRow}>
                <select
                  className={styles.select}
                  value={timeRange}
                  onChange={(e) => setTimeRange(e.target.value)}
                >
                  {TIME_RANGES.map((tr) => (
                    <option key={tr.key} value={tr.key}>
                      {tr.label}
                    </option>
                  ))}
                </select>
                <button
                  className={styles.saveButton}
                  onClick={handleIngest}
                  disabled={ingesting}
                >
                  {ingesting ? (
                    <Loader size={14} className={styles.spinner} />
                  ) : (
                    <Play size={14} />
                  )}
                  Ingest Now
                </button>
              </div>

              {ingestStatus && (
                <div
                  className={`${styles.statusMessage} ${ingestStatus.success ? styles.statusSuccess : styles.statusError}`}
                >
                  {ingestStatus.message}
                </div>
              )}

              {/* Ingestion History */}
              <div className={styles.historySection}>
                <button
                  className={styles.historyToggle}
                  onClick={() => setShowHistory((v) => !v)}
                >
                  <History size={14} />
                  Ingestion History
                  {showHistory ? (
                    <ChevronDown size={14} />
                  ) : (
                    <ChevronRight size={14} />
                  )}
                </button>
                {showHistory && (
                  <div className={styles.historyList}>
                    <button
                      className={styles.historyRefreshBtn}
                      onClick={loadHistory}
                    >
                      <RefreshCw size={12} /> Refresh
                    </button>
                    {history.length === 0 ? (
                      <p className={styles.historyEmpty}>
                        No ingestion jobs found.
                      </p>
                    ) : (
                      history.map((job, i) => (
                        <JobHistoryRow
                          key={job.jobExecutionId || i}
                          job={job}
                        />
                      ))
                    )}
                  </div>
                )}
              </div>
            </>
          )}
        </div>
      </div>

      {showIamPolicyModal && (
        <IamPolicyModal
          sourceConfig={
            localConfig || sourceConfigData || DEFAULT_SOURCE_CONFIG
          }
          onClose={() => setShowIamPolicyModal(false)}
        />
      )}
    </div>
  );
}

/**
 * Inline form for source config (provider, credentials, schedule).
 * Extracted to keep the main component readable.
 */
function SourceConfigForm({
  config,
  setConfig,
  onSave,
  onCancel,
  saving,
  onShowIamPolicy,
}) {
  return (
    <>
      {/* Enable / Provider */}
      <div className={styles.formRow}>
        <div className={styles.formGroup}>
          <label>Enable Auto Ingestion</label>
          <select
            value={config.autoScheduleEnabled ? "yes" : "no"}
            onChange={(e) =>
              setConfig((prev) => ({
                ...prev,
                autoScheduleEnabled: e.target.value === "yes",
              }))
            }
            className={styles.select}
          >
            <option value="no">Disabled</option>
            <option value="yes">Enabled</option>
          </select>
        </div>
        <div className={styles.formGroup}>
          <label>Provider</label>
          <select
            value={config.providerType}
            onChange={(e) =>
              setConfig((prev) => ({
                ...prev,
                providerType: e.target.value,
                refreshFrequencyMinutes:
                  e.target.value === "CLOUDWATCH"
                    ? Math.max(prev.refreshFrequencyMinutes || 1440, 1440)
                    : prev.refreshFrequencyMinutes || 60,
              }))
            }
            className={styles.select}
          >
            <option value="S3">AWS S3</option>
            <option value="CLOUDWATCH">AWS CloudWatch Logs</option>
          </select>
          <span className={styles.comingSoonNote}>
            Azure, GCP, Datadog coming soon
          </span>
        </div>
      </div>

      {/* S3 fields */}
      {config.providerType === "S3" && (
        <div className={styles.formRow}>
          <div className={styles.formGroup}>
            <label>S3 Bucket</label>
            <input
              type="text"
              placeholder="my-bucket"
              value={config.bucketName}
              onChange={(e) =>
                setConfig((prev) => ({ ...prev, bucketName: e.target.value }))
              }
              className={styles.textInput}
            />
          </div>
          <div className={styles.formGroup}>
            <label>Object Key / Prefix</label>
            <input
              type="text"
              placeholder="logs/slow.log"
              value={config.objectPrefix}
              onChange={(e) =>
                setConfig((prev) => ({ ...prev, objectPrefix: e.target.value }))
              }
              className={styles.textInput}
            />
          </div>
        </div>
      )}

      {/* CloudWatch fields */}
      {config.providerType === "CLOUDWATCH" && (
        <div className={styles.formRow}>
          <div className={styles.formGroup}>
            <label>Log Group Name</label>
            <input
              type="text"
              placeholder="/aws/rds/instance/slowquery"
              value={config.logGroupName}
              onChange={(e) =>
                setConfig((prev) => ({ ...prev, logGroupName: e.target.value }))
              }
              className={styles.textInput}
            />
          </div>
          <div className={styles.formGroup}>
            <label>Log Stream Prefix (optional)</label>
            <input
              type="text"
              placeholder="slowquery"
              value={config.logStreamPrefix}
              onChange={(e) =>
                setConfig((prev) => ({
                  ...prev,
                  logStreamPrefix: e.target.value,
                }))
              }
              className={styles.textInput}
            />
          </div>
        </div>
      )}

      {/* AWS credentials */}
      {(config.providerType === "S3" ||
        config.providerType === "CLOUDWATCH") && (
        <>
          <div className={styles.formRow}>
            <div className={styles.formGroup}>
              <label>AWS Region</label>
              <input
                type="text"
                placeholder="us-east-1"
                value={config.s3Region}
                onChange={(e) =>
                  setConfig((prev) => ({ ...prev, s3Region: e.target.value }))
                }
                className={styles.textInput}
              />
            </div>
            <div className={styles.formGroup}>
              <label>Check Frequency (minutes)</label>
              <input
                type="number"
                min={config.providerType === "CLOUDWATCH" ? 1440 : 5}
                value={config.refreshFrequencyMinutes}
                onChange={(e) =>
                  setConfig((prev) => ({
                    ...prev,
                    refreshFrequencyMinutes: Number(e.target.value),
                  }))
                }
                className={styles.textInput}
              />
            </div>
          </div>

          <div className={styles.formRow}>
            <div className={styles.formGroup}>
              <label>AWS Access Key Id</label>
              <input
                type="password"
                placeholder="AKIA..."
                value={config.accessKeyId}
                onChange={(e) =>
                  setConfig((prev) => ({
                    ...prev,
                    accessKeyId: e.target.value,
                  }))
                }
                className={styles.textInput}
              />
            </div>
            <div className={styles.formGroup}>
              <label>AWS Secret Access Key</label>
              <input
                type="password"
                placeholder="********"
                value={config.secretAccessKey}
                onChange={(e) =>
                  setConfig((prev) => ({
                    ...prev,
                    secretAccessKey: e.target.value,
                  }))
                }
                className={styles.textInput}
              />
            </div>
          </div>

          <div className={styles.formGroup}>
            <label>AWS Session Token (optional)</label>
            <input
              type="password"
              placeholder="(optional)"
              value={config.sessionToken}
              onChange={(e) =>
                setConfig((prev) => ({ ...prev, sessionToken: e.target.value }))
              }
              className={styles.textInput}
            />
          </div>
        </>
      )}

      {/* Actions */}
      <div className={styles.configActions}>
        <button
          className={styles.saveButton}
          onClick={onSave}
          disabled={saving}
        >
          {saving ? <Loader size={14} className={styles.spinner} /> : null}
          Save Config
        </button>
        {onCancel && (
          <button className={styles.iamButton} onClick={onCancel}>
            Cancel
          </button>
        )}
        {(config.providerType === "S3" ||
          config.providerType === "CLOUDWATCH") && (
          <button className={styles.iamButton} onClick={onShowIamPolicy}>
            <Info size={14} />
            View IAM Policy
          </button>
        )}
      </div>
    </>
  );
}

function JobHistoryRow({ job }) {
  const [expanded, setExpanded] = useState(false);
  const badgeClass =
    {
      COMPLETED: styles.badgeCompleted,
      FAILED: styles.badgeFailed,
      RUNNING: styles.badgeRunning,
      CANCELLED: styles.badgeCancelled,
    }[job.status] || styles.badgeCancelled;

  const queries = job.slowQueriesFound ?? job.eventsProcessed ?? 0;

  return (
    <div className={styles.historyItem}>
      <button
        className={styles.historyRowBtn}
        onClick={() => setExpanded(!expanded)}
      >
        <span className={`${styles.statusBadge} ${badgeClass}`}>
          {job.status}
        </span>
        <span className={styles.historyMeta}>
          {queries} {job.slowQueriesFound != null ? "patterns" : "events"}
        </span>
        <span className={styles.historyDate}>
          {job.startTime ? new Date(job.startTime).toLocaleString() : "\u2014"}
        </span>
        {expanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
      </button>
      {expanded && (
        <div className={styles.historyDetail}>
          {job.message && <p>{job.message}</p>}
          {job.eventsProcessed != null && (
            <p>Events processed: {job.eventsProcessed.toLocaleString()}</p>
          )}
          {job.slowQueriesFound != null && (
            <p>Slow query patterns: {job.slowQueriesFound}</p>
          )}
          {job.endTime && (
            <p>Completed: {new Date(job.endTime).toLocaleString()}</p>
          )}
          {job.providerType && <p>Source: {job.providerType}</p>}
        </div>
      )}
    </div>
  );
}
