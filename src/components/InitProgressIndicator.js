import { useEffect, useRef, useState, useCallback } from "react";
import {
  Brain,
  CheckCircle,
  AlertCircle,
  Loader,
  Clock,
  ChevronDown,
  ChevronUp,
  RefreshCw,
  History,
  X,
} from "lucide-react";
import useInitProgressStore, {
  useIsInitActive,
} from "../lib/stores/useInitProgressStore";
import { connectionAPI } from "../lib/api/client";
import InitHistoryModal from "./InitHistoryModal";

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
  COMPLETED: "All set!",
  FAILED: "Initialization failed",
};

const STAGE_DESCRIPTIONS = {
  SCHEMA_SCAN: {
    pending:
      "Will discover all tables, columns, data types, indexes, and foreign key relationships in your database.",
    active:
      "Discovering tables, columns, data types, indexes, and foreign key relationships across your database.",
    completed:
      "Mapped all tables, columns, data types, indexes, and foreign key relationships.",
  },
  DATA_SAMPLING: {
    pending:
      "Will profile column statistics (distinct counts, null rates, value distributions) and sample rows from each table.",
    active:
      "Profiling columns (distinct counts, null rates, value distributions) and sampling representative rows from each table.",
    completed: "Profiled column statistics and sampled representative data.",
  },
  KEY_COLUMN_ANALYSIS: {
    pending:
      "Will identify important columns from query history, JOIN usage, filter usage, and grouping patterns.",
    active:
      "Analyzing query history, JOINs, filters, and grouping patterns to identify the most important columns in your schema.",
    completed:
      "Identified key columns and their usage patterns across your workload.",
  },
  COLUMN_VALUE_COLLECTION: {
    pending:
      "Will cache low-cardinality value dictionaries so the chat assistant can generate accurate filters and follow-up questions.",
    active:
      "Caching low-cardinality value dictionaries used for exact filters, disambiguation, and follow-up query generation.",
    completed:
      "Cached value dictionaries used for exact filters and better query generation.",
  },
  INFERRED_RELATIONSHIPS: {
    pending:
      "Will infer non-explicit join paths from query lineage, slow logs, naming patterns, and sampled data correlation.",
    active:
      "Inferring join paths from workload evidence so Brain can reason about real application relationships, not just declared foreign keys.",
    completed:
      "Learned inferred join paths from workload evidence and added them to Brain metadata.",
  },
  SCHEMA_CLASSIFICATION: {
    pending:
      "Will detect schema patterns (star, snowflake, OLTP) and classify table roles (fact, dimension, bridge).",
    active:
      "Classifying schema patterns and assigning table roles based on key column relationships and structure.",
    completed:
      "Classified schema pattern and assigned roles to all tables (fact, dimension, bridge, lookup).",
  },
  AI_DESCRIPTION: {
    pending:
      "Will use AI to analyze schema structure, classifications, relationships, and sampled data to generate business descriptions.",
    active:
      "AI analyzing schema, classifications, relationships, and sample data to write business descriptions (parallel batches).",
    completed:
      "Generated business-oriented descriptions for all tables and columns.",
  },
  RAG_EMBEDDING: {
    pending:
      "Will rebuild the retrieval index after metadata learning so chat can find the latest schema, relationships, and value context.",
    active:
      "Rebuilding the retrieval index from schema DDL, relationship docs, and value summaries.",
    completed:
      "Refreshed the retrieval index used by the chat assistant.",
  },
  BRAIN_ANALYSIS: {
    pending:
      "Will analyze the refreshed metadata graph to enable intelligent recommendations and downstream reasoning.",
    active:
      "Cross-referencing schema relationships, documentation, and learned metadata for downstream reasoning.",
    completed:
      "Completed downstream Brain analysis on top of the refreshed metadata graph.",
  },
  SEMANTIC_MODELING: {
    pending:
      "Will build a vault-backed semantic model with table grain, preferred joins, filter semantics, and BI hints.",
    active:
      "Building the semantic model from schema docs, key columns, relationships, value dictionaries, and approved query patterns.",
    completed:
      "Built the vault-backed semantic model used for BI-grade query generation and schema reasoning.",
  },
};

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

