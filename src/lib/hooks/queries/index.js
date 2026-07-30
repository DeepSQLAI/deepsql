/**
 * TanStack Query Domain Hooks - Re-exports
 *
 * Import from '@/lib/hooks/queries' for all domain-specific hooks.
 */

// Connections
export {
  useConnections,
  useTestConnection,
  useSaveConnection,
  useUpdateConnection,
  useDeleteConnection,
} from "./useConnections";

export {
  useCompanyKnowledge,
  useCreateCompanyKnowledge,
  useUpdateCompanyKnowledge,
  useDeleteCompanyKnowledge,
} from "./useCompanyKnowledge";

// Code Scan (Company Knowledge enrichment)
export {
  useCodeScanSources,
  useCreateCodeScanSource,
  useDeleteCodeScanSource,
  useStartCodeScan,
  useCodeScanJob,
  useCodeScanJobStream,
  useCodeScanSuggestions,
  useAllCodeScanSuggestions,
  useDecideCodeScanSuggestion,
  useBulkDecideCodeScanSuggestions,
  useUpdateCodeScanFocus,
  useSchemaAmbiguity,
} from "./useCodeScan";

// Brain
export {
  // Understanding
  useBrainUnderstanding,
  useProfileConnection,
  useRescanSchema,
  // Tasks
  useBrainTasks,
  useCreateBrainTask,
  useUpdateTaskStatus,
  // Notes
  useBrainNotes,
  useCreateBrainNote,
  useUpdateBrainNote,
  useDeleteBrainNote,
  // Key Columns
  useKeyColumns,
  useAnalyzeKeyColumns,
  useAcknowledgeAntiPattern,
  // Schema Classification
  useSchemaClassification,
  useAnalyzeSchemaClassification,
  useTableClassifications,
  useFactTables,
  useDimensionTables,
  // Query Anti-Patterns
  useQueryAntiPatterns,
  useAnalyzeQueryAntiPatterns,
  // Workload Intelligence
  useWorkloadProfile,
  useWorkloadStatus,
  // Cardinality Accuracy
  useCardinalityAccuracy,
  // Scalability Simulation
  useScalabilitySimulations,
  useLatestSimulation,
  useRunScalabilitySimulation,
  useTablePredictions,
  useHighRiskTables,
  // Brain Score
  useBrainScore,
  useBrainScoreHistory,
  useCalculateBrainScore,
} from "./useBrain";

// Training
export {
  useTrainingStatus,
  useStartSchemaTraining,
  useCancelSchemaTraining,
  useTrainingHistory,
  useTrainingStats,
  useQueueMetrics,
  useAddDocumentation,
  getTrainingStreamUrl,
} from "./useTraining";

// Active Queries
export {
  useActiveQueries,
  useLatestQueries,
  useActiveQueryStatistics,
  useActiveQueryFilterOptions,
  useCaptureQueries,
  useKillQuery,
} from "./useActiveQueries";

// Slow Queries
export {
  // History
  useSlowQueryHistory,
  useLatestSlowQueryAnalysis,
  useSlowQueryHistoryDetail,
  useAnalyzeSlowQueries,
  useDeleteSlowQueryHistory,
  // Optimization
  useOptimizeQuery,
  useBatchOptimize,
  // Optimization Cache
  useCachedOptimization,
  useCachedOptimizations,
  useOptimizationCacheStats,
  useClearOptimizationCache,
  useOptimizationCandidates,
  useBenchmarkCandidates,
  // Alerts
  useSlowQueryAlerts,
  useAcknowledgeSlowQueryAlert,
  useAcknowledgeAllSlowQueryAlerts,
  useProcessSlowQueryAlerts,
  // Comparison
  useCompareAnalyses,
  // Dashboard
  useSlowQueryDashboard,
  useSlowQueryOverview,
  useSlowQueryTrend,
  // Fingerprints
  useFingerprintSummary,
  useFingerprints,
  useFingerprintTrend,
  useResetFingerprintBaseline,
  useProcessFingerprints,
  // Explain Plan
  useGetExplainPlan,
  useCriticalQueryExplains,
  // Slow Log Source
  useSlowLogSourceConfig,
  useSaveSlowLogSourceConfig,
  useIngestSlowLogs,
  useStartAsyncIngestion,
  useIngestionJobStatus,
  useActiveIngestionJob,
  useCancelIngestionJob,
  // Key Customers
  useKeyCustomers,
  // Advanced Slow-Query Insights
  useSlowQueryInsights,
  useRemediationInsights,
  useHotspotInsights,
  useSkewInsights,
  useTailRiskInsights,
  usePlanDriftInsights,
} from "./useSlowQueries";

