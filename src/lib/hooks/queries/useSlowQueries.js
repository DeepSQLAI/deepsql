/**
 * TanStack Query hooks for Slow Queries API
 */

import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { slowQueriesAPI, slowLogSourceAPI } from "@/lib/api/client";
import { queryKeys } from "@/lib/queryKeys";

// ==================== History ====================

/**
 * Fetch slow query history
 */
export function useSlowQueryHistory(connectionId) {
  return useQuery({
    queryKey: queryKeys.slowQueries.history(connectionId),
    queryFn: () => slowQueriesAPI.getHistory(connectionId),
    enabled: Boolean(connectionId),
  });
}

/**
 * Fetch latest slow query analysis
 */
export function useLatestSlowQueryAnalysis(connectionId) {
  return useQuery({
    queryKey: queryKeys.slowQueries.latest(connectionId),
    queryFn: () => slowQueriesAPI.getLatestAnalysis(connectionId),
    enabled: Boolean(connectionId),
    staleTime: 2 * 60 * 1000,
  });
}

/**
 * Fetch a specific history item
 */
export function useSlowQueryHistoryDetail(id) {
  return useQuery({
    queryKey: queryKeys.slowQueries.historyDetail(id),
    queryFn: () => slowQueriesAPI.getHistoryById(id),
    enabled: Boolean(id),
  });
}

/**
 * Analyze slow queries
 */
export function useAnalyzeSlowQueries() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: ({ connectionId, threshold = 100, limit = 10 }) =>
      slowQueriesAPI.analyzeSlowQueries(connectionId, threshold, limit),
    onSuccess: (_data, { connectionId }) => {
      queryClient.invalidateQueries({
        queryKey: queryKeys.slowQueries.all(connectionId),
      });
    },
  });
}

/**
 * Delete slow query history
 */
export function useDeleteSlowQueryHistory() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: slowQueriesAPI.deleteHistory,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["slowQueries"] });
    },
  });
}

// ==================== Optimization ====================

/**
 * Optimize a single query
 */
export function useOptimizeQuery() {
  return useMutation({
    mutationFn: slowQueriesAPI.optimizeQuery,
  });
}

/**
 * Batch optimize queries
 */
export function useBatchOptimize() {
  return useMutation({
    mutationFn: ({ connectionId, limit = 5 }) =>
      slowQueriesAPI.batchOptimize(connectionId, limit),
  });
}

// ==================== Optimization Cache ====================

/**
 * Fetch cached optimization for a single query fingerprint
 */
export function useCachedOptimization(connectionId, queryFingerprint) {
  return useQuery({
    queryKey: queryKeys.slowQueries.cachedOptimization(
      connectionId,
      queryFingerprint,
    ),
    queryFn: () =>
      slowQueriesAPI.getCachedOptimization(connectionId, queryFingerprint),
    enabled: Boolean(connectionId) && Boolean(queryFingerprint),
    staleTime: 5 * 60 * 1000, // 5 minutes
  });
}

/**
 * Fetch cached optimizations for multiple query fingerprints
 */
export function useCachedOptimizations(connectionId, fingerprints) {
  return useQuery({
    queryKey: queryKeys.slowQueries.cachedOptimizations(
      connectionId,
      fingerprints,
    ),
    queryFn: () =>
      slowQueriesAPI.getCachedOptimizations(connectionId, fingerprints),
    enabled:
      Boolean(connectionId) &&
      Array.isArray(fingerprints) &&
      fingerprints.length > 0,
    staleTime: 5 * 60 * 1000, // 5 minutes
  });
}

/**
 * Fetch optimization cache stats
 */
export function useOptimizationCacheStats(connectionId) {
  return useQuery({
    queryKey: queryKeys.slowQueries.cacheStats(connectionId),
    queryFn: () => slowQueriesAPI.getOptimizationCacheStats(connectionId),
    enabled: Boolean(connectionId),
  });
}

