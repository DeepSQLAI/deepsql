"use client";

import { useState, useMemo, useEffect } from "react";
import {
  X,
  Search,
  AlertTriangle,
  Clock,
  Zap,
  CheckCircle,
} from "lucide-react";
import { useLatestSlowQueryAnalysis } from "@/lib/hooks/queries";
import { QueryDetailDialog } from "../Performance/components";
import { useQueryOptimizationState } from "../Performance/hooks/useQueryOptimizationState";
import styles from "./SlowQueryListModal.module.css";

const SEVERITY_TABS = ["ALL", "CRITICAL", "HIGH", "MEDIUM", "LOW"];

const SEVERITY_BADGE_COLORS = {
  CRITICAL: { bg: "#FEE2E2", color: "#DC2626" },
  HIGH: { bg: "#FEF3C7", color: "#D97706" },
  MEDIUM: { bg: "#DBEAFE", color: "#2563EB" },
  LOW: { bg: "#F3F4F6", color: "#6B7280" },
};

function truncateQuery(sql, maxLen = 80) {
  if (!sql) return "—";
  return sql.length > maxLen ? `${sql.substring(0, maxLen)}...` : sql;
}

function formatDuration(ms) {
  if (ms == null) return "—";
  if (ms < 1000) return `${Math.round(ms)}ms`;
  return `${(ms / 1000).toFixed(2)}s`;
}

function calculateImpact(query) {
  const avg = query.avgExecutionTimeMs ?? 0;
  const count = query.callCount ?? 1;
  return Math.round((avg * count) / 1000);
}