function formatDuration(ms) {
  if (!ms || ms <= 0) return "";
  const seconds = Math.round(ms / 1000);
  if (seconds < 60) return `${seconds}s`;
  const minutes = Math.floor(seconds / 60);
  const remaining = seconds % 60;
  return remaining > 0 ? `${minutes}m ${remaining}s` : `${minutes}m`;
}

// Handles both ISO strings ("2026-02-27T08:10:30") and Java LocalDateTime
// arrays ([year, month, day, hour, minute, second, nano]).
function parseDateTime(ts) {
  if (!ts) return null;
  if (Array.isArray(ts)) {
    const [year, month, day, hour = 0, minute = 0, second = 0] = ts;
    return new Date(year, month - 1, day, hour, minute, second);
  }
  const d = new Date(ts);
  return isNaN(d.getTime()) ? null : d;
}

function formatTimestamp(ts) {
  const d = parseDateTime(ts);
  if (!d) return "";
  return d.toLocaleString(undefined, {
    month: "short",
    day: "numeric",
    hour: "numeric",
    minute: "2-digit",
  });
}

function computeTotalDuration(run) {
  // Prefer the pre-computed field; fall back to computing from timestamps
  if (run.totalDurationMs) return run.totalDurationMs;
  const start = parseDateTime(run.startedAt);
  const end = parseDateTime(run.completedAt);
  if (start && end) return end.getTime() - start.getTime();
  return null;
}

function renderStageMetrics(stageName, details) {
  if (!details) return null;
  const stageData = details[stageName];
  if (!stageData) return null;
  const has = (key) => stageData[key] != null;
  const metrics = [];
  if (has("tablesDiscovered"))
    metrics.push(`${stageData.tablesDiscovered} tables`);
  if (has("columnsProfiled"))
    metrics.push(`${stageData.columnsProfiled} columns profiled`);
  if (has("columnsAnalyzed"))
    metrics.push(`${stageData.columnsAnalyzed} key columns`);
  if (has("cachedColumns"))
    metrics.push(`${stageData.cachedColumns} value sets cached`);
  if (has("totalRelationshipsInferred"))
    metrics.push(`${stageData.totalRelationshipsInferred} joins inferred`);
  if (has("highConfidenceCount"))
    metrics.push(`${stageData.highConfidenceCount} high confidence`);
  if (has("tablesSampled"))
    metrics.push(`${stageData.tablesSampled} tables sampled`);
  if (has("descriptionsGenerated"))
    metrics.push(`${stageData.descriptionsGenerated} descriptions`);
  else if (has("tablesProcessed"))
    metrics.push(
      `${stageData.tablesProcessed}/${stageData.tablesTotal ?? "?"} tables processed`,
    );
  if (has("documentsIndexed"))
    metrics.push(`${stageData.documentsIndexed} indexed`);
  return metrics.length > 0 ? metrics.join(" · ") : null;
}