/**
 * Fetch optimization candidates for a query fingerprint
 */
export function useOptimizationCandidates(connectionId, queryFingerprint) {
  return useQuery({
    queryKey: queryKeys.slowQueries.candidates(connectionId, queryFingerprint),
    queryFn: () =>
      slowQueriesAPI.getOptimizationCandidates(connectionId, queryFingerprint),
    enabled: Boolean(connectionId) && Boolean(queryFingerprint),
    staleTime: 5 * 60 * 1000,
  });
}

/**
 * Benchmark optimization candidates (manual)
 */
export function useBenchmarkCandidates() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: ({ connectionId, queryFingerprint, runs, timeoutMs }) =>
      slowQueriesAPI.benchmarkOptimizationCandidates(
        connectionId,
        queryFingerprint,
        { runs, timeoutMs },
      ),
    onSuccess: (data, { connectionId, queryFingerprint }) => {
      queryClient.setQueryData(
        queryKeys.slowQueries.candidates(connectionId, queryFingerprint),
        data,
      );
    },
  });
}

/**
 * Clear optimization cache for a connection
 */
export function useClearOptimizationCache() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (connectionId) =>
      slowQueriesAPI.clearOptimizationCache(connectionId),
    onSuccess: (_, connectionId) => {
      // Invalidate all cached optimization queries for this connection
      queryClient.invalidateQueries({
        predicate: (query) =>
          query.queryKey[0] === "slowQueries" &&
          query.queryKey[1] === "cachedOptimization" &&
          query.queryKey[2] === connectionId,
      });
    },
  });
}

// ==================== Alerts ====================

/**
 * Fetch alert summary
 */
export function useSlowQueryAlerts(connectionId) {
  return useQuery({
    queryKey: queryKeys.slowQueries.alerts(connectionId),
    queryFn: () => slowQueriesAPI.getAlertSummary(connectionId),
    enabled: Boolean(connectionId),
  });
}

/**
 * Acknowledge an alert
 */
export function useAcknowledgeSlowQueryAlert() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: ({ alertId, userId }) =>
      slowQueriesAPI.acknowledgeAlert(alertId, userId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["slowQueries"] });
    },
  });
}

/**
 * Acknowledge all alerts for a connection
 */
export function useAcknowledgeAllSlowQueryAlerts() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: ({ connectionId, userId }) =>
      slowQueriesAPI.acknowledgeAllAlerts(connectionId, userId),
    onSuccess: (_data, { connectionId }) => {
      queryClient.invalidateQueries({
        queryKey: queryKeys.slowQueries.alerts(connectionId),
      });
    },
  });
}

/**
 * Process alerts for a connection
 */
export function useProcessSlowQueryAlerts() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: ({ connectionId, config }) =>
      slowQueriesAPI.processAlerts(connectionId, config),
    onSuccess: (_data, { connectionId }) => {
      queryClient.invalidateQueries({
        queryKey: queryKeys.slowQueries.alerts(connectionId),
      });
    },
  });
}

// ==================== Comparison ====================

/**
 * Compare two analyses
 */
export function useCompareAnalyses() {
  return useMutation({
    mutationFn: ({ historyId1, historyId2 }) =>
      slowQueriesAPI.compareAnalyses(historyId1, historyId2),
  });
}

// ==================== Dashboard ====================

/**
 * Fetch all dashboard widgets
 */
export function useSlowQueryDashboard(connectionId) {
  return useQuery({
    queryKey: queryKeys.slowQueries.dashboard(connectionId),
    queryFn: () => slowQueriesAPI.getDashboardWidgets(connectionId),
    enabled: Boolean(connectionId),
  });
}

/**
 * Fetch overview widget
 */
export function useSlowQueryOverview(connectionId) {
  return useQuery({
    queryKey: queryKeys.slowQueries.overview(connectionId),
    queryFn: () => slowQueriesAPI.getOverviewWidget(connectionId),
    enabled: Boolean(connectionId),
    staleTime: 2 * 60 * 1000,
  });
}