function formatMs(ms) {
  if (ms == null || Number.isNaN(ms)) return "—";
  if (ms < 1000) return `${Math.round(ms)}ms`;
  if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`;
  return `${(ms / 60000).toFixed(1)}m`;
}

export default function SlowQueryListModal({
  open,
  onClose,
  connectionId,
  severityFilter,
  queryIdsFilter,
  remediationQueryIds = [],
  remediationItems = [],
  title,
}) {
  const { data: latestAnalysisData } = useLatestSlowQueryAnalysis(connectionId);
  const analysis = latestAnalysisData?.analysisData ?? null;
  const topSlowQueries = useMemo(
    () => analysis?.topSlowQueries ?? [],
    [analysis],
  );
  const totalSlowQueries = analysis?.totalSlowQueries ?? 0;
  const isCapped = totalSlowQueries > topSlowQueries.length;

  const remediationMap = useMemo(() => {
    const map = new Map();
    for (const item of remediationItems) {
      if (item.queryId) map.set(item.queryId, item);
    }
    return map;
  }, [remediationItems]);

  const [activeTab, setActiveTab] = useState(() => severityFilter || "ALL");
  const [searchTerm, setSearchTerm] = useState("");
  const [selectedQuery, setSelectedQuery] = useState(null);

  // Shared optimization hook — includes candidates + benchmark for runtime comparison
  const {
    handleOptimize,
    handleBenchmark,
    getOptimizationForQuery,
    isOptimizingQuery,
    setSelectedFingerprint,
    optimizationCandidates,
    isBenchmarking,
    rowKey: getRowKey,
  } = useQueryOptimizationState(connectionId, topSlowQueries);

  // Sync selectedFingerprint when a query is selected (needed for candidate fetching)
  useEffect(() => {
    setSelectedFingerprint(selectedQuery?.queryId ?? null);
  }, [selectedQuery, setSelectedFingerprint]);

  // Count queries by severity
  const severityCounts = useMemo(() => {
    const counts = { ALL: 0, CRITICAL: 0, HIGH: 0, MEDIUM: 0, LOW: 0 };
    for (const q of topSlowQueries) {
      counts.ALL++;
      const sev = (q.severity || "").toUpperCase();
      if (counts[sev] !== undefined) counts[sev]++;
    }
    return counts;
  }, [topSlowQueries]);

  // Filter queries based on active tab, search, and queryIdsFilter
  // Sort remediation queries to the top (preserving priority order)
  const filteredQueries = useMemo(() => {
    let queries = topSlowQueries;

    // Filter by queryIdsFilter (key customer click)
    if (queryIdsFilter?.length) {
      const idSet = new Set(queryIdsFilter);
      queries = queries.filter((q) => q.queryId && idSet.has(q.queryId));
    }

    // Filter by severity tab (skip when in queryIds mode — tabs are hidden)
    if (!queryIdsFilter?.length && activeTab !== "ALL") {
      queries = queries.filter(
        (q) => (q.severity || "").toUpperCase() === activeTab,
      );
    }

    // Filter by search term
    if (searchTerm.trim()) {
      const term = searchTerm.toLowerCase();
      queries = queries.filter(
        (q) =>
          (q.normalizedQuery || "").toLowerCase().includes(term) ||
          (q.affectedTables || []).some((t) => t.toLowerCase().includes(term)),
      );
    }

    // Sort: remediation candidates first (in priority order), then the rest by optimization impact
    if (remediationQueryIds.length > 0) {
      const remediationRank = new Map(
        remediationQueryIds.map((id, idx) => [id, idx]),
      );
      queries = [...queries].sort((a, b) => {
        const aRank = remediationRank.has(a.queryId)
          ? remediationRank.get(a.queryId)
          : Infinity;
        const bRank = remediationRank.has(b.queryId)
          ? remediationRank.get(b.queryId)
          : Infinity;
        return aRank - bRank;
      });
    } else {
      // Default sort: highest optimization impact first (total DB wall-time)
      queries = [...queries].sort((a, b) => {
        const aTotal = (a.avgExecutionTimeMs || 0) * (a.callCount || 1);
        const bTotal = (b.avgExecutionTimeMs || 0) * (b.callCount || 1);
        return bTotal - aTotal;
      });
    }

    return queries;
  }, [
    topSlowQueries,
    queryIdsFilter,
    activeTab,
    searchTerm,
    remediationQueryIds,
  ]);

  // Build query shape for QueryDetailDialog
  // sampleQuery is kept so the optimization hook can send it for EXPLAIN + AI rewrite with literals
  const buildSafeQuery = (query) => ({
    ...query,
    sampleQuery: query.sampleQuery || query.queryText,
    queryText: query.normalizedQuery,
    avgDurationMs: query.avgExecutionTimeMs,
    maxDurationMs: query.maxExecutionTimeMs,
    callCount: query.callCount,
    impactScore: calculateImpact(query),
    tables: query.affectedTables,
  });

  // Pass through optimization result (full explainAnalysis needed for EXPLAIN tab)
  const buildSafeOptimization = (optimization) => optimization ?? null;

  const handleViewDetails = (query) => {
    if (!query.normalizedQuery) return;
    setSelectedQuery(query);
  };

  if (!open) return null;

  const hasNoAnalysis = !analysis;

  return (
    <div className={styles.overlay} onClick={onClose}>
      <div className={styles.modal} onClick={(e) => e.stopPropagation()}>
        {/* Header */}
        <div className={styles.header}>
          <div>
            <h2 className={styles.title}>{title || "Slow Queries"}</h2>
            {isCapped && (
              <p className={styles.capNotice}>
                Showing top {topSlowQueries.length} of {totalSlowQueries}{" "}
                queries from latest analysis
              </p>
            )}
          </div>
          <button type="button" className={styles.closeBtn} onClick={onClose}>
            <X size={18} />
          </button>
        </div>

        {hasNoAnalysis ? (
          <div className={styles.emptyState}>
            <AlertTriangle size={32} className={styles.emptyIcon} />
            <p>No slow query analysis found.</p>
            <p className={styles.emptyHint}>
              Run analysis from the Performance tab.
            </p>
          </div>
        ) : (
          <>
            {/* Severity tabs (hidden when filtering by queryIds) */}
            {!queryIdsFilter?.length && (
              <div className={styles.tabs}>
                {SEVERITY_TABS.map((tab) => (
                  <button
                    key={tab}
                    type="button"
                    className={
                      activeTab === tab ? styles.tabActive : styles.tab
                    }
                    onClick={() => setActiveTab(tab)}
                  >
                    {tab} ({severityCounts[tab]})
                  </button>
                ))}
              </div>
            )}

            {/* Search */}
            <div className={styles.searchRow}>
              <Search size={14} className={styles.searchIcon} />
              <input
                type="text"
                placeholder="Search queries or tables..."
                value={searchTerm}
                onChange={(e) => setSearchTerm(e.target.value)}
                className={styles.searchInput}
              />
            </div>

            {/* Query list */}
            <div className={styles.queryList}>
              {filteredQueries.length === 0 ? (
                <div className={styles.noResults}>
                  No queries match the current filter.
                </div>
              ) : (
                filteredQueries.map((query, idx) => {
                  const key = getRowKey(query, idx);
                  const badge =
                    SEVERITY_BADGE_COLORS[
                      (query.severity || "").toUpperCase()
                    ] || SEVERITY_BADGE_COLORS.LOW;
                  const optimization = getOptimizationForQuery(query);
                  const hasNormalized = Boolean(query.normalizedQuery);
                  const remediationInfo = remediationMap.get(query.queryId);
                  const severityLabel = (query.severity || "LOW").toUpperCase();
                  const totalDbTime =
                    (query.avgExecutionTimeMs ?? 0) * (query.callCount ?? 1);

                  return (
                    <div
                      key={key}
                      className={styles.queryRow}
                      onClick={() => handleViewDetails(query)}
                      style={{ cursor: hasNormalized ? "pointer" : "default" }}
                    >
                      <div className={styles.queryRowTop}>
                        <span className={styles.badgeTooltipWrap}>
                          <span
                            className={styles.severityBadge}
                            style={{
                              backgroundColor: badge.bg,
                              color: badge.color,
                            }}
                          >
                            {severityLabel}
                          </span>
                          <span className={styles.badgeTooltip}>
                            <strong>{severityLabel} severity</strong>
                            <br />
                            Avg: {formatDuration(query.avgExecutionTimeMs)}
                            <br />
                            Calls: {query.callCount?.toLocaleString() ?? "—"}
                            <br />
                            Total DB time: {formatMs(totalDbTime)}
                            {query.rowsExamined != null && (
                              <>
                                <br />
                                Rows examined:{" "}
                                {query.rowsExamined.toLocaleString()}
                              </>
                            )}
                          </span>
                        </span>
                        {remediationInfo && (
                          <span className={styles.badgeTooltipWrap}>
                            <span
                              className={styles.severityBadge}
                              style={{
                                backgroundColor: "#ECFDF5",
                                color: "#059669",
                              }}
                            >
                              REMEDIATION
                            </span>
                            <span className={styles.badgeTooltip}>
                              <strong>Remediation candidate</strong>
                              <br />
                              Root cause: {remediationInfo.rootCause || "—"}
                              <br />
                              Expected savings:{" "}
                              {formatMs(remediationInfo.expectedSavingsMs)}
                              <br />
                              Improvement:{" "}
                              {remediationInfo.estimatedImprovementPct != null
                                ? `~${remediationInfo.estimatedImprovementPct}%`
                                : "—"}
                            </span>
                          </span>
                        )}
                        <code className={styles.queryText}>
                          {query.normalizedQuery ||
                            "(normalized query unavailable)"}
                        </code>
                        {optimization && !optimization.error && (
                          <CheckCircle
                            size={14}
                            className={styles.optimizedIcon}
                            title="AI optimization cached"
                          />
                        )}
                      </div>
                      <div className={styles.queryRowMeta}>
                        <span title="Average duration">
                          <Clock size={12} />{" "}
                          {formatDuration(query.avgExecutionTimeMs)}
                        </span>
                        <span title="Call count">
                          {query.callCount?.toLocaleString() ?? "—"} calls
                        </span>
                        <span title="Impact score">
                          <Zap size={12} /> {calculateImpact(query)}
                        </span>
                        {!hasNormalized && (
                          <span
                            className={styles.noDetailsHint}
                            title="Normalized query unavailable — view in Performance tab"
                          >
                            Details unavailable
                          </span>
                        )}
                      </div>
                    </div>
                  );
                })
              )}
            </div>
          </>
        )}

        {/* Monitoring-safe QueryDetailDialog */}
        {selectedQuery && (
          <QueryDetailDialog
            query={buildSafeQuery(selectedQuery)}
            open={Boolean(selectedQuery)}
            onOpenChange={(isOpen) => {
              if (!isOpen) setSelectedQuery(null);
            }}
            onOptimize={handleOptimize}
            optimization={buildSafeOptimization(
              getOptimizationForQuery(selectedQuery),
            )}
            isOptimizing={isOptimizingQuery(selectedQuery)}
            onBenchmarkCandidates={handleBenchmark}
            optimizationCandidates={optimizationCandidates}
            isBenchmarkingCandidates={isBenchmarking}
          />
        )}
      </div>
    </div>
  );
}
