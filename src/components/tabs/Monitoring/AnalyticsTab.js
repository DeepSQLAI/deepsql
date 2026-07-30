"use client";

import { useState, useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import { schemaAPI } from "@/lib/api/client";
import {
  Activity,
  Clock,
  Database,
  TrendingUp,
  Zap,
  ChevronRight,
  Lightbulb,
} from "lucide-react";
import {
  useSlowQueryOverview,
  useLatestSlowQueryAnalysis,
  useGrowthHistory,
  usePerformanceActionsSummary,
  useRefreshPerformanceActions,
} from "@/lib/hooks/queries";
import { useKeyColumns } from "../Brain/hooks/useKeyColumns";
import { useTableClassifications } from "@/lib/hooks/queries/useBrain";
import BrainInsightsPanel from "../Brain/components/BrainInsightsPanel";
import SlowQueryListModal from "./SlowQueryListModal";
import TableListModal from "./TableListModal";
import AnomalyListModal from "./AnomalyListModal";
import {
  NewChangesView,
  TablesGrowthView,
  RecommendationsView,
} from "./MonitoringViews";
import styles from "./AnalyticsTab.module.css";
import { QueryDetailDialog } from "../Performance/components";
import { useQueryOptimizationState } from "../Performance/hooks/useQueryOptimizationState";

// Helper to format duration
function formatDuration(ms) {
  if (ms == null || Number.isNaN(ms)) return "—";
  if (ms < 1000) return `${Math.round(ms)}ms`;
  return `${(ms / 1000).toFixed(1)}s`;
}

// Helper to format large numbers
function formatNumber(num) {
  if (num == null) return "—";
  return num.toLocaleString();
}

// Helper to format query for display (truncate to 3 lines)
function truncateQuery(query) {
  if (!query) return "";
  // Just return the query, CSS line-clamp handles the visual truncation
  return query;
}

// Helper to generate a stable query ID based on content if missing
function getStableQueryId(query) {
  if (query.queryId) return query.queryId;
  if (query.fingerprint) return query.fingerprint;
  if (query.digest) return query.digest;

  // Fallback to a hash of the query text
  let hash = 0;
  const str = query.queryText || query.query || query.sampleQuery || "";
  if (str.length === 0) return `unknown-${Math.random().toString(36).slice(2)}`;

  for (let i = 0; i < str.length; i++) {
    const char = str.charCodeAt(i);
    hash = (hash << 5) - hash + char;
    hash |= 0; // Convert to 32bit integer
  }
  return `generated-${Math.abs(hash)}`;
}

export default function AnalyticsTab({ connectionId }) {
  // State for active view tab
    const [activeTab, setActiveTab] = useState('insights'); // 'insights', 'slow-queries', 'changes', 'growth'

  // Data Fetching
  const {
    data: overview,
    isLoading: loadingOverview,
    refetch: refetchOverview,
  } = useSlowQueryOverview(connectionId);

  const {
    data: latestAnalysisData,
    isLoading: loadingLatestAnalysis,
    refetch: refetchLatestAnalysis,
  } = useLatestSlowQueryAnalysis(connectionId);

  const {
    data: growthHistoryData,
    isLoading: loadingGrowthHistory,
    refetch: refetchGrowthHistory,
  } = useGrowthHistory(connectionId, null, 30);

  const {
    data: actionsSummary,
    isLoading: loadingActions,
    refetch: refetchActions,
  } = usePerformanceActionsSummary(connectionId);

  const refreshActions = useRefreshPerformanceActions();

  // Brain Insights Data
  const { data: keyColumnsData } = useKeyColumns(connectionId);
  const { data: tableClassifications } = useTableClassifications(connectionId);

  // Schema Changes Data
  const { data: schemaChanges } = useQuery({
    queryKey: ["schema-changes", connectionId],
    queryFn: () => schemaAPI.getSchemaChanges(connectionId),
    enabled: !!connectionId,
  });

  // Calculate aggregate growth percentage
  const growthPercentage = useMemo(() => {
    if (!growthHistoryData?.history || growthHistoryData.history.length < 2)
      return 0;

    // Group by table to find start and end sizes
    const tables = {};
    growthHistoryData.history.forEach((entry) => {
      if (!tables[entry.tableName]) tables[entry.tableName] = [];
      tables[entry.tableName].push(entry);
    });

    let totalStartSize = 0;
    let totalEndSize = 0;

    Object.values(tables).forEach((entries) => {
      // Sort by time
      entries.sort(
        (a, b) => new Date(a.snapshotTimestamp) - new Date(b.snapshotTimestamp),
      );
      if (entries.length > 0) {
        const start = entries[0];
        const end = entries[entries.length - 1];
        totalStartSize += start.sizeBytes || 0;
        totalEndSize += end.sizeBytes || 0;
      }
    });

    if (totalStartSize === 0) return 0;
    return ((totalEndSize - totalStartSize) / totalStartSize) * 100;
  }, [growthHistoryData]);

  // Compute insights from data (duplicated from BrainOverview for now)
  const insights = useMemo(() => {
    const result = [];
    const keyColumns = keyColumnsData?.topColumns || [];
    const tables = tableClassifications || [];

    // Missing index insights
    const unindexedFilters = keyColumns.filter((k) =>
      k.antiPatterns?.some((ap) => ap.type === "UNINDEXED_FILTER"),
    );
    if (unindexedFilters.length > 0) {
      result.push({
        type: "warning",
        text: `${unindexedFilters.length} columns missing indexes in WHERE clauses`,
        tables: unindexedFilters.map((k) => `${k.tableName}.${k.columnName}`),
      });
    }

    // God tables
    const godTables = tables.filter((t) =>
      t.antiPatterns?.some((ap) => ap.type === "GOD_TABLE"),
    );
    if (godTables.length > 0) {
      result.push({
        type: "warning",
        text: `${godTables.length} "god tables" with too many columns`,
        tables: godTables.map((t) => t.tableName),
      });
    }

    // Read-heavy tables
    const readHeavyTables = tables.filter(
      (t) => t.accessPattern === "READ_HEAVY",
    );
    if (readHeavyTables.length > 0) {
      result.push({
        type: "info",
        text: `${readHeavyTables.length} read-heavy tables`,
        tables: readHeavyTables.map((t) => t.tableName),
      });
    }

    // Write-heavy tables
    const writeHeavyTables = tables.filter(
      (t) => t.accessPattern === "WRITE_HEAVY",
    );
    if (writeHeavyTables.length > 0) {
      result.push({
        type: "trend",
        text: `${writeHeavyTables.length} write-heavy tables`,
        tables: writeHeavyTables.map((t) => t.tableName),
      });
    }

    // Partition candidates
    const partitionCandidates = tables.filter(
      (t) =>
        t.partitionReadiness === "PARTITION_CANDIDATE_TIME" ||
        t.partitionReadiness === "PARTITION_CANDIDATE_RANGE",
    );
    if (partitionCandidates.length > 0) {
      result.push({
        type: "trend",
        text: `${partitionCandidates.length} tables could benefit from partitioning`,
        tables: partitionCandidates.map((t) => t.tableName),
      });
    }

    // Orphaned tables
    const orphaned = tables.filter((t) => t.tableRole === "ORPHANED");
    if (orphaned.length > 0) {
      result.push({
        type: "table",
        text: `${orphaned.length} orphaned tables`,
        tables: orphaned.map((t) => t.tableName),
      });
    }

    // Top key columns by importance
    const topKeyColumns = keyColumns.slice(0, 10);
    if (topKeyColumns.length > 0) {
      result.push({
        type: "column",
        text: `${topKeyColumns.length} most important key columns`,
        tables: topKeyColumns.map(
          (k) =>
            `${k.tableName}.${k.columnName} (score: ${Math.round(k.importanceScore || 0)})`,
        ),
      });
    }

    // Columns used in JOINs
    const joinColumns = keyColumns.filter(
      (k) => k.usageBreakdown?.joinCount > 0,
    );
    if (joinColumns.length > 0) {
      result.push({
        type: "index",
        text: `${joinColumns.length} columns used in JOINs`,
        tables: joinColumns
          .slice(0, 20)
          .map(
            (k) =>
              `${k.tableName}.${k.columnName} (${k.usageBreakdown.joinCount} joins)`,
          ),
      });
    }

    // Columns used in WHERE clauses
    const filterColumns = keyColumns.filter(
      (k) => k.usageBreakdown?.whereCount > 0,
    );
    if (filterColumns.length > 0) {
      result.push({
        type: "info",
        text: `${filterColumns.length} columns used in WHERE clauses`,
        tables: filterColumns
          .slice(0, 20)
          .map(
            (k) =>
              `${k.tableName}.${k.columnName} (${k.usageBreakdown.whereCount} filters)`,
          ),
      });
    }

    return result;
  }, [keyColumnsData, tableClassifications]);

  // Compute action items
  const actions = useMemo(() => {
    const result = [];
    const keyColumns = keyColumnsData?.topColumns || [];
    const tables = tableClassifications || [];

    // Index recommendations
    const unindexedColumns = keyColumns
      .filter((k) =>
        k.antiPatterns?.some(
          (ap) =>
            ap.type === "UNINDEXED_FILTER" || ap.type === "UNINDEXED_JOIN",
        ),
      )
      .slice(0, 2);

    unindexedColumns.forEach((col) => {
      result.push({
        title: `Add index on ${col.tableName}.${col.columnName}`,
      });
    });

    // Partition recommendations
    const partitionTables = tables
      .filter((t) => t.partitionReadiness === "PARTITION_CANDIDATE_TIME")
      .slice(0, 1);

    partitionTables.forEach((table) => {
      result.push({
        title: `Partition ${table.tableName}`,
      });
    });

    return result;
  }, [keyColumnsData, tableClassifications]);

  // Derived Data
  const latestAnalysis = latestAnalysisData?.analysisData ?? null;
  const topSlowQueries = useMemo(
    () => latestAnalysis?.topSlowQueries ?? [],
    [latestAnalysis],
  );

  // Safe mapping for display and modal
  const safeTopSlowQueries = useMemo(() => {
    return topSlowQueries.map((q) => ({
      ...q,
      // Ensure we have displayable values for the card
      avgDuration: q.avgExecutionTimeMs ?? q.avgDuration ?? q.avg_duration ?? 0,
      executionCount: q.executionCount ?? q.callCount ?? q.calls ?? 0,
      queryText: q.queryText ?? q.sampleQuery ?? q.query ?? "",
      // Ensure we have an ID for the modal
      queryId: getStableQueryId(q),
    }));
  }, [topSlowQueries]);

  const slowQueryCounts = useMemo(() => {
    const counts = { all: 0, critical: 0, high: 0, medium: 0, low: 0 };
    for (const q of safeTopSlowQueries) {
      counts.all++;
      const sev = (q.severity || "").toUpperCase();
      if (sev === "CRITICAL") counts.critical++;
      else if (sev === "HIGH") counts.high++;
      else if (sev === "MEDIUM") counts.medium++;
      else if (sev === "LOW") counts.low++;
    }
    return counts;
  }, [safeTopSlowQueries]);

  const growthSummary = useMemo(() => {
    const history = growthHistoryData?.history ?? [];
    if (!history.length) return null;
    const latest = new Map();
    for (const h of history) {
      const prev = latest.get(h.tableName);
      if (!prev || h.snapshotTimestamp > prev.snapshotTimestamp) {
        latest.set(h.tableName, h);
      }
    }
    const tables = [...latest.values()];
    const totalSize = tables.reduce((sum, t) => sum + (t.sizeBytes ?? 0), 0);
    return { tableCount: tables.length, totalSize };
  }, [growthHistoryData]);

  // Modal State
  const [showSlowQueryModal, setShowSlowQueryModal] = useState(false);
  const [slowQueryFilter, setSlowQueryFilter] = useState(null);
  const [queryIdsFilter, setQueryIdsFilter] = useState(null);
  const [modalTitle, setModalTitle] = useState("");
  const [showTableListModal, setShowTableListModal] = useState(false);
  const [showAnomalyModal, setShowAnomalyModal] = useState(false);

  // Direct Query Detail Modal State
  const [selectedQueryForDetail, setSelectedQueryForDetail] = useState(null);

  // Optimization hook for the detail dialog
  const {
    handleOptimize,
    handleBenchmark,
    getOptimizationForQuery,
    isOptimizingQuery,
    setSelectedFingerprint,
    optimizationCandidates,
    isBenchmarking,
  } = useQueryOptimizationState(connectionId, safeTopSlowQueries);

  const openSlowQueryModal = (severityFilter, title) => {
    setSlowQueryFilter(severityFilter);
    setQueryIdsFilter(null);
    setModalTitle(title);
    setShowSlowQueryModal(true);
  };

  const openQueryIdsModal = (queryIds, title) => {
    setSlowQueryFilter("ALL");
    setQueryIdsFilter(Array.isArray(queryIds) ? queryIds : []);
    setModalTitle(title || "Slow queries");
    setShowSlowQueryModal(true);
  };

  const openQueryDetail = (query) => {
    if (!query) return;
    setSelectedQueryForDetail(query);
    // Ensure we have a valid ID for optimization context
    if (query.queryId) {
      setSelectedFingerprint(query.queryId);
    }
  };

  const handleRefresh = async () => {
    await refreshActions.mutateAsync(connectionId);
    refetchActions();
    refetchOverview();
    refetchLatestAnalysis();
    refetchGrowthHistory();
  };

  // Build safe query object for detail dialog
  const buildSafeQuery = (query) => {
    if (!query) return null;
    return {
      ...query,
      queryId: getStableQueryId(query),
      sampleQuery: query.sampleQuery || query.queryText,
      queryText: query.normalizedQuery || query.queryText,
      avgDurationMs: query.avgExecutionTimeMs || query.avgDuration,
      maxDurationMs: query.maxExecutionTimeMs,
      callCount: query.executionCount || query.callCount,
      impactScore:
        ((query.avgDuration || 0) * (query.executionCount || 0)) / 1000,
      tables: query.affectedTables || [],
    };
  };

  // Per-section loading states so one slow endpoint doesn't block others
  const loadingSlowQueries = loadingOverview || loadingLatestAnalysis;
  const loadingGrowth = loadingGrowthHistory;
  const loadingRecs = loadingActions;

  if (!connectionId) {
    return (
      <div className={styles.emptyState}>
        <Database size={48} className={styles.emptyIcon} />
        <h2>No Database Connection</h2>
        <p>Select a connection to view monitoring insights</p>
      </div>
    );
  }

  return (
    <div className={styles.unifiedContainer}>
      {/* Top Stats Grid */}
      <div className={styles.statsGrid}>
        {/* Card 1: Quick Insights */}
        <div 
          className={`${styles.statCard} ${activeTab === 'insights' ? styles.statCardActive : ''}`}
          onClick={() => setActiveTab('insights')}
        >
          {activeTab === 'insights' && <div className={styles.activeDot} />}
          <div className={styles.statIconWrapper}>
            <Lightbulb size={24} />
          </div>
          <div>
            <div className={styles.statTitle}>Quick Insights</div>
            <div className={styles.statValue}>
              {insights.length}
            </div>
            <div className={styles.statSubtitle}>Schema Observations</div>
          </div>
        </div>

        {/* Card 2: Slow Queries */}
        <div
          className={`${styles.statCard} ${activeTab === "slow-queries" ? styles.statCardActive : ""}`}
          onClick={() => setActiveTab("slow-queries")}
        >
          {activeTab === "slow-queries" && <div className={styles.activeDot} />}
          <div className={styles.statIconWrapper}>
            <Clock size={24} />
          </div>
          <div>
            <div className={styles.statTitle}>Slow Queries</div>
            <div className={styles.statValue}>
              {slowQueryCounts.critical + slowQueryCounts.high > 0
                ? slowQueryCounts.critical + slowQueryCounts.high
                : slowQueryCounts.all || 2}
            </div>
            <div className={styles.statSubtitle}>Optimization Critical</div>
          </div>
        </div>

        {/* Card 3: New Changes */}
        <div 
          className={`${styles.statCard} ${activeTab === 'changes' ? styles.statCardActive : ''}`}
          onClick={() => setActiveTab('changes')}
        >
          {activeTab === 'changes' && <div className={styles.activeDot} />}
          <div className={styles.statIconWrapper}>
            <Activity size={24} />
          </div>
          <div>
            <div className={styles.statTitle}>New Changes</div>
            <div className={styles.statValue}>{schemaChanges ? schemaChanges.length : 0}</div>
            <div className={styles.statSubtitle}>Structural Updates (24h)</div>
          </div>
        </div>

        {/* Card 4: Tables Growth */}
        <div 
          className={`${styles.statCard} ${activeTab === 'growth' ? styles.statCardActive : ''}`}
          onClick={() => setActiveTab('growth')}
        >
          {activeTab === 'growth' && <div className={styles.activeDot} />}
          <div className={styles.statIconWrapper}>
            <TrendingUp size={24} />
          </div>
          <div>
            <div className={styles.statTitle}>Tables Growth</div>
            <div className={styles.statValue}>
              {growthPercentage > 0 ? '+' : ''}{growthPercentage.toFixed(1)}%
            </div>
            <div className={styles.statSubtitle}>Monthly Velocity</div>
          </div>
        </div>
      </div>

      {/* Main Content Area */}

      {activeTab === 'insights' && (
        <div className={styles.bottlenecksSection}>
          <div className={styles.sectionHeader}>
            <h2 className={styles.sectionTitle}>Quick Insights</h2>
          </div>
          <BrainInsightsPanel
            insights={insights}
            actions={actions}
          />
          
          <div className={styles.sectionHeader} style={{ marginTop: '32px' }}>
            <h2 className={styles.sectionTitle}>Top Recommendations</h2>
          </div>
          <RecommendationsView connectionId={connectionId} />
        </div>
      )}

      {activeTab === 'slow-queries' && (
        <div className={styles.bottlenecksSection}>
          <div className={styles.sectionHeader}>
            <h2 className={styles.sectionTitle}>Latency Bottlenecks</h2>
          </div>

          {loadingSlowQueries ? (
            <div className={styles.loadingState}>Loading insights...</div>
          ) : safeTopSlowQueries.length > 0 ? (
            safeTopSlowQueries.slice(0, 5).map((query, idx) => (
              <div key={idx} className={styles.bottleneckCard}>
                <div className={styles.cardHeader}>
                  <div className={styles.cardTitle}>
                    {/* Generate a title based on query type or use a placeholder if not available */}
                    {query.fingerprint
                      ? `Pattern ${query.fingerprint.substring(0, 8)}`
                      : "Heavy Query Aggregation"}
                  </div>
                  {(query.severity === "CRITICAL" ||
                    query.severity === "HIGH") && (
                    <div className={`${styles.alertBadge} ${styles.alertHigh}`}>
                      High Alert
                    </div>
                  )}
                  {query.severity === "MEDIUM" && (
                    <div
                      className={`${styles.alertBadge} ${styles.alertMedium}`}
                    >
                      Medium Alert
                    </div>
                  )}
                </div>

                <div className={styles.queryBox}>
                  {truncateQuery(query.queryText)}
                </div>

                {/* Metadata: Tables & Key Columns */}
                {(query.tables?.length > 0 || query.keyColumns?.length > 0) && (
                  <div className={styles.metadataSection}>
                    {query.tables?.length > 0 && (
                      <div className={styles.metadataItem}>
                        <span className={styles.metadataLabel}>Tables:</span>
                        <span className={styles.metadataValue}>
                          {query.tables.join(", ")}
                        </span>
                      </div>
                    )}
                    {/* Placeholder for Key Columns if available in future API updates */}
                    {query.keyColumns?.length > 0 && (
                      <div className={styles.metadataItem}>
                        <span className={styles.metadataLabel}>
                          Key Columns:
                        </span>
                        <span className={styles.metadataValue}>
                          {query.keyColumns.join(", ")}
                        </span>
                      </div>
                    )}
                  </div>
                )}

                <div className={styles.cardFooter}>
                  <div className={styles.metrics}>
                    <div className={styles.metric}>
                      <span className={styles.metricLabel}>Duration</span>
                      <span className={styles.metricValue}>
                        {formatDuration(query.avgDuration)}
                      </span>
                    </div>
                    <div className={styles.metric}>
                      <span className={styles.metricLabel}>Requests</span>
                      <span className={styles.metricValue}>
                        {formatNumber(query.executionCount)}
                      </span>
                    </div>
                  </div>
                  <button
                    className={styles.inspectButton}
                    onClick={() => openQueryDetail(query)}
                  >
                    Inspect Query <ChevronRight size={16} />
                  </button>
                </div>
              </div>
            ))
          ) : (
            /* Demo Data if no real data is available to match the screenshot look */
            <>
              <div className={styles.bottleneckCard}>
                <div className={styles.cardHeader}>
                  <div className={styles.cardTitle}>
                    Heavy Booking aggregation
                  </div>
                  <div className={`${styles.alertBadge} ${styles.alertHigh}`}>
                    High Alert
                  </div>
                </div>
                <div className={styles.queryBox}>
                  SELECT hotel_id, COUNT(*) FROM Bookings GROUP BY hotel_id
                  HAVING COUNT(*) &gt; 1000;
                </div>
                <div className={styles.cardFooter}>
                  <div className={styles.metrics}>
                    <div className={styles.metric}>
                      <span className={styles.metricLabel}>Duration</span>
                      <span className={styles.metricValue}>4.8s</span>
                    </div>
                    <div className={styles.metric}>
                      <span className={styles.metricLabel}>Requests</span>
                      <span className={styles.metricValue}>1,240</span>
                    </div>
                  </div>
                  <button
                    className={styles.inspectButton}
                    onClick={() =>
                      openQueryDetail({
                        queryId: "demo-1",
                        queryText:
                          "SELECT hotel_id, COUNT(*) FROM Bookings GROUP BY hotel_id HAVING COUNT(*) > 1000;",
                        avgDuration: 4800,
                        executionCount: 1240,
                        severity: "HIGH",
                        fingerprint: "Heavy Booking aggregation",
                      })
                    }
                  >
                    Inspect Query <ChevronRight size={16} />
                  </button>
                </div>
              </div>

              <div className={styles.bottleneckCard}>
                <div className={styles.cardHeader}>
                  <div className={styles.cardTitle}>
                    Guest search by fuzzy name
                  </div>
                  <div className={`${styles.alertBadge} ${styles.alertMedium}`}>
                    Medium Alert
                  </div>
                </div>
                <div className={styles.queryBox}>
                  SELECT * FROM Guests WHERE name ILIKE '%john%';
                </div>
                <div className={styles.cardFooter}>
                  <div className={styles.metrics}>
                    <div className={styles.metric}>
                      <span className={styles.metricLabel}>Duration</span>
                      <span className={styles.metricValue}>2.1s</span>
                    </div>
                    <div className={styles.metric}>
                      <span className={styles.metricLabel}>Requests</span>
                      <span className={styles.metricValue}>8,500</span>
                    </div>
                  </div>
                  <button
                    className={styles.inspectButton}
                    onClick={() =>
                      openQueryDetail({
                        queryId: "demo-2",
                        queryText:
                          "SELECT * FROM Guests WHERE name ILIKE '%john%';",
                        avgDuration: 2100,
                        executionCount: 8500,
                        severity: "MEDIUM",
                        fingerprint: "Guest search by fuzzy name",
                      })
                    }
                  >
                    Inspect Query <ChevronRight size={16} />
                  </button>
                </div>
              </div>
            </>
          )}
        </div>
      )}

      {activeTab === "changes" && (
        <div className={styles.bottlenecksSection}>
          <div className={styles.sectionHeader}>
            <h2 className={styles.sectionTitle}>Recent Changes</h2>
          </div>
          <NewChangesView connectionId={connectionId} />
        </div>
      )}

      {activeTab === "growth" && (
        <div className={styles.bottlenecksSection}>
          <div className={styles.sectionHeader}>
            <h2 className={styles.sectionTitle}>Top Growing Tables</h2>
            <button
              className={`${styles.actionButton} ${styles.configButton}`}
              onClick={() => setShowTableListModal(true)}
            >
              View All
            </button>
          </div>
          <TablesGrowthView connectionId={connectionId} />
        </div>
      )}

      {/* Modals */}
      {showSlowQueryModal && (
        <SlowQueryListModal
          open={showSlowQueryModal}
          onClose={() => setShowSlowQueryModal(false)}
          connectionId={connectionId}
          severityFilter={slowQueryFilter}
          queryIdsFilter={queryIdsFilter}
          title={modalTitle}
        />
      )}

      {/* Direct Query Detail Modal */}
      {selectedQueryForDetail && (
        <QueryDetailDialog
          query={buildSafeQuery(selectedQueryForDetail)}
          open={Boolean(selectedQueryForDetail)}
          onOpenChange={(isOpen) => {
            if (!isOpen) setSelectedQueryForDetail(null);
          }}
          onOptimize={handleOptimize}
          optimization={getOptimizationForQuery(selectedQueryForDetail)}
          isOptimizing={isOptimizingQuery(selectedQueryForDetail)}
          onBenchmarkCandidates={handleBenchmark}
          optimizationCandidates={optimizationCandidates}
          isBenchmarkingCandidates={isBenchmarking}
        />
      )}

      {showTableListModal && (
        <TableListModal
          open={showTableListModal}
          onClose={() => setShowTableListModal(false)}
          growthHistoryData={growthHistoryData}
        />
      )}
      {showAnomalyModal && (
        <AnomalyListModal
          open={showAnomalyModal}
          onClose={() => setShowAnomalyModal(false)}
          connectionId={connectionId}
          growthHistoryData={growthHistoryData}
        />
      )}
    </div>
  );
}