/**
 * Fetch trend widget
 */
export function useSlowQueryTrend(connectionId) {
  return useQuery({
    queryKey: queryKeys.slowQueries.trend(connectionId),
    queryFn: () => slowQueriesAPI.getTrendWidget(connectionId),
    enabled: Boolean(connectionId),
  });
}

// ==================== Fingerprints ====================

/**
 * Fetch fingerprint summary
 */
export function useFingerprintSummary(connectionId) {
  return useQuery({
    queryKey: queryKeys.slowQueries.fingerprints(connectionId),
    queryFn: () => slowQueriesAPI.getFingerprintSummary(connectionId),
    enabled: Boolean(connectionId),
  });
}

/**
 * Fetch fingerprints with filters
 */
export function useFingerprints(connectionId, options = {}) {
  return useQuery({
    queryKey: queryKeys.slowQueries.fingerprintsList(connectionId, options),
    queryFn: () => slowQueriesAPI.getFingerprints(connectionId, options),
    enabled: Boolean(connectionId),
  });
}

/**
 * Fetch fingerprint trend
 */
export function useFingerprintTrend(fingerprintId) {
  return useQuery({
    queryKey: queryKeys.slowQueries.fingerprintTrend(fingerprintId),
    queryFn: () => slowQueriesAPI.getFingerprintTrend(fingerprintId),
    enabled: Boolean(fingerprintId),
  });
}

/**
 * Reset fingerprint baseline
 */
export function useResetFingerprintBaseline() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: slowQueriesAPI.resetFingerprintBaseline,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["slowQueries"] });
    },
  });
}

/**
 * Process fingerprints
 */
export function useProcessFingerprints() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (connectionId) =>
      slowQueriesAPI.processFingerprints(connectionId),
    onSuccess: (_data, connectionId) => {
      queryClient.invalidateQueries({
        queryKey: queryKeys.slowQueries.fingerprints(connectionId),
      });
    },
  });
}

// ==================== Explain Plan ====================

/**
 * Get explain plan for a query
 */
export function useGetExplainPlan() {
  return useMutation({
    mutationFn: ({ connectionId, queryText, analyze = false }) =>
      slowQueriesAPI.getExplainPlan(connectionId, queryText, analyze),
  });
}

/**
 * Fetch critical query explains
 */
export function useCriticalQueryExplains(connectionId, limit = 5) {
  return useQuery({
    queryKey: queryKeys.slowQueries.criticalExplains(connectionId),
    queryFn: () => slowQueriesAPI.getCriticalQueryExplains(connectionId, limit),
    enabled: Boolean(connectionId),
  });
}

// ==================== Slow Log Source ====================

/**
 * Fetch slow log source config
 */
export function useSlowLogSourceConfig(connectionId) {
  return useQuery({
    queryKey: queryKeys.slowLogSource.config(connectionId),
    queryFn: () => slowLogSourceAPI.getConfig(connectionId),
    enabled: Boolean(connectionId),
    staleTime: 0, // Always refetch on mount to ensure fresh data
  });
}

/**
 * Save slow log source config
 */
export function useSaveSlowLogSourceConfig() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: slowLogSourceAPI.saveConfig,
    onSuccess: (data) => {
      if (data?.connectionId) {
        queryClient.invalidateQueries({
          queryKey: queryKeys.slowLogSource.config(data.connectionId),
        });
      }
    },
  });
}

/**
 * Ingest slow logs now
 */
export function useIngestSlowLogs() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: slowLogSourceAPI.ingestNow,
    onSuccess: (_data, payload) => {
      if (payload?.connectionId) {
        queryClient.invalidateQueries({
          queryKey: queryKeys.slowQueries.all(payload.connectionId),
        });
      }
    },
  });
}

