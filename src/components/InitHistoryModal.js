import { useState, useEffect } from "react";
import {
  CheckCircle,
  AlertCircle,
  Clock,
  ChevronDown,
  ChevronUp,
  History,
  Loader,
  Database,
  FileText,
  Cpu,
  ToggleLeft,
  ToggleRight,
} from "lucide-react";
import Modal from "./ui/Modal";
import { connectionAPI } from "../lib/api/client";
import styles from "./InitHistoryModal.module.css";

const STAGE_ORDER = [
  "SCHEMA_SCAN",
  "DATA_SAMPLING",
  "KEY_COLUMN_ANALYSIS",
  "COLUMN_VALUE_COLLECTION",
  "INFERRED_RELATIONSHIPS",
  "SCHEMA_CLASSIFICATION",
  "AI_DESCRIPTION",
  "RAG_EMBEDDING",
  "BRAIN_ANALYSIS",
  "SEMANTIC_MODELING",
];

const STAGE_LABELS = {
  SCHEMA_SCAN: "Scanning schema",
  DATA_SAMPLING: "Sampling data",
  KEY_COLUMN_ANALYSIS: "Analyzing key columns",
  COLUMN_VALUE_COLLECTION: "Caching value dictionaries",
  INFERRED_RELATIONSHIPS: "Inferring join paths",
  SCHEMA_CLASSIFICATION: "Classifying schema",
  AI_DESCRIPTION: "Generating descriptions",
  RAG_EMBEDDING: "Building knowledge base",
  BRAIN_ANALYSIS: "Analyzing patterns",
  SEMANTIC_MODELING: "Modeling semantics",
};

const STAGE_DESCRIPTIONS = {
  SCHEMA_SCAN: {
    completed:
      "Mapped all tables, columns, data types, indexes, and foreign key relationships.",
    incomplete:
      "Discovers all tables, columns, data types, indexes, and foreign key relationships in your database.",
  },
  DATA_SAMPLING: {
    completed:
      "Sampled representative data from tables to capture real patterns and distributions.",
    incomplete:
      "Samples representative rows from each table to understand real data patterns and value distributions.",
  },
  KEY_COLUMN_ANALYSIS: {
    completed:
      "Identified key columns and their usage patterns across your database queries.",
    incomplete:
      "Identifies important columns from slow query patterns, JOIN usage, and WHERE clause frequency.",
  },
  COLUMN_VALUE_COLLECTION: {
    completed:
      "Cached low-cardinality value dictionaries used for exact filters and follow-up query generation.",
    incomplete:
      "Caches low-cardinality value dictionaries so Brain can use exact filter values instead of guessing.",
  },
  INFERRED_RELATIONSHIPS: {
    completed:
      "Learned inferred join paths from workload evidence and added them to Brain metadata.",
    incomplete:
      "Infers join paths from query lineage, slow logs, naming conventions, and sampled data correlation.",
  },
  SCHEMA_CLASSIFICATION: {
    completed:
      "Classified schema pattern and assigned roles to all tables (fact, dimension, bridge, lookup).",
    incomplete:
      "Detects schema patterns (star, snowflake, OLTP) and classifies table roles (fact, dimension, bridge).",
  },
  AI_DESCRIPTION: {
    completed:
      "Generated AI-powered descriptions explaining the purpose and content of each table and column.",
    incomplete:
      "Uses AI to generate plain-English descriptions for every table and column based on schema, sampled data, classifications, and relationships.",
  },
  RAG_EMBEDDING: {
    completed:
      "Rebuilt the retrieval index so chat can use the latest schema, relationship, and value context.",
    incomplete:
      "Rebuilds the retrieval index from schema DDL, relationship docs, and value summaries after metadata learning finishes.",
  },
  BRAIN_ANALYSIS: {
    completed:
      "Analyzed the refreshed metadata graph for downstream reasoning and recommendations.",
    incomplete:
      "Cross-references schema relationships, documentation, and learned metadata for downstream reasoning.",
  },
  SEMANTIC_MODELING: {
    completed:
      "Built the vault-backed semantic model with grain, preferred joins, filter semantics, and BI hints.",
    incomplete:
      "Builds a vault-backed semantic model from schema docs, key columns, relationships, value dictionaries, and approved query patterns.",
  },
};

const DETAIL_LABELS = {
  tablesDiscovered: "Tables discovered",
  columnsDiscovered: "Columns discovered",
  tablesSampled: "Tables sampled",
  columnsAnalyzed: "Key columns analyzed",
  candidateColumns: "Candidate columns",
  cachedColumns: "Cached value sets",
  lowCardinalityColumns: "Low-cardinality columns",
  embeddedColumns: "Embedded value sets",
  remainingCandidates: "Remaining candidates",
  totalRelationshipsInferred: "Relationships inferred",
  highConfidenceCount: "High-confidence relationships",
  newRelationshipsFound: "New relationships",
  existingRelationshipsUpdated: "Updated relationships",
  totalQueriesAnalyzed: "Queries analyzed",
  descriptionsGenerated: "Descriptions generated",
  tablesProcessed: "Tables processed",
  tablesSkipped: "Tables skipped",
  documentsIndexed: "Documents indexed",
  semanticTablesBuilt: "Semantic tables built",
  semanticJoinsBuilt: "Semantic joins built",
  tablesWithTimeColumns: "Tables with time columns",
  verifiedPatterns: "Verified query patterns",
  method: "Method",
};