// Growth Monitoring
export {
  useGrowthHistory,
  useGrowthAnomalies,
  useAcknowledgeGrowthAnomaly,
  useGrowthConfiguration,
  useSaveGrowthConfiguration,
  useGrowthTrends,
  useManualCapture,
  useManualCleanup,
  useSchemaChanges,
} from "./useGrowthMonitoring";

// Playbooks
export {
  usePlaybooks,
  usePlaybook,
  useCreatePlaybook,
  useUpdatePlaybook,
  useDeletePlaybook,
  useTogglePlaybook,
  useExecutePlaybook,
  usePlaybookRunHistory,
  useCancelPlaybookRun,
  usePlaybookAlerts,
  useAcknowledgePlaybookAlert,
} from "./usePlaybooks";

// Performance
export {
  // Performance Metrics
  usePerformanceMetrics,
  usePerformanceDashboard,
  // Performance Insights
  usePerformanceSnapshots,
  useRecentSnapshots,
  useCollectSnapshot,
  usePerformanceSummary,
  useTableUsage,
  // Query Performance
  useTrackedQueries,
  useQueryHistory,
  usePerformanceTrend,
  usePerformanceRegressions,
  useAcknowledgeRegression,
  useResolveRegression,
  useTriggerAnalysis,
  // Lock Contention
  useActiveLocks,
  useLockStatistics,
  useDetectContentions,
  useKillSession,
} from "./usePerformance";

// Sentinel
export {
  useDeathClock,
  useForecasts,
  useGenerateForecast,
  useVelocity,
  useDatabaseEvents,
  useLogDeploymentEvent,
  useLogSchemaChangeEvent,
  useSentinelRecommendations,
  useUpdateRecommendationStatus,
  useSentinelSummary,
} from "./useSentinel";

// Index
export {
  useIndexRecommendations,
  useGenerateIndexRecommendations,
  useApplyIndexRecommendation,
  useDismissIndexRecommendation,
  useDeleteIndexRecommendation,
} from "./useIndex";

// Advisor
export { useAdvisorAnalysis } from "./useAdvisor";

// Saved Items
export {
  // Saved Queries
  useSavedQueries,
  useSavedQuery,
  useFavoriteQueries,
  useQueriesByFolder,
  useQueryFolders,
  useCreateSavedQuery,
  useUpdateSavedQuery,
  useDeleteSavedQuery,
  useToggleQueryFavorite,
  // Saved Dashboards
  useSavedDashboards,
  useSavedDashboard,
  useFavoriteDashboards,
  useDashboardsByFolder,
  useDashboardFolders,
  useCreateSavedDashboard,
  useUpdateSavedDashboard,
  useDeleteSavedDashboard,
  useToggleDashboardFavorite,
} from "./useSavedItems";

// Performance Actions
export {
  usePerformanceActions,
  useTopPerformanceActions,
  usePerformanceActionsByCategory,
  usePerformanceActionsBySource,
  usePerformanceActionsSummary,
  useRefreshPerformanceActions,
  useUpdateActionStatus,
  useBatchUpdateActionStatus,
} from "./usePerformanceActions";