export function InitProgressIndicator({ connectionId }) {
  const [expanded, setExpanded] = useState(false);
  const [history, setHistory] = useState([]);
  const [expandedRunIdx, setExpandedRunIdx] = useState(null);
  const [historyModalOpen, setHistoryModalOpen] = useState(false);
  const [reinitializing, setReinitializing] = useState(false);
  const [cancelling, setCancelling] = useState(false);
  const [forceRebuildConfirmOpen, setForceRebuildConfirmOpen] = useState(false);
  const [forceRebuilding, setForceRebuilding] = useState(false);
  const isActive = useIsInitActive(connectionId);
  const {
    stage,
    progress,
    message,
    error,
    stageTimings,
    stageDetails,
    startedAt,
    hasLoaded,
  } = useInitProgressStore();
  const setInitProgress = useInitProgressStore((s) => s.setInitProgress);
  const markLoaded = useInitProgressStore((s) => s.markLoaded);
  const clearInit = useInitProgressStore((s) => s.clearInit);
  const pollingRef = useRef(null);
  const errorCountRef = useRef(0);
  const containerRef = useRef(null);
  const prevStageRef = useRef(null);

  const fetchHistory = useCallback(async () => {
    if (!connectionId) return;
    try {
      const data = await connectionAPI.getInitHistory(connectionId);
      setHistory(Array.isArray(data) ? data.slice(0, 3) : []);
    } catch {
      // history is optional — fail silently
    }
  }, [connectionId]);

  const startPolling = useCallback(() => {
    if (pollingRef.current) clearInterval(pollingRef.current);
    errorCountRef.current = 0;
    const poll = async () => {
      try {
        const data = await connectionAPI.getInitStatus(connectionId);
        errorCountRef.current = 0;
        if (data) {
          setInitProgress(data);
          if (["COMPLETED", "FAILED"].includes(data.currentStage)) {
            clearInterval(pollingRef.current);
            pollingRef.current = null;
          }
        }
      } catch (err) {
        if (err?.status === 404) {
          clearInterval(pollingRef.current);
          pollingRef.current = null;
          markLoaded();
          return;
        }
        errorCountRef.current++;
        if (errorCountRef.current >= 10) {
          clearInterval(pollingRef.current);
          pollingRef.current = null;
        }
      }
    };
    poll();
    pollingRef.current = setInterval(poll, 3000);
  }, [connectionId, setInitProgress, markLoaded]);

  const handleReinit = useCallback(async () => {
    if (!connectionId || reinitializing || isActive) return;
    setReinitializing(true);
    try {
      await connectionAPI.reinitialize(connectionId);
      // Reset store state and restart polling
      clearInit();
      startPolling();
    } catch (err) {
      if (err?.response?.status === 409) {
        // Already in progress — just restart polling
        startPolling();
      }
    } finally {
      setReinitializing(false);
    }
  }, [connectionId, reinitializing, isActive, clearInit, startPolling]);

  const handleForceRebuild = useCallback(async () => {
    if (!connectionId || forceRebuilding || isActive) return;
    setForceRebuilding(true);
    setForceRebuildConfirmOpen(false);
    try {
      await connectionAPI.forceRebuild(connectionId);
      clearInit();
      startPolling();
    } catch (err) {
      if (err?.response?.status === 409) {
        startPolling();
      }
    } finally {
      setForceRebuilding(false);
    }
  }, [connectionId, forceRebuilding, isActive, clearInit, startPolling]);

  const handleCancel = useCallback(async () => {
    if (!connectionId || cancelling || !isActive) return;
    setCancelling(true);
    try {
      await connectionAPI.cancelInit(connectionId);
    } catch {
      // Ignore errors — polling will pick up the new state
    } finally {
      setCancelling(false);
    }
  }, [connectionId, cancelling, isActive]);

  // Clear stale state on connection switch
  useEffect(() => {
    clearInit();
    setHistory([]);
    setExpandedRunIdx(null);
  }, [connectionId, clearInit]);

  // Click-outside dismissal
  useEffect(() => {
    if (!expanded) return;
    const handler = (e) => {
      if (containerRef.current && !containerRef.current.contains(e.target)) {
        setExpanded(false);
      }
    };
    document.addEventListener("mousedown", handler);
    return () => document.removeEventListener("mousedown", handler);
  }, [expanded]);

  // Polling subscription
  useEffect(() => {
    if (!connectionId) return;
    errorCountRef.current = 0;

    const poll = async () => {
      try {
        const data = await connectionAPI.getInitStatus(connectionId);
        if (errorCountRef.current > 2 && pollingRef.current) {
          clearInterval(pollingRef.current);
          pollingRef.current = setInterval(poll, 3000);
        }
        errorCountRef.current = 0;
        if (data) {
          setInitProgress(data);
          if (["COMPLETED", "FAILED"].includes(data.currentStage)) {
            clearInterval(pollingRef.current);
            pollingRef.current = null;
          }
        }
      } catch (err) {
        // 404 = no init running for this connection — mark loaded so we show "Not initialized" or history
        if (err?.status === 404) {
          clearInterval(pollingRef.current);
          pollingRef.current = null;
          markLoaded();
          return;
        }
        errorCountRef.current++;
        if (errorCountRef.current >= 10) {
          clearInterval(pollingRef.current);
          pollingRef.current = null;
          setInitProgress({
            currentStage: "FAILED",
            errorMessage:
              "Lost connection to server. Refresh the page to check status.",
          });
        }
        if (pollingRef.current && errorCountRef.current > 2) {
          clearInterval(pollingRef.current);
          const backoff = Math.min(
            3000 * Math.pow(2, errorCountRef.current - 2),
            12000,
          );
          pollingRef.current = setInterval(poll, backoff);
        }
      }
    };

    poll();
    pollingRef.current = setInterval(poll, 3000);

    return () => {
      if (pollingRef.current) {
        clearInterval(pollingRef.current);
        pollingRef.current = null;
      }
    };
  }, [connectionId]);

  // Fetch history on mount and when a run completes
  useEffect(() => {
    fetchHistory();
  }, [fetchHistory]);

  useEffect(() => {
    if (
      prevStageRef.current &&
      !["COMPLETED", "FAILED"].includes(prevStageRef.current) &&
      ["COMPLETED", "FAILED"].includes(stage)
    ) {
      fetchHistory();
    }
    prevStageRef.current = stage;
  }, [stage, fetchHistory]);

  // Don't render until we've at least tried to load status
  if (!hasLoaded) return null;

  const currentStageIdx = STAGE_ORDER.indexOf(stage);

  // Determine chip display mode
  let chipIcon, chipLabel, chipBg, chipColor;
  if (isActive) {
    chipIcon = (
      <Loader
        size={14}
        style={{ animation: "spin 1s linear infinite", color: "#6366f1" }}
      />
    );
    chipLabel = STAGE_LABELS[stage] || "Initializing...";
    chipBg = "#f0f0ff";
    chipColor = "#4f46e5";
  } else if (stage === "COMPLETED") {
    chipIcon = <CheckCircle size={14} style={{ color: "#16a34a" }} />;
    chipLabel = "Brain ready";
    chipBg = "#f0fdf4";
    chipColor = "#15803d";
  } else if (stage === "FAILED") {
    chipIcon = <AlertCircle size={14} style={{ color: "#dc2626" }} />;
    chipLabel = "Init failed";
    chipBg = "#fef2f2";
    chipColor = "#dc2626";
  } else {
    // No init ever or 404
    chipIcon = <Brain size={14} style={{ color: "#9ca3af" }} />;
    chipLabel = "Not initialized";
    chipBg = "#f3f4f6";
    chipColor = "#6b7280";
  }

  return (
    <div ref={containerRef} style={{ position: "relative" }}>
      <button
        onClick={() => setExpanded(!expanded)}
        style={{
          display: "flex",
          alignItems: "center",
          gap: "6px",
          padding: "5px 10px",
          borderRadius: "6px",
          border: "1px solid #e5e7eb",
          background: chipBg,
          cursor: "pointer",
          fontSize: "12px",
          fontWeight: 500,
          color: chipColor,
          transition: "all 0.15s ease",
        }}
      >
        {chipIcon}
        <span>{chipLabel}</span>
        {isActive && (
          <span style={{ fontSize: "11px", opacity: 0.7 }}>{progress}%</span>
        )}
      </button>

      {expanded && (
        <div
          style={{
            position: "absolute",
            top: "100%",
            right: 0,
            marginTop: "8px",
            width: "520px",
            background: "white",
            borderRadius: "12px",
            boxShadow: "0 10px 40px rgba(0,0,0,0.12)",
            border: "1px solid #e5e7eb",
            padding: "20px",
            zIndex: 1000,
            maxHeight: "680px",
            overflowY: "auto",
          }}
        >
          {/* Header */}
          <div
            style={{
              display: "flex",
              alignItems: "center",
              gap: "8px",
              marginBottom: "16px",
            }}
          >
            <Brain size={20} style={{ color: "#6366f1" }} />
            <span style={{ fontWeight: 600, fontSize: "15px" }}>
              Brain Initialization
            </span>
          </div>

          {/* Current / last run stage breakdown */}
          {stage ? (
            <>
              <div
                style={{
                  display: "flex",
                  flexDirection: "column",
                  gap: "10px",
                }}
              >
                {STAGE_ORDER.map((s, i) => {
                  const completed =
                    currentStageIdx > i || stage === "COMPLETED";
                  const current = s === stage;
                  const timing = stageTimings?.[s];
                  const descKey = completed
                    ? "completed"
                    : current
                      ? "active"
                      : "pending";
                  const desc = STAGE_DESCRIPTIONS[s]?.[descKey];
                  return (
                    <div
                      key={s}
                      style={{
                        display: "flex",
                        flexDirection: "column",
                        gap: "3px",
                      }}
                    >
                      <div
                        style={{
                          display: "flex",
                          alignItems: "center",
                          gap: "10px",
                        }}
                      >
                        {completed ? (
                          <CheckCircle
                            size={16}
                            style={{ color: "#16a34a", flexShrink: 0 }}
                          />
                        ) : current ? (
                          <Loader
                            size={16}
                            style={{
                              animation: "spin 1s linear infinite",
                              color: "#6366f1",
                              flexShrink: 0,
                            }}
                          />
                        ) : (
                          <div
                            style={{
                              width: 16,
                              height: 16,
                              borderRadius: "50%",
                              border: "2px solid #d1d5db",
                              flexShrink: 0,
                            }}
                          />
                        )}
                        <span
                          style={{
                            flex: 1,
                            fontSize: "13px",
                            color: completed || current ? "#374151" : "#9ca3af",
                          }}
                        >
                          {STAGE_LABELS[s]}
                        </span>
                        {timing?.durationMs > 0 && (
                          <span style={{ fontSize: "11px", color: "#9ca3af" }}>
                            {formatDuration(timing.durationMs)}
                          </span>
                        )}
                      </div>
                      {(completed || current) && desc && (
                        <div
                          style={{
                            marginLeft: "26px",
                            fontSize: "11px",
                            lineHeight: "1.45",
                            color: current ? "#6366f1" : "#9ca3af",
                          }}
                        >
                          {desc}
                        </div>
                      )}
                      {(() => {
                        const metricsText = renderStageMetrics(s, stageDetails);
                        return (completed || current) && metricsText ? (
                          <div
                            style={{
                              marginLeft: "26px",
                              fontSize: "10px",
                              color: "#a1a1aa",
                              marginTop: "2px",
                            }}
                          >
                            {metricsText}
                          </div>
                        ) : null;
                      })()}
                    </div>
                  );
                })}
              </div>

              {message && isActive && (
                <div
                  style={{
                    marginTop: "12px",
                    fontSize: "12px",
                    color: "#6b7280",
                    fontStyle: "italic",
                  }}
                >
                  {message}
                </div>
              )}

              {error && (
                <div
                  style={{
                    marginTop: "12px",
                    padding: "8px",
                    background: "#fef2f2",
                    borderRadius: "6px",
                    fontSize: "12px",
                    color: "#dc2626",
                  }}
                >
                  {error}
                </div>
              )}

              {isActive && (
                <div style={{ marginTop: "16px" }}>
                  <div
                    style={{
                      height: "4px",
                      background: "#e5e7eb",
                      borderRadius: "2px",
                      overflow: "hidden",
                    }}
                  >
                    <div
                      style={{
                        height: "100%",
                        background: "#6366f1",
                        borderRadius: "2px",
                        width: `${progress}%`,
                        transition: "width 0.3s ease",
                      }}
                    />
                  </div>
                  <div
                    style={{
                      display: "flex",
                      justifyContent: "space-between",
                      marginTop: "6px",
                      fontSize: "11px",
                      color: "#9ca3af",
                    }}
                  >
                    <span>{progress}%</span>
                    {startedAt && (
                      <span>Started {formatElapsed(startedAt)}</span>
                    )}
                  </div>
                  <div
                    style={{
                      marginTop: "14px",
                      borderTop: "1px solid #e5e7eb",
                      paddingTop: "14px",
                    }}
                  >
                    <button
                      onClick={handleCancel}
                      disabled={cancelling}
                      style={{
                        display: "flex",
                        alignItems: "center",
                        gap: "6px",
                        padding: "7px 14px",
                        borderRadius: "8px",
                        border: "1px solid #fca5a5",
                        background: "transparent",
                        color: "#dc2626",
                        fontSize: "12px",
                        fontWeight: 600,
                        cursor: cancelling ? "not-allowed" : "pointer",
                        opacity: cancelling ? 0.6 : 1,
                        transition: "all 0.15s ease",
                      }}
                    >
                      <X size={13} />
                      {cancelling ? "Cancelling..." : "Cancel initialization"}
                    </button>
                  </div>
                </div>
              )}
            </>
          ) : (
            <div
              style={{
                fontSize: "13px",
                color: "#9ca3af",
                marginBottom: "8px",
              }}
            >
              No active initialization. Connect and initialize to train the
              Brain.
            </div>
          )}

          {/* Action buttons — re-init + view history */}
          {(stage === "COMPLETED" || stage === "FAILED" || !stage) && (
            <div
              style={{
                display: "flex",
                gap: "8px",
                marginTop: "16px",
                borderTop: "1px solid #e5e7eb",
                paddingTop: "14px",
              }}
            >
              <button
                onClick={handleReinit}
                disabled={isActive || reinitializing}
                style={{
                  display: "flex",
                  alignItems: "center",
                  gap: "6px",
                  padding: "7px 14px",
                  borderRadius: "8px",
                  border: "1px solid #6366f1",
                  background: "transparent",
                  color: "#6366f1",
                  fontSize: "12px",
                  fontWeight: 600,
                  cursor:
                    isActive || reinitializing ? "not-allowed" : "pointer",
                  opacity: isActive || reinitializing ? 0.5 : 1,
                  transition: "all 0.15s ease",
                }}
              >
                <RefreshCw
                  size={13}
                  style={
                    reinitializing
                      ? { animation: "spin 1s linear infinite" }
                      : {}
                  }
                />
                {reinitializing
                  ? "Starting..."
                  : error === "Cancelled by user"
                    ? "Restart Brain init"
                    : "Re-initialize Brain"}
              </button>
              <button
                onClick={() => setForceRebuildConfirmOpen(true)}
                disabled={isActive || forceRebuilding}
                style={{
                  display: "flex",
                  alignItems: "center",
                  gap: "6px",
                  padding: "7px 14px",
                  borderRadius: "8px",
                  border: "1px solid #dc2626",
                  background: "transparent",
                  color: "#dc2626",
                  fontSize: "12px",
                  fontWeight: 600,
                  cursor: isActive || forceRebuilding ? "not-allowed" : "pointer",
                  opacity: isActive || forceRebuilding ? 0.5 : 1,
                  transition: "all 0.15s ease",
                }}
              >
                <RefreshCw
                  size={13}
                  style={forceRebuilding ? { animation: "spin 1s linear infinite" } : {}}
                />
                {forceRebuilding ? "Starting..." : "Force Rebuild"}
              </button>
              <button
                onClick={() => {
                  setExpanded(false);
                  setHistoryModalOpen(true);
                }}
                style={{
                  display: "flex",
                  alignItems: "center",
                  gap: "6px",
                  padding: "7px 14px",
                  borderRadius: "8px",
                  border: "1px solid #e5e7eb",
                  background: "transparent",
                  color: "#6b7280",
                  fontSize: "12px",
                  fontWeight: 500,
                  cursor: "pointer",
                  transition: "all 0.15s ease",
                }}
              >
                <History size={13} />
                {history.length > 0 ? "View full history" : "View details"}
              </button>
            </div>
          )}

          {/* Past Runs */}
          {history.length > 0 && (
            <>
              <div
                style={{
                  borderTop: "1px solid #e5e7eb",
                  marginTop: "16px",
                  paddingTop: "14px",
                }}
              >
                <span
                  style={{
                    fontSize: "12px",
                    fontWeight: 600,
                    color: "#6b7280",
                    textTransform: "uppercase",
                    letterSpacing: "0.05em",
                  }}
                >
                  Past Runs
                </span>
              </div>
              <div
                style={{
                  display: "flex",
                  flexDirection: "column",
                  gap: "6px",
                  marginTop: "10px",
                }}
              >
                {history.map((run, idx) => {
                  const totalMs = computeTotalDuration(run);
                  const isExpanded = expandedRunIdx === idx;
                  const succeeded =
                    (run.finalStage || run.currentStage) === "COMPLETED";
                  const failed =
                    (run.finalStage || run.currentStage) === "FAILED";
                  return (
                    <div key={run.id || idx}>
                      <button
                        onClick={() =>
                          setExpandedRunIdx(isExpanded ? null : idx)
                        }
                        style={{
                          display: "flex",
                          alignItems: "center",
                          gap: "8px",
                          width: "100%",
                          padding: "8px 10px",
                          borderRadius: "8px",
                          border: "none",
                          background: isExpanded ? "#f9fafb" : "transparent",
                          cursor: "pointer",
                          fontSize: "12px",
                          color: "#374151",
                          textAlign: "left",
                        }}
                      >
                        {succeeded ? (
                          <CheckCircle
                            size={14}
                            style={{ color: "#16a34a", flexShrink: 0 }}
                          />
                        ) : failed ? (
                          <AlertCircle
                            size={14}
                            style={{ color: "#dc2626", flexShrink: 0 }}
                          />
                        ) : (
                          <Clock
                            size={14}
                            style={{ color: "#9ca3af", flexShrink: 0 }}
                          />
                        )}
                        <div style={{ flex: 1, minWidth: 0 }}>
                          <div style={{
                            fontSize: "12px",
                            fontWeight: 500,
                            color: succeeded ? "#15803d" : failed ? "#dc2626" : "#374151",
                          }}>
                            {succeeded ? "Completed" : failed ? "Failed" : "Partial"}
                          </div>
                          <div style={{ fontSize: "11px", color: "#9ca3af", marginTop: "1px" }}>
                            {formatTimestamp(run.startedAt) || "—"}
                          </div>
                        </div>
                        {totalMs && (
                          <span style={{
                            color: "#9ca3af",
                            fontSize: "11px",
                            whiteSpace: "nowrap",
                            marginRight: "4px",
                          }}>
                            {formatDuration(totalMs)}
                          </span>
                        )}
                        {isExpanded ? (
                          <ChevronUp size={14} style={{ color: "#9ca3af" }} />
                        ) : (
                          <ChevronDown size={14} style={{ color: "#9ca3af" }} />
                        )}
                      </button>

                      {isExpanded && (
                        <div
                          style={{
                            padding: "8px 10px 8px 32px",
                            display: "flex",
                            flexDirection: "column",
                            gap: "6px",
                          }}
                        >
                          {STAGE_ORDER.map((s) => {
                            const timing = run.stageTimings?.[s];
                            const runFinal = run.finalStage || run.currentStage;
                            const runStageIdx = STAGE_ORDER.indexOf(runFinal);
                            const stageCompleted =
                              STAGE_ORDER.indexOf(s) < runStageIdx ||
                              runFinal === "COMPLETED";
                            return (
                              <div
                                key={s}
                                style={{
                                  display: "flex",
                                  alignItems: "center",
                                  gap: "8px",
                                  fontSize: "11px",
                                }}
                              >
                                {stageCompleted ? (
                                  <CheckCircle
                                    size={12}
                                    style={{ color: "#16a34a", flexShrink: 0 }}
                                  />
                                ) : (
                                  <div
                                    style={{
                                      width: 12,
                                      height: 12,
                                      borderRadius: "50%",
                                      border: "2px solid #d1d5db",
                                      flexShrink: 0,
                                    }}
                                  />
                                )}
                                <span
                                  style={{
                                    flex: 1,
                                    color: stageCompleted
                                      ? "#374151"
                                      : "#9ca3af",
                                  }}
                                >
                                  {STAGE_LABELS[s]}
                                </span>
                                {timing?.durationMs > 0 && (
                                  <span style={{ color: "#9ca3af" }}>
                                    {formatDuration(timing.durationMs)}
                                  </span>
                                )}
                              </div>
                            );
                          })}
                          {run.errorMessage && (
                            <div
                              style={{
                                marginTop: "4px",
                                padding: "6px 8px",
                                background: "#fef2f2",
                                borderRadius: "4px",
                                fontSize: "11px",
                                color: "#dc2626",
                              }}
                            >
                              {run.errorMessage}
                            </div>
                          )}
                        </div>
                      )}
                    </div>
                  );
                })}
              </div>
            </>
          )}

          {!stage && history.length === 0 && (
            <div
              style={{ fontSize: "12px", color: "#9ca3af", marginTop: "8px" }}
            >
              No previous runs
            </div>
          )}
        </div>
      )}

      <InitHistoryModal
        isOpen={historyModalOpen}
        onClose={() => setHistoryModalOpen(false)}
        connectionId={connectionId}
      />

      {/* Force Rebuild confirmation modal */}
      {forceRebuildConfirmOpen && (
        <div
          style={{
            position: "fixed",
            inset: 0,
            background: "rgba(0,0,0,0.45)",
            zIndex: 9999,
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
          }}
          onClick={() => setForceRebuildConfirmOpen(false)}
        >
          <div
            style={{
              background: "#fff",
              borderRadius: "12px",
              padding: "28px 32px",
              maxWidth: "440px",
              width: "90%",
              boxShadow: "0 20px 60px rgba(0,0,0,0.18)",
            }}
            onClick={(e) => e.stopPropagation()}
          >
            <div style={{ display: "flex", alignItems: "center", gap: "10px", marginBottom: "12px" }}>
              <div style={{
                width: "36px", height: "36px", borderRadius: "50%",
                background: "#fef2f2", display: "flex", alignItems: "center", justifyContent: "center", flexShrink: 0,
              }}>
                <RefreshCw size={18} color="#dc2626" />
              </div>
              <h3 style={{ margin: 0, fontSize: "16px", fontWeight: 700, color: "#111827" }}>
                Force Full Rebuild?
              </h3>
            </div>
            <p style={{ margin: "0 0 8px", fontSize: "14px", color: "#374151", lineHeight: "1.6" }}>
              This will rebuild <strong>everything from scratch</strong> — schema scan, data sampling, AI descriptions, embeddings, and all analysis stages.
            </p>
            <p style={{ margin: "0 0 24px", fontSize: "13px", color: "#6b7280", lineHeight: "1.6" }}>
              Expect this to take <strong>30 minutes to 1 hour</strong> depending on the size of your database. The Brain will be unavailable for queries during this time.
            </p>
            <div style={{ display: "flex", gap: "10px", justifyContent: "flex-end" }}>
              <button
                onClick={() => setForceRebuildConfirmOpen(false)}
                style={{
                  padding: "8px 18px", borderRadius: "8px", border: "1px solid #e5e7eb",
                  background: "transparent", color: "#6b7280", fontSize: "13px",
                  fontWeight: 500, cursor: "pointer",
                }}
              >
                Cancel
              </button>
              <button
                onClick={handleForceRebuild}
                style={{
                  padding: "8px 18px", borderRadius: "8px", border: "none",
                  background: "#dc2626", color: "#fff", fontSize: "13px",
                  fontWeight: 600, cursor: "pointer",
                }}
              >
                Yes, Force Rebuild
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

function formatElapsed(startedAt) {
  const seconds = Math.round(
    (Date.now() - new Date(startedAt).getTime()) / 1000,
  );
  if (seconds < 60) return `${seconds}s ago`;
  return `${Math.round(seconds / 60)}m ago`;
}