function formatDetailKey(key) {
  return (
    DETAIL_LABELS[key] ||
    key.replace(/([A-Z])/g, " $1").replace(/^./, (c) => c.toUpperCase())
  );
}

function formatDuration(ms) {
  if (!ms || ms <= 0) return "";
  const seconds = Math.round(ms / 1000);
  if (seconds < 60) return `${seconds}s`;
  const minutes = Math.floor(seconds / 60);
  const remaining = seconds % 60;
  return remaining > 0 ? `${minutes}m ${remaining}s` : `${minutes}m`;
}

function formatTimestamp(ts) {
  if (!ts) return "";
  const d = new Date(ts);
  return d.toLocaleString(undefined, {
    month: "short",
    day: "numeric",
    hour: "numeric",
    minute: "2-digit",
    second: "2-digit",
  });
}

function computeTotalDuration(run) {
  if (run.totalDurationMs) return run.totalDurationMs;
  if (run.completedAt && run.startedAt) {
    return (
      new Date(run.completedAt).getTime() - new Date(run.startedAt).getTime()
    );
  }
  return null;
}

function SummaryCard({ summary }) {
  if (!summary) return null;
  const {
    totalTables,
    totalColumns,
    aiDescriptions,
    userDescriptions,
    tableDescriptions,
    columnDescriptions,
    dataSamplingEnabled,
    aiModel,
    embeddingModel,
  } = summary;

  return (
    <div className={styles.summaryCard}>
      <div className={styles.summaryTitle}>Current Database Knowledge</div>
      <div className={styles.summaryGrid}>
        <div className={styles.summaryItem}>
          <Database size={14} className={styles.summaryIcon} />
          <div className={styles.summaryItemContent}>
            <span className={styles.summaryValue}>
              {totalTables || tableDescriptions}
            </span>
            <span className={styles.summaryLabel}>Tables</span>
          </div>
        </div>
        <div className={styles.summaryItem}>
          <Database size={14} className={styles.summaryIcon} />
          <div className={styles.summaryItemContent}>
            <span className={styles.summaryValue}>
              {totalColumns || columnDescriptions}
            </span>
            <span className={styles.summaryLabel}>Columns</span>
          </div>
        </div>
        <div className={styles.summaryItem}>
          <Cpu size={14} className={styles.summaryIcon} />
          <div className={styles.summaryItemContent}>
            <span className={styles.summaryValue}>{aiDescriptions}</span>
            <span className={styles.summaryLabel}>AI descriptions</span>
          </div>
        </div>
        <div className={styles.summaryItem}>
          <FileText size={14} className={styles.summaryIcon} />
          <div className={styles.summaryItemContent}>
            <span className={styles.summaryValue}>{userDescriptions}</span>
            <span className={styles.summaryLabel}>User descriptions</span>
          </div>
        </div>
      </div>
      <div className={styles.summaryFooter}>
        <span className={styles.summaryTag}>
          {dataSamplingEnabled ? (
            <ToggleRight size={12} style={{ color: "#16a34a" }} />
          ) : (
            <ToggleLeft size={12} style={{ color: "#9ca3af" }} />
          )}
          Data sampling {dataSamplingEnabled ? "on" : "off"}
        </span>
        {aiModel && <span className={styles.summaryTag}>{aiModel}</span>}
        {embeddingModel && (
          <span className={styles.summaryTag}>{embeddingModel}</span>
        )}
      </div>
    </div>
  );
}