/**
 * Start async ingestion
 */
export function useStartAsyncIngestion() {
  return useMutation({
    mutationFn: slowLogSourceAPI.startAsyncIngestion,
  });
}

/**
 * Fetch ingestion job status
 */
export function useIngestionJobStatus(
  jobId,
  { enabled = true, interval = 2000 } = {},
) {
  return useQuery({
    queryKey: queryKeys.slowLogSource.jobStatus(jobId),
    queryFn: () => slowLogSourceAPI.getJobStatus(jobId),
    enabled: Boolean(jobId) && enabled,
    refetchInterval: interval,
    refetchIntervalInBackground: false,
  });
}

/**
 * Fetch active job for a connection
 * Always refetches on mount to detect running jobs after tab switch
 */
export function useActiveIngestionJob(connectionId) {
  return useQuery({
    queryKey: queryKeys.slowLogSource.activeJob(connectionId),
    queryFn: () => slowLogSourceAPI.getActiveJob(connectionId),
    enabled: Boolean(connectionId),
    refetchOnMount: "always",
    staleTime: 0, // Always consider data stale to ensure fresh fetch
  });
}

/**
 * Cancel an ingestion job
 */
export function useCancelIngestionJob() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: slowLogSourceAPI.cancelJob,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["slowLogSource"] });
    },
  });
}

// ==================== Key Customers ====================

export function useKeyCustomers(connectionId, options = {}) {
  return useQuery({
    queryKey: queryKeys.slowQueries.keyCustomers(connectionId, options),
    queryFn: () => slowQueriesAPI.getKeyCustomers(connectionId, options),
    enabled: Boolean(connectionId),
    staleTime: 5 * 60 * 1000,
  });
}

// ==================== Advanced Slow-Query Insights ====================

export function useSlowQueryInsights(connectionId, options = {}) {
  return useQuery({
    queryKey: queryKeys.slowQueries.insights(connectionId, options),
    queryFn: () => slowQueriesAPI.getInsights(connectionId, options),
    enabled: Boolean(connectionId),
    staleTime: 2 * 60 * 1000,
  });
}

export function useRemediationInsights(connectionId, options = {}) {
  return useQuery({
    queryKey: queryKeys.slowQueries.insightsRemediation(connectionId, options),
    queryFn: () => slowQueriesAPI.getRemediationInsights(connectionId, options),
    enabled: Boolean(connectionId),
    staleTime: 2 * 60 * 1000,
  });
}

export function useHotspotInsights(connectionId, options = {}) {
  return useQuery({
    queryKey: queryKeys.slowQueries.insightsHotspots(connectionId, options),
    queryFn: () => slowQueriesAPI.getHotspotInsights(connectionId, options),
    enabled: Boolean(connectionId),
    staleTime: 2 * 60 * 1000,
  });
}

export function useSkewInsights(connectionId, options = {}) {
  return useQuery({
    queryKey: queryKeys.slowQueries.insightsSkew(connectionId, options),
    queryFn: () => slowQueriesAPI.getSkewInsights(connectionId, options),
    enabled: Boolean(connectionId),
    staleTime: 2 * 60 * 1000,
  });
}

export function useTailRiskInsights(connectionId, options = {}) {
  return useQuery({
    queryKey: queryKeys.slowQueries.insightsTailRisk(connectionId, options),
    queryFn: () => slowQueriesAPI.getTailRiskInsights(connectionId, options),
    enabled: Boolean(connectionId),
    staleTime: 2 * 60 * 1000,
  });
}

export function usePlanDriftInsights(connectionId, options = {}) {
  return useQuery({
    queryKey: queryKeys.slowQueries.insightsPlanDrift(connectionId, options),
    queryFn: () => slowQueriesAPI.getPlanDriftInsights(connectionId, options),
    enabled: Boolean(connectionId),
    staleTime: 2 * 60 * 1000,
  });
}