export default function InitHistoryModal({ isOpen, onClose, connectionId }) {
  const [history, setHistory] = useState([]);
  const [summary, setSummary] = useState(null);
  const [loadedKey, setLoadedKey] = useState(null);
  const [expandedRunId, setExpandedRunId] = useState(null);
  const requestKey = isOpen && connectionId ? `${connectionId}` : null;
  const loading = Boolean(requestKey && loadedKey !== requestKey);

  useEffect(() => {
    if (!requestKey) return;
    Promise.all([
      connectionAPI
        .getInitHistory(requestKey)
        .then((data) => (Array.isArray(data) ? data : []))
        .catch(() => []),
      connectionAPI.getInitSummary(requestKey).catch(() => null),
    ])
      .then(([historyData, summaryData]) => {
        setHistory(historyData);
        setSummary(summaryData);
      })
      .finally(() => setLoadedKey(requestKey));
  }, [requestKey]);

  return (
    <Modal
      isOpen={isOpen}
      onClose={onClose}
      title="Initialization History"
      size="medium"
    >
      {loading ? (
        <div className={styles.loadingState}>
          <Loader
            size={20}
            style={{ animation: "spin 1s linear infinite", marginBottom: 8 }}
          />
          <div>Loading history...</div>
        </div>
      ) : (
        <>
          <SummaryCard summary={summary} />
          {history.length === 0 ? (
            <div className={styles.emptyState}>
              <History size={40} className={styles.emptyIcon} />
              <div>No initialization runs yet</div>
            </div>
          ) : (
            <div className={styles.runList}>
              {history.map((run) => {
                const totalMs = computeTotalDuration(run);
                const isExpanded = expandedRunId === run.id;
                const succeeded = run.finalStage === "COMPLETED";
                const failed = run.finalStage === "FAILED";
                const runStageIdx = STAGE_ORDER.indexOf(run.finalStage);

                return (
                  <div key={run.id}>
                    <button
                      className={
                        isExpanded ? styles.runRowActive : styles.runRow
                      }
                      onClick={() =>
                        setExpandedRunId(isExpanded ? null : run.id)
                      }
                    >
                      {succeeded ? (
                        <CheckCircle
                          size={16}
                          style={{ color: "#16a34a" }}
                          className={styles.statusDot}
                        />
                      ) : failed ? (
                        <AlertCircle
                          size={16}
                          style={{ color: "#dc2626" }}
                          className={styles.statusDot}
                        />
                      ) : (
                        <Clock
                          size={16}
                          style={{ color: "#9ca3af" }}
                          className={styles.statusDot}
                        />
                      )}
                      <span className={styles.runDate}>
                        {formatTimestamp(run.startedAt)}
                      </span>
                      {totalMs > 0 && (
                        <span className={styles.runDuration}>
                          {formatDuration(totalMs)}
                        </span>
                      )}
                      {isExpanded ? (
                        <ChevronUp size={14} className={styles.chevron} />
                      ) : (
                        <ChevronDown size={14} className={styles.chevron} />
                      )}
                    </button>

                    {isExpanded && (
                      <div className={styles.runDetail}>
                        <div className={styles.detailHeader}>
                          <span
                            className={
                              succeeded
                                ? styles.statusCompleted
                                : styles.statusFailed
                            }
                          >
                            {succeeded ? "Completed" : "Failed"}
                          </span>
                          <span>{formatTimestamp(run.startedAt)}</span>
                          {run.completedAt && (
                            <>
                              <span>-</span>
                              <span>{formatTimestamp(run.completedAt)}</span>
                            </>
                          )}
                        </div>

                        {/* Progress bar for failed runs */}
                        {failed && run.progressPercent < 100 && (
                          <div className={styles.progressBar}>
                            <div
                              className={styles.progressFillError}
                              style={{ width: `${run.progressPercent}%` }}
                            />
                          </div>
                        )}

                        {/* Stage breakdown */}
                        {STAGE_ORDER.map((s, i) => {
                          const timing = run.stageTimings?.[s];
                          const stageCompleted =
                            i < runStageIdx || run.finalStage === "COMPLETED";
                          const desc =
                            STAGE_DESCRIPTIONS[s]?.[
                              stageCompleted ? "completed" : "incomplete"
                            ];
                          const details = run.stageDetails?.[s];
                          const detailEntries = details
                            ? Object.entries(details).filter(
                                ([k]) => k !== "method",
                              )
                            : [];
                          const method = details?.method;
                          return (
                            <div
                              key={s}
                              style={{
                                display: "flex",
                                flexDirection: "column",
                                gap: "2px",
                              }}
                            >
                              <div className={styles.stageRow}>
                                {stageCompleted ? (
                                  <CheckCircle
                                    size={14}
                                    style={{ color: "#16a34a", flexShrink: 0 }}
                                  />
                                ) : (
                                  <div
                                    style={{
                                      width: 14,
                                      height: 14,
                                      borderRadius: "50%",
                                      border: "2px solid #d1d5db",
                                      flexShrink: 0,
                                    }}
                                  />
                                )}
                                <span
                                  className={
                                    stageCompleted
                                      ? styles.stageNameActive
                                      : styles.stageNameMuted
                                  }
                                >
                                  {STAGE_LABELS[s]}
                                </span>
                                {timing?.durationMs > 0 && (
                                  <span className={styles.stageTiming}>
                                    {formatDuration(timing.durationMs)}
                                  </span>
                                )}
                              </div>
                              {desc && (
                                <div className={styles.stageDescription}>
                                  {desc}
                                </div>
                              )}
                              {(detailEntries.length > 0 || method) && (
                                <div className={styles.stageDetailsBox}>
                                  {detailEntries.map(([key, val]) => (
                                    <div key={key} className={styles.detailRow}>
                                      <span className={styles.detailLabel}>
                                        {formatDetailKey(key)}
                                      </span>
                                      <span className={styles.detailValue}>
                                        {typeof val === "number"
                                          ? val.toLocaleString()
                                          : String(val)}
                                      </span>
                                    </div>
                                  ))}
                                  {method && (
                                    <div className={styles.methodRow}>
                                      {method}
                                    </div>
                                  )}
                                </div>
                              )}
                            </div>
                          );
                        })}

                        {run.errorMessage && (
                          <div className={styles.errorBox}>
                            {run.errorMessage}
                          </div>
                        )}
                      </div>
                    )}
                  </div>
                );
              })}
            </div>
          )}
        </>
      )}
    </Modal>
  );
}
