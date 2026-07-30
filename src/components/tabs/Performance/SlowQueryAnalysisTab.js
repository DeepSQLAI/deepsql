"use client";

import { useState, useEffect, useMemo } from "react";
import {
  AlertCircle,
  CheckCircle,
  AlertTriangle,
  Info,
  Loader2,
  RefreshCw,
  Filter,
  History,
  Clock,
  X,
  Upload,
  Settings,
  Bell,
  GitCompare,
  Database,
  Cloud,
  FileText,
  ArrowRight,
  Lock,
  Sparkles,
} from "lucide-react";
import { slowQueriesAPI, slowLogSourceAPI } from "@/lib/api/client";
import { QueryDetailDialog } from "./components";
import { useAuth } from "@/hooks/useAuth";
import { ActionGuard } from "@/components/ActionGuard";
import { useQueryOptimizationState } from "./hooks/useQueryOptimizationState";
import {
  // Slow Query hooks
  useSlowQueryHistory,
  useLatestSlowQueryAnalysis,
  useSlowQueryHistoryDetail,
  useAnalyzeSlowQueries,
  useDeleteSlowQueryHistory,
  useSlowQueryAlerts,
  useAcknowledgeSlowQueryAlert,
  useAcknowledgeAllSlowQueryAlerts,
  useSlowQueryDashboard,
  useFingerprints,
  useFingerprintTrend,
  useProcessFingerprints,
  useResetFingerprintBaseline,
  useCompareAnalyses,
  useBatchOptimize,
  useGetExplainPlan,
  // Slow Log Source hooks
  useSlowLogSourceConfig,
  useSaveSlowLogSourceConfig,
  useStartAsyncIngestion,
  useActiveIngestionJob,
  useIngestionJobStatus,
  useCancelIngestionJob,
  // Performance hooks
  usePerformanceRegressions,
  useAcknowledgeRegression,
  useResolveRegression,
} from "@/lib/hooks/queries";
import styles from "./SlowQueryAnalysisTab.module.css";

// Extracted components
import {
  SourceConfigModal,
  IamPolicyModal,
  IngestionModal,
  CompareModal,
  UploadModal,
  RegressionPanel,
  AlertPanel,
  HistoryPanel,
  FilterPanel,
  // Utils
  getProviderLabel,
  getProviderSource,
  formatTimestamp,
  formatNextScheduledRun,
  formatDuration,
  formatDate,
  formatBytes,
  getHealthColor,
  getSeverityColor,
  getTrendColor,
  DEFAULT_SOURCE_CONFIG,
} from "./SlowQueries";

export default function SlowQueryAnalysisTab({ connectionId }) {
  // ==================== Permission Check ====================
  const { canAction } = useAuth();
  const canRunAnalysis = canAction("run-ingestion");

  // ==================== TanStack Query Hooks ====================

  // Slow Query History & Analysis
  const { data: historyData, refetch: refetchHistory } =
    useSlowQueryHistory(connectionId);

  const {
    data: latestAnalysisData,
    isLoading: loadingAnalysis,
    error: analysisError,
    refetch: refetchLatestAnalysis,
  } = useLatestSlowQueryAnalysis(connectionId);

  // Selected history item query (when viewing a specific past analysis)
  const [selectedHistoryId, setSelectedHistoryId] = useState(null);
  const [isAnalysisCleared, setIsAnalysisCleared] = useState(false);
  const {
    data: selectedHistoryData,
    isLoading: loadingSelectedHistory,
    isFetching: fetchingSelectedHistory,
  } = useSlowQueryHistoryDetail(selectedHistoryId);

  // Source Config
  const {
    data: sourceConfigData,
    isLoading: loadingSourceConfig,
    isFetched: sourceConfigFetched,
    refetch: refetchSourceConfig,
  } = useSlowLogSourceConfig(connectionId);

  // Regressions
  const {
    data: regressionsData,
    isLoading: loadingRegressions,
    refetch: refetchRegressions,
  } = usePerformanceRegressions(connectionId, true); // unacknowledged only

  // Alerts
  const {
    data: alertSummaryData,
    isLoading: loadingAlerts,
    refetch: refetchAlerts,
  } = useSlowQueryAlerts(connectionId);

  // Dashboard
  const {
    data: dashboardWidgetsData,
    isLoading: loadingDashboard,
    refetch: refetchDashboard,
  } = useSlowQueryDashboard(connectionId);

  // Fingerprints
  const { data: fingerprintsData, refetch: refetchFingerprints } =
    useFingerprints(connectionId, { limit: 100 });

  // Active ingestion job - refetches on mount to detect running jobs after tab switch
  const {
    data: activeJobData,
    isSuccess: activeJobFetched,
    isLoading: activeJobLoading,
    dataUpdatedAt: activeJobUpdatedAt,
  } = useActiveIngestionJob(connectionId);

  // ==================== Mutations ====================
  const analyzeSlowQueriesMutation = useAnalyzeSlowQueries();
  const deleteHistoryMutation = useDeleteSlowQueryHistory();
  const saveSourceConfigMutation = useSaveSlowLogSourceConfig();
  const startIngestionMutation = useStartAsyncIngestion();
  const cancelIngestionMutation = useCancelIngestionJob();
  const acknowledgeAlertMutation = useAcknowledgeSlowQueryAlert();
  const acknowledgeAllAlertsMutation = useAcknowledgeAllSlowQueryAlerts();
  const acknowledgeRegressionMutation = useAcknowledgeRegression();
  const resolveRegressionMutation = useResolveRegression();
  const compareAnalysesMutation = useCompareAnalyses();
  const processFingerPrintsMutation = useProcessFingerprints();
  const resetBaselineMutation = useResetFingerprintBaseline();
  const batchOptimizeMutation = useBatchOptimize();
  const getExplainPlanMutation = useGetExplainPlan();

  // ==================== Local UI State (needed before early returns) ====================
  const [copiedSql, setCopiedSql] = useState(null);
  const [sortBy, setSortBy] = useState("impactScore");
  const [sortOrder, setSortOrder] = useState("desc");
  const [showAllPatterns, setShowAllPatterns] = useState(false);
  const [selectedQueryKey, setSelectedQueryKey] = useState(null);
  const [showRegressionPanel, setShowRegressionPanel] = useState(false);
  const [resettingBaseline, setResettingBaseline] = useState(false);
  const [fingerprintOpen, setFingerprintOpen] = useState(false);

  useEffect(() => {
    if (selectedQueryKey) {
      setFingerprintOpen(false);
    }
  }, [selectedQueryKey]);

  // ==================== Derived State from Queries ====================
  const analysisHistory = useMemo(() => historyData || [], [historyData]);
  // Show selected history analysis if viewing a past analysis, otherwise show latest
  // If user explicitly cleared, show nothing until new analysis
  const analysis = useMemo(() => {
    if (isAnalysisCleared) {
      return null;
    }
    if (selectedHistoryId && selectedHistoryData?.analysisData) {
      return selectedHistoryData.analysisData;
    }
    return latestAnalysisData?.analysisData || null;
  }, [
    isAnalysisCleared,
    selectedHistoryId,
    selectedHistoryData,
    latestAnalysisData,
  ]);
  const regressions = useMemo(
    () => regressionsData?.regressions || [],
    [regressionsData],
  );
  const alertSummary = useMemo(
    () => alertSummaryData || null,
    [alertSummaryData],
  );
  const dashboardWidgets = useMemo(
    () => dashboardWidgetsData || null,
    [dashboardWidgetsData],
  );
  const fingerprints = useMemo(
    () => fingerprintsData || [],
    [fingerprintsData],
  );
  const fingerprintByHash = useMemo(() => {
    const map = new Map();
    fingerprints.forEach((fp) => {
      if (fp?.fingerprint) {
        map.set(fp.fingerprint, fp);
      }
    });
    return map;
  }, [fingerprints]);

  // Shared optimization hook (handles cached optimizations, candidates, benchmark)
  const {
    optimizationResults,
    optimizingQueryKey: optimizingQuery,
    optimizationSteps,
    handleOptimize: handleOptimizeQuery,
    handleBenchmark: handleBenchmarkCandidates,
    getOptimizationForQuery,
    isOptimizingQuery: isOptimizingQueryFn,
    selectedFingerprint: optSelectedFingerprint,
    setSelectedFingerprint: setOptSelectedFingerprint,
    optimizationCandidates: optimizationCandidatesData,
    isBenchmarking: isBenchmarkingCandidates,
    getOptimizationKey,
    setOptimizationResults,
    refetchOptimizationCandidates,
  } = useQueryOptimizationState(connectionId, analysis?.topSlowQueries ?? []);

  // Sync selected query's queryId (not row key) with shared hook for candidate fetching
  useEffect(() => {
    if (!selectedQueryKey) {
      setOptSelectedFingerprint(null);
      return;
    }
    const queries = analysis?.topSlowQueries ?? [];
    const match = queries.find(
      (q, idx) => getQueryKey(q, idx) === selectedQueryKey,
    );
    setOptSelectedFingerprint(match?.queryId ?? null);
  }, [selectedQueryKey, setOptSelectedFingerprint, analysis]);

  // Source config: fetched data with local editing state
  const [sourceConfig, setSourceConfig] = useState(() => ({
    ...DEFAULT_SOURCE_CONFIG,
  }));
  const [configSaved, setConfigSaved] = useState(false);

  // Reset source config when connectionId changes
  useEffect(() => {
    setSourceConfig({ ...DEFAULT_SOURCE_CONFIG });
    setConfigSaved(false);
  }, [connectionId]);

  // Sync source config from fetched data (without credentials)
  useEffect(() => {
    if (sourceConfigData?.id) {
      setSourceConfig((prev) => ({
        ...prev,
        ...sourceConfigData,
        accessKeyId: "",
        secretAccessKey: "",
        sessionToken: "", // Clear sensitive fields
      }));
      setConfigSaved(true);
    }
  }, [sourceConfigData]);

  // Combined loading state
  // Loading state: when fetching analysis OR when we've selected a history item that's still loading
  // Use both isLoading (initial) and isFetching (refetch) for selected history
  const isLoadingSelectedHistory =
    selectedHistoryId && (loadingSelectedHistory || fetchingSelectedHistory);
  const loading =
    loadingAnalysis ||
    isLoadingSelectedHistory ||
    analyzeSlowQueriesMutation.isPending;
  const error = analysisError?.message || null;

  // Filter states
  const [timeRange, setTimeRange] = useState("LAST_24_HOURS");
  const [thresholdMs, setThresholdMs] = useState(100);
  const [limit, setLimit] = useState(10);
  const [showFilters, setShowFilters] = useState(false);

  // History states
  const [showHistory, setShowHistory] = useState(false);

  // File upload states (UploadModal manages its own form state)
  const [uploading, setUploading] = useState(false);
  const [showUploadModal, setShowUploadModal] = useState(false);
  const [showSourceModal, setShowSourceModal] = useState(false);

  // Ingestion states
  const [ingestStatus, setIngestStatus] = useState(null);
  const [showIamPolicyModal, setShowIamPolicyModal] = useState(false);
  const [savingConfig, setSavingConfig] = useState(false);
  const [ingesting, setIngesting] = useState(false);
  const [showIngestModal, setShowIngestModal] = useState(false);
  const [selectedTimeRange, setSelectedTimeRange] = useState("SINCE_LAST");
  const [currentJob, setCurrentJob] = useState(null);
  const [jobPollingInterval, setJobPollingInterval] = useState(null);
  const [pollErrorCount, setPollErrorCount] = useState(0);

  // Derive effective ingesting state from query data (primary) and local state (fallback for optimistic UI)
  // This prevents "Start Ingestion" flash when switching tabs back to a running job
  const effectiveIngesting = useMemo(() => {
    if (
      activeJobData?.status === "RUNNING" ||
      activeJobData?.status === "PENDING"
    ) {
      return true;
    }
    return ingesting;
  }, [activeJobData, ingesting]);

  // Derive effective current job from query data (primary) and local state (fallback)
  const effectiveCurrentJob = useMemo(() => {
    if (activeJobData?.jobExecutionId) {
      return activeJobData;
    }
    return currentJob;
  }, [activeJobData, currentJob]);

  // Regression detection states (panel expanded state managed by RegressionPanel component)

  // AI Optimization panel visibility (results state managed by shared hook)
  const [showOptimizationPanel, setShowOptimizationPanel] = useState({});

  // Alert states
  const [showAlertPanel, setShowAlertPanel] = useState(false);

  // Query Comparison states (period selection managed by CompareModal component)
  const [showCompareModal, setShowCompareModal] = useState(false);
  const [comparisonResult, setComparisonResult] = useState(null);
  const [loadingComparison, setLoadingComparison] = useState(false);

  // Dashboard widgets states
  const [showDashboard, setShowDashboard] = useState(false);

  // Fingerprint tracking states
  const [showFingerprintPanel, setShowFingerprintPanel] = useState(false);
  const [selectedFingerprintTrend, setSelectedFingerprintTrend] =
    useState(null);

  // EXPLAIN states
  const [explainResults, setExplainResults] = useState({});
  const [loadingExplain, setLoadingExplain] = useState({});

  // Check for active ingestion job on component mount or when fresh data arrives
  // Uses activeJobUpdatedAt to trigger when refetch returns same data after tab switch
  useEffect(() => {
    if (activeJobFetched && activeJobData?.jobExecutionId) {
      setCurrentJob(activeJobData);
      // Handle resumable statuses - show resume option instead of polling
      // Both INTERRUPTED (server crash) and FAILED (job error) are resumable
      if (
        activeJobData.status === "INTERRUPTED" ||
        activeJobData.status === "FAILED"
      ) {
        setIngesting(false);
        const message =
          activeJobData.status === "INTERRUPTED"
            ? "Previous ingestion was interrupted. You can resume it."
            : `Previous ingestion failed: ${activeJobData.message || "Unknown error"}. You can retry.`;
        setIngestStatus({
          success: false,
          message,
          canResume: true,
          jobId: activeJobData.jobExecutionId,
        });
      } else if (
        activeJobData.status === "RUNNING" ||
        activeJobData.status === "PENDING"
      ) {
        setIngesting(true);
        startJobPolling(activeJobData.jobExecutionId);
      }
    }
  }, [activeJobData, activeJobFetched, activeJobUpdatedAt]);

  // Handlers using mutations
  const handleAcknowledgeRegression = async (regressionId) => {
    try {
      await acknowledgeRegressionMutation.mutateAsync({ regressionId });
    } catch (err) {
      console.error("Error acknowledging regression:", err);
    }
  };

  const handleResolveRegression = async (regressionId) => {
    try {
      await resolveRegressionMutation.mutateAsync({
        regressionId,
        resolutionNotes: "Resolved by user",
      });
    } catch (err) {
      console.error("Error resolving regression:", err);
    }
  };

  // ==================== AI Optimization Functions ====================
  // getOptimizationKey, handleOptimizeQuery, handleBenchmarkCandidates
  // are provided by useQueryOptimizationState shared hook

  const handleBatchOptimize = async () => {
    if (!connectionId) return;
    try {
      const results = await batchOptimizeMutation.mutateAsync({
        connectionId,
        limit: 5,
      });
      if (Array.isArray(results)) {
        setOptimizationResults((prev) => {
          const next = { ...prev };
          results.forEach((result) => {
            const key =
              result.queryId ||
              result.normalizedQuery ||
              result.originalQuery?.substring(0, 50);
            if (key) {
              next[key] = result;
            }
          });
          return next;
        });
      }
    } catch (err) {
      console.error("Error batch optimizing queries:", err);
    }
  };

  // ==================== Alert Functions ====================

  // Auto-show alert panel when there are unacknowledged alerts
  useEffect(() => {
    if (alertSummary?.unacknowledgedCount > 0) {
      setShowAlertPanel(true);
    }
  }, [alertSummary]);

  const handleAcknowledgeSlowQueryAlert = async (alertId) => {
    try {
      const userId = localStorage.getItem("username") || "admin";
      await acknowledgeAlertMutation.mutateAsync({ alertId, userId });
    } catch (err) {
      console.error("Error acknowledging alert:", err);
    }
  };

  const handleAcknowledgeAllAlerts = async () => {
    try {
      const userId = localStorage.getItem("username") || "admin";
      await acknowledgeAllAlertsMutation.mutateAsync({ connectionId, userId });
    } catch (err) {
      console.error("Error acknowledging all alerts:", err);
    }
  };

  // ==================== Query Comparison Functions ====================

  const handleCompareAnalyses = async (period1, period2) => {
    if (!period1 || !period2) return;
    setLoadingComparison(true);
    try {
      const result = await compareAnalysesMutation.mutateAsync({
        historyId1: period1,
        historyId2: period2,
      });
      setComparisonResult(result);
    } catch (err) {
      console.error("Error comparing analyses:", err);
    } finally {
      setLoadingComparison(false);
    }
  };

  // ==================== Dashboard Functions ====================

  // Dashboard data is now fetched via useSlowQueryDashboard hook
  // refetchDashboard() can be called to refresh

  // ==================== Fingerprint Functions ====================

  // Fingerprints are now fetched via useFingerprints hook
  // refetchFingerprints() can be called to refresh

  const handleProcessFingerprints = async () => {
    if (!connectionId) return;
    try {
      await processFingerPrintsMutation.mutateAsync(connectionId);
      refetchFingerprints();
    } catch (err) {
      console.error("Error processing fingerprints:", err);
    }
  };

  const handleResetBaseline = async (fingerprintId) => {
    try {
      await resetBaselineMutation.mutateAsync(fingerprintId);
      refetchFingerprints();
    } catch (err) {
      console.error("Error resetting baseline:", err);
    }
  };

  const fetchFingerprintTrend = async (fingerprintId) => {
    try {
      const trend = await slowQueriesAPI.getFingerprintTrend(fingerprintId);
      setSelectedFingerprintTrend(trend);
    } catch (err) {
      console.error("Error fetching fingerprint trend:", err);
    }
  };

  // ==================== EXPLAIN Functions ====================

  const handleRunExplain = async (query, queryKey) => {
    setLoadingExplain((prev) => ({ ...prev, [queryKey]: true }));
    try {
      const result = await getExplainPlanMutation.mutateAsync({
        connectionId,
        queryText: query.queryText,
        analyze: false,
      });
      // Backend returns { explainAnalysis: {...}, queryText, connectionId }
      const analysisData = result.explainAnalysis || result;
      setExplainResults((prev) => ({ ...prev, [queryKey]: analysisData }));
    } catch (err) {
      console.error("Error running EXPLAIN:", err);
      setExplainResults((prev) => ({
        ...prev,
        [queryKey]: { error: err.message },
      }));
    } finally {
      setLoadingExplain((prev) => ({ ...prev, [queryKey]: false }));
    }
  };

  // Run fresh analysis (expensive operation) - uses mutation
  const runFreshAnalysis = async () => {
    try {
      await analyzeSlowQueriesMutation.mutateAsync({
        connectionId,
        threshold: thresholdMs,
        limit,
      });
      // Refetch to get updated data
      refetchLatestAnalysis();
      refetchHistory();
    } catch (err) {
      console.error("Error running analysis:", err);
    }
  };

  // Alias for backward compatibility
  const fetchAnalysis = runFreshAnalysis;

  const handleFileUpload = async (event, dbType = "mysql") => {
    const file = event.target.files[0];
    if (!file) return;

    setUploading(true);

    try {
      await slowQueriesAPI.analyzeLogFile(connectionId, file, dbType);
      // Refetch to get updated data
      refetchLatestAnalysis();
      refetchHistory();
    } catch (err) {
      console.error("Error analyzing log file:", err);
    } finally {
      setUploading(false);
    }
  };

  const handleS3Analyze = async (url, region, dbType = "mysql") => {
    if (!url || !connectionId) {
      return;
    }

    setUploading(true);

    try {
      await slowQueriesAPI.analyzeLogFileFromS3({
        connectionId,
        s3Url: url,
        region: region || null,
        databaseType: dbType,
        userId: null,
      });
      // Refetch to get updated data
      refetchLatestAnalysis();
      refetchHistory();
    } catch (err) {
      console.error("Error analyzing S3 log file:", err);
    } finally {
      setUploading(false);
    }
  };

  // Source config is now loaded via useSlowLogSourceConfig hook
  // Sync happens in the useEffect above

  const handleSaveSourceConfig = async () => {
    if (!connectionId) return;
    setIngestStatus(null);
    setSavingConfig(true);
    try {
      const payload = {
        connectionId,
        ...sourceConfig,
      };
      const saved = await saveSourceConfigMutation.mutateAsync(payload);
      setSourceConfig((prev) => ({
        ...prev,
        ...saved,
        accessKeyId: "",
        secretAccessKey: "",
        sessionToken: "",
      }));
      setConfigSaved(true);
      setShowSourceModal(false);
    } catch (err) {
      setIngestStatus({
        success: false,
        message: err.message || "Failed to save configuration.",
      });
    } finally {
      setSavingConfig(false);
    }
  };

  const handleIngestNow = async (timeRange = "SINCE_LAST") => {
    if (!connectionId) return;
    setIngestStatus(null);
    setIngesting(true);
    setCurrentJob(null);
    setPollErrorCount(0);

    try {
      const result = await startIngestionMutation.mutateAsync({
        connectionId,
        includeCloudWatch: sourceConfig.providerType === "CLOUDWATCH",
        includeS3: sourceConfig.providerType === "S3",
        includeAzureBlob: sourceConfig.providerType === "AZURE_BLOB",
        includeGcpLogging: sourceConfig.providerType === "GCP_LOGGING",
        includeDatadog: sourceConfig.providerType === "DATADOG",
        includeElasticsearch: sourceConfig.providerType === "ELASTICSEARCH",
        maxLogs: 10000,
        timeRange,
      });

      if (!result?.success) {
        setIngestStatus({
          success: false,
          message: result?.message || "Failed to start ingestion.",
        });
        setIngesting(false);
        return;
      }

      // Close modal and show progress in main view
      setShowIngestModal(false);

      // Start polling for job status
      const jobId = result.jobId;
      startJobPolling(jobId);
    } catch (err) {
      console.error("Error starting ingestion:", err);
      setIngestStatus({
        success: false,
        message: err.message || "Failed to start ingestion.",
      });
      setIngesting(false);
    }
  };

  const startJobPolling = (jobId) => {
    // Clear any existing interval
    if (jobPollingInterval) {
      clearInterval(jobPollingInterval);
    }

    // Poll immediately
    pollJobStatus(jobId);

    // Then poll every 1 second
    const interval = setInterval(() => pollJobStatus(jobId), 1000);
    setJobPollingInterval(interval);
  };
  const MAX_POLL_ERRORS = 5;

  const pollJobStatus = async (jobId) => {
    try {
      const job = await slowLogSourceAPI.getJobStatus(jobId);
      setCurrentJob(job);
      setPollErrorCount(0); // Reset error count on success

      // Check if job is in a terminal or interrupted state
      if (
        job.status === "COMPLETED" ||
        job.status === "FAILED" ||
        job.status === "CANCELLED" ||
        job.status === "INTERRUPTED"
      ) {
        // Stop polling
        if (jobPollingInterval) {
          clearInterval(jobPollingInterval);
          setJobPollingInterval(null);
        }

        setIngesting(false);

        if (job.status === "COMPLETED") {
          // Ingestion already parses and saves analysis - just refetch to display it
          setIsAnalysisCleared(false);
          setIngestStatus(null); // Clear status to show analysis view
          refetchSourceConfig();
          refetchLatestAnalysis();
          refetchHistory();
        } else if (job.status === "CANCELLED") {
          setIngestStatus({
            success: false,
            message: "Ingestion was cancelled.",
          });
        } else if (job.status === "INTERRUPTED" || job.status === "FAILED") {
          // Job was interrupted (server restart) or failed - offer resume option
          // Both INTERRUPTED and FAILED are resumable in Spring Batch
          const message =
            job.status === "INTERRUPTED"
              ? "Ingestion was interrupted (server restart). You can resume it."
              : `Ingestion failed: ${job.message || "Unknown error"}. You can retry.`;
          setIngestStatus({
            success: false,
            message,
            canResume: true,
            jobId: job.jobExecutionId,
          });
        }
      }
    } catch (err) {
      console.error("Error polling job status:", err);
      setPollErrorCount((prev) => {
        const newCount = prev + 1;
        if (newCount >= MAX_POLL_ERRORS) {
          // Stop polling after too many errors
          if (jobPollingInterval) {
            clearInterval(jobPollingInterval);
            setJobPollingInterval(null);
          }
          setIngesting(false);
          setIngestStatus({
            success: false,
            message:
              "Lost connection to ingestion job. The server may have restarted. Please try again.",
          });
        }
        return newCount;
      });
    }
  };

  const handleCancelIngestion = async () => {
    // If there's a running job, cancel it
    if (currentJob?.jobExecutionId) {
      try {
        await cancelIngestionMutation.mutateAsync(currentJob.jobExecutionId);
        // The polling will pick up the cancelled status
      } catch (err) {
        console.error("Error cancelling job:", err);
      }
    }

    // Stop polling if active
    if (jobPollingInterval) {
      clearInterval(jobPollingInterval);
      setJobPollingInterval(null);
    }

    // Reset state
    setIngesting(false);
    setCurrentJob(null);
    setIngestStatus({ success: false, message: "Ingestion was cancelled." });
  };

  const handleResumeIngestion = async (jobId) => {
    try {
      setIngestStatus(null);
      setIngesting(true);

      const result = await slowLogSourceAPI.resumeJob(jobId);
      if (result.success) {
        // Start polling the resumed job
        startJobPolling(result.jobId);
      } else {
        setIngesting(false);
        setIngestStatus({
          success: false,
          message: result.message || "Failed to resume ingestion.",
        });
      }
    } catch (err) {
      console.error("Error resuming ingestion:", err);
      setIngesting(false);
      setIngestStatus({
        success: false,
        message: err.message || "Failed to resume ingestion.",
      });
    }
  };

  // Cleanup polling interval on unmount
  useEffect(() => {
    return () => {
      if (jobPollingInterval) {
        clearInterval(jobPollingInterval);
      }
    };
  }, [jobPollingInterval]);

  const openIngestModal = () => {
    setSelectedTimeRange(
      sourceConfig.lastProcessedAt ? "SINCE_LAST" : "LAST_24_HOURS",
    );
    setIngestStatus(null);
    setCurrentJob(null);
    setShowIngestModal(true);
  };

  const toggleAutoSchedule = async () => {
    if (!connectionId) return;
    const newValue = !sourceConfig.autoScheduleEnabled;
    try {
      const payload = {
        connectionId,
        ...sourceConfig,
        autoScheduleEnabled: newValue,
      };
      const saved = await slowLogSourceAPI.saveConfig(payload);
      setSourceConfig((prev) => ({
        ...prev,
        ...saved,
        accessKeyId: "",
        secretAccessKey: "",
        sessionToken: "",
      }));
    } catch (err) {
      console.error("Error toggling auto-schedule:", err);
    }
  };

  const copyToClipboard = async (text, id) => {
    try {
      await navigator.clipboard.writeText(text);
      setCopiedSql(id);
      setTimeout(() => setCopiedSql(null), 2000);
    } catch (err) {
      console.error("Failed to copy:", err);
    }
  };

  const getUploadLabel = () => "log file";

  const getTimeRangeLabel = (range) => {
    switch (range) {
      case "LAST_HOUR":
        return "Last hour";
      case "LAST_24_HOURS":
        return "Last 24h";
      case "LAST_7_DAYS":
        return "Last 7d";
      case "LAST_30_DAYS":
        return "Last 30d";
      case "ALL_TIME":
        return "All time";
      default:
        return "Custom";
    }
  };

  const getTotalTimeMs = (query) => {
    if (!query) return 0;
    if (query.totalExecutionTimeMs != null) return query.totalExecutionTimeMs;
    if (query.avgExecutionTimeMs != null && query.callCount != null) {
      return query.avgExecutionTimeMs * query.callCount;
    }
    return 0;
  };

  // Raw impact score used for sorting — uncapped so 353s queries rank above 5s queries
  const getRawImpactScore = (query) => {
    if (!query) return 0;
    const avgMs = query.avgExecutionTimeMs || 0;
    const calls = query.callCount || 0;
    // Total database wall-time is the best proxy for optimization impact
    const totalTimeScore = (avgMs * calls) / 1000;
    // Blend in per-call severity so a single 353s query still outranks many 1s queries
    const perCallSeverity = (avgMs / 1000) * Math.log10(calls + 1) * 10;
    return totalTimeScore + perCallSeverity;
  };

  // Capped 0-100 score for display in the Impact column
  const getImpactScore = (query) => {
    if (!query) return 0;
    if (query.performanceImpact != null)
      return Math.min(100, query.performanceImpact);
    const avgMs = query.avgExecutionTimeMs || 0;
    const calls = query.callCount || 0;
    const impact = (avgMs / 1000) * Math.log10(calls + 1) * 10;
    return Math.min(100, impact);
  };

  const formatTotalTime = (totalMs) => {
    const totalSeconds = totalMs / 1000;
    if (totalSeconds >= 3600) {
      return { value: (totalSeconds / 3600).toFixed(1), unit: "hours" };
    }
    if (totalSeconds >= 60) {
      return { value: Math.round(totalSeconds / 60), unit: "minutes" };
    }
    return { value: Math.round(totalSeconds), unit: "seconds" };
  };

  const formatCompactNumber = (value) => {
    if (value == null || Number.isNaN(value)) return "—";
    try {
      return new Intl.NumberFormat("en", {
        notation: "compact",
        maximumFractionDigits: 1,
      }).format(value);
    } catch (err) {
      return value.toLocaleString();
    }
  };

  const getTrendMeta = (query) => {
    const fp = query?.queryId ? fingerprintByHash.get(query.queryId) : null;
    if (!fp || fp.trendPercentage == null || !fp.trendDirection) {
      return { label: "—", className: styles.trendNeutral };
    }
    const pct = Math.abs(fp.trendPercentage);
    if (fp.trendDirection === "IMPROVING") {
      return { label: `-${pct.toFixed(0)}%`, className: styles.trendDown };
    }
    if (fp.trendDirection === "DEGRADING" || fp.trendDirection === "CRITICAL") {
      return { label: `+${pct.toFixed(0)}%`, className: styles.trendUp };
    }
    return { label: `${pct.toFixed(0)}%`, className: styles.trendNeutral };
  };

  const getQueryPreview = (text, limit = 80) => {
    if (!text) return "—";
    const normalized = text.replace(/\s+/g, " ").trim();
    if (normalized.length <= limit) return normalized;
    return `${normalized.slice(0, limit)}...`;
  };

  const getPatternTitle = (query) => {
    if (!query) return "Pattern Detail";
    const table =
      Array.isArray(query.affectedTables) && query.affectedTables.length > 0
        ? query.affectedTables[0]
        : null;
    if (table) {
      const columnHint = query?.suggestedIndexes?.[0]?.columns?.slice(-1)?.[0];
      return columnHint
        ? `Pattern: ${table} by ${columnHint}`
        : `Pattern: ${table}`;
    }
    if (query.queryId) {
      return `Pattern: ${query.queryId.slice(0, 8)}`;
    }
    return "Pattern Detail";
  };

  const getPatternTags = (query) => {
    const tags = [];
    if (query?.suggestedIndexes?.length) tags.push("Missing index");
    if (
      query?.rowsExamined &&
      query?.rowsSent &&
      query.rowsSent / query.rowsExamined < 0.1
    ) {
      tags.push("Large scan");
    }
    if (query?.severity) tags.push(query.severity);
    return tags;
  };

  const getQueryKey = (query, fallbackIndex) => {
    if (!query) return fallbackIndex;
    return (
      query.queryId ??
      query.fingerprint ??
      query.normalizedQuery ??
      query.queryText ??
      fallbackIndex
    );
  };

  const handleSelectQuery = (queryKey) => {
    setSelectedQueryKey((prev) => (prev === queryKey ? null : queryKey));
  };

  const handleSortChange = (event) => {
    setSortBy(event.target.value);
    setSortOrder("desc");
  };

  const getSortedQueries = () => {
    if (!analysis || !analysis.topSlowQueries) return [];

    const queries = [...analysis.topSlowQueries];
    queries.sort((a, b) => {
      // Use uncapped raw score for sorting so 353s queries rank above 5s queries
      const aVal =
        sortBy === "impactScore" ? getRawImpactScore(a) : a[sortBy] || 0;
      const bVal =
        sortBy === "impactScore" ? getRawImpactScore(b) : b[sortBy] || 0;

      if (sortOrder === "asc") {
        return aVal > bVal ? 1 : -1;
      } else {
        return aVal < bVal ? 1 : -1;
      }
    });

    return queries;
  };

  const clearAnalysis = () => {
    // Clear selected history and mark as cleared to show empty state
    setSelectedHistoryId(null);
    setIsAnalysisCleared(true);
    setSelectedQueryKey(null);
  };

  const loadFromHistory = (historyItem) => {
    // Select this history item to view it
    setSelectedHistoryId(historyItem.id);
    setIsAnalysisCleared(false);
    setShowHistory(false);
  };

  const deleteFromHistory = async (id) => {
    try {
      await deleteHistoryMutation.mutateAsync(id);
      // If we deleted the currently viewed history, clear it
      if (selectedHistoryId === id) {
        setSelectedHistoryId(null);
      }
    } catch (error) {
      console.error("Error deleting slow query history:", error);
    }
  };

  const getHealthIcon = (health) => {
    switch (health) {
      case "EXCELLENT":
      case "GOOD":
        return <CheckCircle size={24} />;
      case "FAIR":
        return <Info size={24} />;
      case "POOR":
      case "CRITICAL":
        return <AlertCircle size={24} />;
      default:
        return <AlertTriangle size={24} />;
    }
  };

  const getSeverityIcon = (severity) => {
    switch (severity) {
      case "CRITICAL":
      case "HIGH":
        return <AlertCircle size={16} />;
      case "MEDIUM":
        return <AlertTriangle size={16} />;
      case "LOW":
        return <Info size={16} />;
      default:
        return <Info size={16} />;
    }
  };

  if (!connectionId) {
    return (
      <div className={styles.container}>
        <div className={styles.emptyState}>
          <AlertCircle size={48} className={styles.emptyStateIcon} />
          <h3>No Connection Selected</h3>
          <p>Please select a database connection to analyze slow queries</p>
        </div>
      </div>
    );
  }

  if (loading) {
    return (
      <div className={styles.container}>
        <div className={styles.loadingState}>
          <Loader2 size={48} className={styles.spinner} />
          <h3>Analyzing Slow Queries</h3>
          <p>Scanning query performance data and identifying bottlenecks...</p>
        </div>
      </div>
    );
  }

  if (error) {
    return (
      <div className={styles.container}>
        <div className={styles.errorState}>
          <AlertCircle size={48} />
          <h3>Analysis Failed</h3>
          <p>{error}</p>
          <button onClick={fetchAnalysis} className={styles.retryButton}>
            Retry Analysis
          </button>
        </div>
      </div>
    );
  }

  // Check if we have a valid analysis (must have analysisDate timestamp - indicates it was actually run)
  // Just having overallHealth or empty data doesn't count as a valid analysis
  // Note: Backend uses 'analysisDate', not 'analyzedAt'
  const isValidAnalysis =
    analysis && (analysis.analysisDate || analysis.analyzedAt);

  // Check if analysis ran but found no slow queries (only if analysis is valid)
  const hasNoSlowQueries =
    isValidAnalysis &&
    (!analysis.totalQueriesAnalyzed || analysis.totalQueriesAnalyzed === 0) &&
    (!analysis.topSlowQueries || analysis.topSlowQueries.length === 0);

  // Show empty state if no valid analysis exists
  if (!isValidAnalysis) {
    // Check if external source is already configured
    const hasExternalSource = configSaved && sourceConfig.id;

    return (
      <div className={styles.container}>
        <div className={styles.emptyStateNew}>
          {/* Header */}
          <div className={styles.emptyStateHeader}>
            <h2>Slow Query Analysis</h2>
            <p>
              Identify and optimize slow-running queries to improve database
              performance
            </p>
          </div>
          <div className={styles.emptyStatePillRow}>
            <ActionGuard action="configure-source" mode="disable">
              <button
                className={`${styles.pillButton} btn-minimal`}
                onClick={() => setShowSourceModal(true)}
              >
                <Settings size={14} />
                <span>
                  {hasExternalSource
                    ? "Ingestion configured"
                    : "Ingestion not configured"}
                </span>
                <span className={styles.pillMuted}>
                  • {hasExternalSource ? "Auto-ingest ON" : "Set up source"}
                </span>
                <span
                  className={`${styles.pillDot} ${hasExternalSource ? styles.pillDotOn : styles.pillDotOff}`}
                />
              </button>
            </ActionGuard>
          </div>

          {/* If external source is configured, show prominent ingestion option */}
          {hasExternalSource && (
            <div className={styles.configuredSourceBanner}>
              {activeJobLoading ? (
                /* Loading state while checking for active job */
                <div className={styles.ingestionProgress}>
                  <div className={styles.ingestionProgressHeader}>
                    <Loader2 size={20} className={styles.spinner} />
                    <span>Checking for active ingestion...</span>
                  </div>
                </div>
              ) : effectiveIngesting && effectiveCurrentJob ? (
                /* Active Ingestion Progress */
                <div className={styles.ingestionProgress}>
                  <div className={styles.ingestionProgressHeader}>
                    <Loader2 size={20} className={styles.spinner} />
                    <div>
                      <strong>
                        Ingesting from{" "}
                        {getProviderLabel(sourceConfig.providerType)}
                      </strong>
                      <span className={styles.ingestionProgressDetail}>
                        {effectiveCurrentJob.currentStep || "Processing..."}
                      </span>
                    </div>
                  </div>
                  <div className={styles.progressBarContainer}>
                    <div
                      className={styles.progressBar}
                      style={{
                        width: `${effectiveCurrentJob.progressPct || 0}%`,
                      }}
                    />
                  </div>
                  <div className={styles.ingestionStats}>
                    <span>
                      {effectiveCurrentJob.progressPct || 0}% complete
                    </span>
                    {effectiveCurrentJob.eventsProcessed > 0 && (
                      <span>
                        {effectiveCurrentJob.eventsProcessed.toLocaleString()}{" "}
                        events
                      </span>
                    )}
                    {effectiveCurrentJob.slowQueriesFound > 0 && (
                      <span>
                        {effectiveCurrentJob.slowQueriesFound.toLocaleString()}{" "}
                        queries
                      </span>
                    )}
                    {effectiveCurrentJob.bytesProcessed > 0 && (
                      <span>
                        {formatBytes(effectiveCurrentJob.bytesProcessed)}
                      </span>
                    )}
                  </div>
                  <button
                    onClick={handleCancelIngestion}
                    className={styles.cancelIngestionButton}
                  >
                    Cancel
                  </button>
                </div>
              ) : (
                /* Configured Source Info */
                <>
                  <div className={styles.configuredSourceInfo}>
                    <CheckCircle
                      size={20}
                      style={{ color: "var(--color-dark-grey)" }}
                    />
                    <div>
                      <strong>
                        {getProviderLabel(sourceConfig.providerType)}
                      </strong>
                      <span>
                        {" "}
                        configured: {getProviderSource(sourceConfig)}
                      </span>
                      {sourceConfig.lastProcessedAt && (
                        <span className={styles.lastIngested}>
                          Last ingested:{" "}
                          {formatTimestamp(sourceConfig.lastProcessedAt)}
                        </span>
                      )}
                    </div>
                    <button
                      onClick={() => setShowSourceModal(true)}
                      className={styles.editConfigLinkSmall}
                    >
                      Edit
                    </button>
                  </div>
                  <ActionGuard action="run-ingestion">
                    <button
                      onClick={openIngestModal}
                      className={styles.primaryActionButton}
                    >
                      <RefreshCw size={18} />
                      <span>Start Ingestion</span>
                    </button>
                  </ActionGuard>
                </>
              )}
              {ingestStatus && !effectiveIngesting && (
                <div
                  className={`${styles.ingestStatusMessage} ${ingestStatus.success ? styles.success : styles.error}`}
                >
                  {ingestStatus.success ? (
                    <CheckCircle size={14} />
                  ) : (
                    <AlertCircle size={14} />
                  )}
                  <span>{ingestStatus.message}</span>
                  {ingestStatus.canResume && ingestStatus.jobId && (
                    <button
                      onClick={() => handleResumeIngestion(ingestStatus.jobId)}
                      className={styles.resumeButton}
                    >
                      <RefreshCw size={14} />
                      Resume
                    </button>
                  )}
                </div>
              )}
            </div>
          )}

          {/* Three Option Cards */}
          <div className={styles.optionCardsGrid}>
            {/* Option 1: Query from Database */}
            <ActionGuard action="analyze-slow-queries" mode="disable">
              <div
                className={styles.optionCard}
                onClick={runFreshAnalysis}
                role="button"
                tabIndex={0}
              >
                <div className={styles.optionIconWrapper}>
                  <Database size={24} />
                </div>
                <div className={styles.optionContent}>
                  <h3>Query from Database</h3>
                  <p>
                    Analyze slow queries from built-in performance tables.
                    MySQL: performance_schema + mysql.slow_log. PostgreSQL:
                    pg_stat_statements.
                  </p>
                  <div className={styles.optionMeta}>
                    <span className={styles.optionTag}>Instant</span>
                    <span className={styles.optionTag}>No setup required</span>
                  </div>
                </div>
                <ArrowRight size={18} className={styles.optionArrow} />
              </div>
            </ActionGuard>

            {/* Option 2: Upload Log File */}
            <ActionGuard action="upload-log" mode="disable">
              <label
                htmlFor="log-file-upload-empty"
                className={styles.optionCard}
                role="button"
                tabIndex={0}
              >
                <div className={styles.optionIconWrapper}>
                  <FileText size={24} />
                </div>
                <div className={styles.optionContent}>
                  <h3>Upload Log File</h3>
                  <p>
                    Upload a slow query log file exported from your database.
                    Supports MySQL slow log, PostgreSQL log, and CSV formats.
                  </p>
                  <div className={styles.optionMeta}>
                    <span className={styles.optionTag}>.log</span>
                    <span className={styles.optionTag}>.txt</span>
                    <span className={styles.optionTag}>.csv</span>
                  </div>
                </div>
                <Upload size={18} className={styles.optionArrow} />
                <input
                  id="log-file-upload-empty"
                  type="file"
                  accept=".log,.txt,.csv"
                  onChange={handleFileUpload}
                  style={{ display: "none" }}
                />
              </label>
            </ActionGuard>

            {/* Option 3: Connect Cloud Source */}
            <ActionGuard action="configure-source" mode="disable">
              <div
                className={`${styles.optionCard} ${hasExternalSource ? styles.optionCardConfigured : ""}`}
                onClick={() => setShowSourceModal(true)}
                role="button"
                tabIndex={0}
              >
                <div className={styles.optionIconWrapper}>
                  <Cloud size={24} />
                </div>
                <div className={styles.optionContent}>
                  <h3>Connect Cloud Source</h3>
                  <p>
                    Set up automatic ingestion from AWS CloudWatch Logs or S3
                    buckets. Support for Azure, GCP, and others coming soon.
                  </p>
                  <div className={styles.optionMeta}>
                    <span className={styles.optionTag}>CloudWatch</span>
                    <span className={styles.optionTag}>S3</span>
                    <span className={styles.optionTagMuted}>
                      More coming soon
                    </span>
                  </div>
                </div>
                {hasExternalSource ? (
                  <CheckCircle
                    size={18}
                    className={styles.optionArrow}
                    style={{ color: "var(--color-dark-grey)" }}
                  />
                ) : (
                  <Settings size={18} className={styles.optionArrow} />
                )}
              </div>
            </ActionGuard>
          </div>

          {/* History link if available */}
          {analysisHistory.length > 0 && (
            <div className={styles.historyLink}>
              <span>
                You have {analysisHistory.length} saved{" "}
                {analysisHistory.length === 1 ? "analysis" : "analyses"}.
              </span>
              <button
                onClick={() => setShowHistory(true)}
                className={styles.viewHistoryLink}
              >
                View History
              </button>
            </div>
          )}

          {/* Loading/uploading states */}
          {loadingSourceConfig && (
            <div className={styles.loadingIndicator}>
              <Loader2 size={16} className={styles.spinner} />
              <span>Loading configuration...</span>
            </div>
          )}

          {uploading && (
            <div className={styles.uploadingIndicator}>
              <Loader2 size={20} className={styles.spinner} />
              <span>Analyzing {getUploadLabel()}...</span>
            </div>
          )}
        </div>

        {/* Modals */}
        {showSourceModal && (
          <SourceConfigModal
            sourceConfig={sourceConfig}
            setSourceConfig={setSourceConfig}
            onClose={() => setShowSourceModal(false)}
            onSave={handleSaveSourceConfig}
            onIngestNow={() => {
              setShowSourceModal(false);
              openIngestModal();
            }}
            onShowIamPolicy={() => setShowIamPolicyModal(true)}
            ingestStatus={ingestStatus}
          />
        )}

        {showIamPolicyModal && (
          <IamPolicyModal
            sourceConfig={sourceConfig}
            onClose={() => setShowIamPolicyModal(false)}
          />
        )}

        {showIngestModal && (
          <IngestionModal
            sourceConfig={sourceConfig}
            selectedTimeRange={selectedTimeRange}
            setSelectedTimeRange={setSelectedTimeRange}
            ingesting={ingesting}
            currentJob={currentJob}
            ingestStatus={ingestStatus}
            onClose={() => setShowIngestModal(false)}
            onStartIngestion={handleIngestNow}
            onCancelIngestion={handleCancelIngestion}
            canRunAnalysis={canRunAnalysis}
          />
        )}

        {showHistory && (
          <HistoryPanel
            history={analysisHistory}
            onLoadHistory={loadFromHistory}
            onDeleteHistory={deleteFromHistory}
            onClose={() => setShowHistory(false)}
            getHealthColor={getHealthColor}
          />
        )}
      </div>
    );
  }

  // Show a special state when analysis ran but found no slow queries
  if (hasNoSlowQueries) {
    return (
      <div className={styles.container}>
        <div className={styles.emptyState}>
          <CheckCircle size={48} style={{ color: "var(--color-dark-grey)" }} />
          <h3>No Slow Queries Found</h3>
          <p>
            Great news! No slow queries were found in the analyzed logs.
            {analysis.timeRange &&
              ` (${getTimeRangeLabel(analysis.timeRange)})`}
          </p>

          <div className={styles.emptyStateActions}>
            <ActionGuard action="analyze-slow-queries">
              <button
                onClick={runFreshAnalysis}
                className={`${styles.uploadButton} ${styles.analyzeUploadButton}`}
                title="Run another analysis"
              >
                <RefreshCw size={16} />
                <span className={styles.buttonLabel}>Run New Analysis</span>
              </button>
            </ActionGuard>

            <ActionGuard action="configure-source">
              <button
                onClick={() => setShowSourceModal(true)}
                className={styles.iconButton}
                title="Configure Slow Query Log Source"
              >
                <Settings size={18} />
              </button>
            </ActionGuard>

            {analysisHistory.length > 0 && (
              <button
                onClick={() => setShowHistory(true)}
                className={styles.uploadButton}
                title={`View analysis history (${analysisHistory.length})`}
              >
                <History size={16} />
                <span className={styles.buttonLabel}>View History</span>
              </button>
            )}

            <button
              onClick={clearAnalysis}
              className={styles.iconButton}
              title="Clear and start fresh"
            >
              <X size={18} />
            </button>
          </div>

          {/* Show ingestion section when config is saved */}
          {configSaved && sourceConfig.id && (
            <div className={styles.ingestionSection}>
              <div className={styles.ingestionConfig}>
                <CheckCircle size={16} className={styles.configSavedIcon} />
                <span>
                  {getProviderLabel(sourceConfig.providerType)} configured:
                  <strong> {getProviderSource(sourceConfig)}</strong>
                </span>
              </div>
              {sourceConfig.lastProcessedAt && (
                <div className={styles.lastProcessedInfo}>
                  <Clock size={14} />
                  <span>
                    Last ingested:{" "}
                    {formatTimestamp(sourceConfig.lastProcessedAt)}
                  </span>
                </div>
              )}
              <ActionGuard action="run-ingestion">
                <button
                  onClick={openIngestModal}
                  className={styles.startIngestionButton}
                  disabled={effectiveIngesting && effectiveCurrentJob}
                >
                  <RefreshCw size={16} />
                  <span>
                    Start Ingestion from{" "}
                    {getProviderLabel(sourceConfig.providerType)}
                  </span>
                </button>
              </ActionGuard>
            </div>
          )}
        </div>

        {/* Source Config Modal */}
        {showSourceModal && (
          <SourceConfigModal
            sourceConfig={sourceConfig}
            setSourceConfig={setSourceConfig}
            onClose={() => setShowSourceModal(false)}
            onSave={handleSaveSourceConfig}
            onIngestNow={() => {
              setShowSourceModal(false);
              openIngestModal();
            }}
            onShowIamPolicy={() => setShowIamPolicyModal(true)}
            ingestStatus={ingestStatus}
          />
        )}

        {/* IAM Policy Modal */}
        {showIamPolicyModal && (
          <IamPolicyModal
            sourceConfig={sourceConfig}
            onClose={() => setShowIamPolicyModal(false)}
          />
        )}

        {/* Ingest Modal */}
        {showIngestModal && (
          <IngestionModal
            sourceConfig={sourceConfig}
            selectedTimeRange={selectedTimeRange}
            setSelectedTimeRange={setSelectedTimeRange}
            ingesting={ingesting}
            currentJob={currentJob}
            ingestStatus={ingestStatus}
            onClose={() => setShowIngestModal(false)}
            onStartIngestion={handleIngestNow}
            onCancelIngestion={handleCancelIngestion}
            canRunAnalysis={canRunAnalysis}
          />
        )}

        {/* History Panel */}
        {showHistory && (
          <HistoryPanel
            analysisHistory={analysisHistory}
            selectedHistoryId={selectedHistoryId}
            onLoadHistory={loadFromHistory}
            onDeleteHistory={deleteFromHistory}
            onClose={() => setShowHistory(false)}
            formatTimestamp={formatTimestamp}
            getHealthColor={getHealthColor}
          />
        )}
      </div>
    );
  }

  const sortedQueries = getSortedQueries();
  const computedTotalTimeMs = sortedQueries.reduce(
    (sum, query) => sum + getTotalTimeMs(query),
    0,
  );
  const totalTimeMs =
    analysis.totalDatabaseTimeMs != null
      ? analysis.totalDatabaseTimeMs
      : computedTotalTimeMs;
  const totalTimeDisplay = formatTotalTime(totalTimeMs);
  const execCount =
    analysis.totalQueriesAnalyzed || analysis.totalSlowQueries || 0;
  const timeRangeLabel = getTimeRangeLabel(analysis.timeRange);
  const tableSet = new Set();
  sortedQueries.forEach((query) => {
    if (query?.affectedTables?.length) {
      query.affectedTables.forEach((table) => tableSet.add(table));
    }
  });
  const uniqueTablesCount = tableSet.size;
  const sortedByTime = [...sortedQueries].sort(
    (a, b) => getTotalTimeMs(b) - getTotalTimeMs(a),
  );
  const topCoverage = (count) => {
    if (!totalTimeMs) return 0;
    return (
      sortedByTime
        .slice(0, count)
        .reduce((sum, q) => sum + getTotalTimeMs(q), 0) / totalTimeMs
    );
  };
  const potentialWinPct = Math.round(topCoverage(5) * 100);
  const topImpactPct = Math.round(topCoverage(8) * 100);
  const topImpactCount = Math.min(8, sortedQueries.length);
  const displayLimit = showAllPatterns
    ? sortedQueries.length
    : Math.min(8, sortedQueries.length);
  const displayQueries = sortedQueries.slice(0, displayLimit);
  const remainingQueries = sortedQueries.slice(displayLimit);
  const remainingTimeMs = remainingQueries.reduce(
    (sum, query) => sum + getTotalTimeMs(query),
    0,
  );
  const remainingSharePct = totalTimeMs
    ? Math.round((remainingTimeMs / totalTimeMs) * 100)
    : 0;
  const remainingMedianMs = (() => {
    const values = remainingQueries
      .map((query) => query.avgExecutionTimeMs)
      .filter((value) => value != null)
      .sort((a, b) => a - b);
    if (!values.length) return null;
    const mid = Math.floor(values.length / 2);
    return values.length % 2 === 0
      ? (values[mid - 1] + values[mid]) / 2
      : values[mid];
  })();
  const selectedQuery =
    selectedQueryKey !== null
      ? sortedQueries.find(
          (query, idx) => getQueryKey(query, idx) === selectedQueryKey,
        )
      : null;
  const selectedFingerprint = selectedQuery?.queryId
    ? fingerprintByHash.get(selectedQuery.queryId)
    : null;
  const selectedOptimizationKey = selectedQuery
    ? getOptimizationKey(selectedQuery)
    : null;
  const selectedOptimization = selectedOptimizationKey
    ? optimizationResults[selectedOptimizationKey]
    : null;
  const isOptimizingSelected = selectedOptimizationKey
    ? optimizingQuery === selectedOptimizationKey
    : false;
  const selectedOptimizationSteps = selectedOptimizationKey
    ? (optimizationSteps[selectedOptimizationKey] ?? [])
    : [];
  const selectedImpactScore = selectedQuery ? getImpactScore(selectedQuery) : 0;
  const selectedTotalTimeMs = selectedQuery ? getTotalTimeMs(selectedQuery) : 0;
  const selectedTotalTimeDisplay = selectedQuery
    ? formatTotalTime(selectedTotalTimeMs)
    : null;
  const selectedP95 =
    selectedQuery?.p95ExecutionTimeMs ??
    selectedQuery?.maxExecutionTimeMs ??
    selectedQuery?.avgExecutionTimeMs;
  const selectedPatternTitle = getPatternTitle(selectedQuery);
  const selectedSummaryLine = selectedQuery
    ? `Impact Score ${selectedImpactScore.toFixed(0)} • ${selectedTotalTimeDisplay?.value || "—"}${selectedTotalTimeDisplay?.unit ? ` ${selectedTotalTimeDisplay.unit}` : ""} total time • p95 ${formatDuration(selectedP95)} • ${selectedQuery.callCount?.toLocaleString() || "—"} calls`
    : "";
  const selectedRowsExamined =
    selectedQuery?.rowsExamined ?? selectedQuery?.avgRowsExamined;
  const selectedRowsSent =
    selectedQuery?.rowsSent ?? selectedQuery?.avgRowsSent;
  const selectedTrendMeta = selectedQuery
    ? getTrendMeta(selectedQuery)
    : { label: "—", className: styles.trendNeutral };
  const recommendedFixes = (() => {
    const items = [];
    if (selectedOptimization?.suggestions?.length) {
      selectedOptimization.suggestions.forEach((sug) => {
        items.push({
          label: sug.category || "Fix",
          text: sug.title || sug.description || "Optimization suggestion",
        });
      });
    }
    if (selectedOptimization?.indexRecommendations?.length) {
      selectedOptimization.indexRecommendations.forEach((rec) => {
        items.push({ label: "Index", text: rec });
      });
    }
    if (!items.length && selectedQuery?.suggestedIndexes?.length) {
      selectedQuery.suggestedIndexes.forEach((rec) => {
        const columns = rec.columns?.length
          ? `(${rec.columns.join(", ")})`
          : "";
        const statement =
          rec.suggestedSQL ||
          `CREATE INDEX ON ${rec.tableName} ${columns}`.trim();
        items.push({ label: "Index", text: statement });
      });
    }
    if (!items.length && selectedQuery?.suggestions?.length) {
      selectedQuery.suggestions.forEach((sug) => {
        items.push({ label: "Fix", text: sug });
      });
    }
    if (!items.length) {
      items.push({
        label: "AI",
        text: "Run AI Optimize to generate fixes and index recommendations.",
      });
    }
    return items.slice(0, 3);
  })();
  const showTrendPanel = false;
  const maxLatencyMs = sortedQueries.reduce((max, query) => {
    const candidate = query.maxExecutionTimeMs ?? query.avgExecutionTimeMs ?? 0;
    return Math.max(max, candidate);
  }, 0);
  const aiSummaryLines = analysis.aiSummary
    ? analysis.aiSummary
        .split("\n")
        .map((line) => line.trim())
        .filter(Boolean)
    : [];
  const aiRecommendations = analysis.generalRecommendations || [];
  const ingestionConfigured = Boolean(sourceConfig?.id);
  const autoIngestLabel = ingestionConfigured
    ? sourceConfig.autoScheduleEnabled
      ? "Auto-ingest ON"
      : "Auto-ingest OFF"
    : "Set up source";
  const timeWindowLabel =
    analysis.timeRange === "LAST_24_HOURS" ? "today" : "in the selected window";
  const baselineTooltip =
    "Baseline: reference window used for regression comparisons.";
  const resetBaselineTooltip = selectedFingerprint
    ? "Reset baseline: sets today as the new reference for this pattern."
    : "Select a pattern to reset its baseline.";
  const sortLabelMap = {
    impactScore: "Impact Score",
    totalExecutionTimeMs: "Total Time",
    avgExecutionTimeMs: "Avg Latency",
    maxExecutionTimeMs: "Max Latency",
    callCount: "Calls",
  };
  const sortLabel = sortLabelMap[sortBy] || "Impact Score";

  return (
    <div className={styles.container}>
      <div className={styles.topBar}>
        <div className={styles.pillGroup}>
          <div className={styles.pill}>
            {timeRangeLabel} • {execCount.toLocaleString()} execs
          </div>
          <div className={styles.pill}>
            {sortedQueries.length} patterns • {uniqueTablesCount || 0} tables
          </div>
          <ActionGuard action="configure-source" mode="disable">
            <button
              className={`${styles.pillButton} btn-minimal`}
              onClick={() => setShowSourceModal(true)}
              title="Configure ingestion source"
            >
              <Settings size={14} />
              <span>
                {ingestionConfigured
                  ? "Ingestion configured"
                  : "Ingestion not configured"}
              </span>
              <span className={styles.pillMuted}>• {autoIngestLabel}</span>
              <span
                className={`${styles.pillDot} ${ingestionConfigured ? styles.pillDotOn : styles.pillDotOff}`}
              />
            </button>
          </ActionGuard>
        </div>
        <div className={styles.actionGroup}>
          <ActionGuard action="analyze-slow-queries">
            <button
              onClick={runFreshAnalysis}
              className={styles.iconButton}
              title="Run fresh slow query analysis"
            >
              <RefreshCw size={16} />
            </button>
          </ActionGuard>
          <button
            className={styles.iconButton}
            onClick={() => setShowFilters(!showFilters)}
            title="Filters"
          >
            <Filter size={16} />
          </button>
          {analysisHistory.length > 0 && (
            <button
              className={styles.iconButton}
              onClick={() => setShowHistory(!showHistory)}
              title={`View analysis history (${analysisHistory.length})`}
            >
              <History size={16} />
            </button>
          )}
          {analysisHistory.length >= 2 && (
            <button
              className={styles.iconButton}
              onClick={() => setShowCompareModal(true)}
              title="Compare Analyses"
            >
              <GitCompare size={16} />
            </button>
          )}
          <button
            className={`${styles.iconButton} ${showAlertPanel ? styles.iconButtonActive : ""}`}
            onClick={() => {
              setShowAlertPanel(!showAlertPanel);
              if (!alertSummary) refetchAlerts();
            }}
            title="Alerts"
          >
            <Bell size={16} />
            {alertSummary?.unacknowledgedCount > 0 && (
              <span className={styles.alertBadge}>
                {alertSummary.unacknowledgedCount}
              </span>
            )}
          </button>
          <ActionGuard action="upload-log">
            <button
              className={styles.iconButton}
              onClick={() => setShowUploadModal(true)}
              title="Upload slow query log file"
            >
              <Upload size={16} />
            </button>
          </ActionGuard>
          {analysis && (
            <button
              className={styles.iconButton}
              onClick={clearAnalysis}
              title="Clear results and start new analysis"
            >
              <X size={16} />
            </button>
          )}
        </div>
      </div>

      {sourceConfig?.id && (
        <div className={styles.dataStrip}>
          {activeJobLoading ? (
            <div className={styles.dataStripRow}>
              <Loader2 size={16} className={styles.spinner} />
              <span>Checking for active ingestion...</span>
            </div>
          ) : effectiveIngesting && effectiveCurrentJob ? (
            <div className={styles.dataStripRow}>
              <div className={styles.dataStripMeta}>
                <Loader2 size={16} className={styles.spinner} />
                <div>
                  <strong>
                    {effectiveCurrentJob.currentStep || "Processing..."}
                  </strong>
                  <span>{effectiveCurrentJob.progressPct || 0}%</span>
                </div>
              </div>
              <div className={styles.progressBarContainer}>
                <div
                  className={styles.progressBar}
                  style={{ width: `${effectiveCurrentJob.progressPct || 0}%` }}
                />
              </div>
              <div className={styles.dataStripStats}>
                <span>
                  {(effectiveCurrentJob.eventsProcessed || 0).toLocaleString()}{" "}
                  events
                </span>
                <span>
                  {(effectiveCurrentJob.slowQueriesFound || 0).toLocaleString()}{" "}
                  queries
                </span>
                <span>
                  {formatBytes(effectiveCurrentJob.bytesProcessed || 0)}
                </span>
              </div>
              {canRunAnalysis && (
                <button
                  onClick={handleCancelIngestion}
                  className={styles.dataStripDanger}
                >
                  <X size={12} />
                  Cancel
                </button>
              )}
            </div>
          ) : (
            <div className={styles.dataStripRow}>
              <div className={styles.dataStripMeta}>
                <Database size={16} />
                <div>
                  <strong>{getProviderLabel(sourceConfig.providerType)}</strong>
                  <span className={styles.dataStripMuted}>
                    {getProviderSource(sourceConfig)}
                  </span>
                  <span className={styles.dataStripMuted}>
                    {sourceConfig.lastProcessedAt
                      ? `Last ingested ${formatTimestamp(sourceConfig.lastProcessedAt)}`
                      : "Never ingested"}
                  </span>
                </div>
              </div>
              <div className={styles.dataStripActions}>
                <button
                  onClick={toggleAutoSchedule}
                  disabled={!canRunAnalysis}
                  className={styles.dataStripToggle}
                  title={
                    !canRunAnalysis
                      ? "You need Editor or Admin role to change this"
                      : ""
                  }
                >
                  {sourceConfig.autoScheduleEnabled
                    ? "Auto-ingest ON"
                    : "Auto-ingest OFF"}
                </button>
                <button
                  onClick={openIngestModal}
                  disabled={!canRunAnalysis}
                  className={styles.dataStripPrimary}
                >
                  {!canRunAnalysis ? (
                    <Lock size={14} />
                  ) : (
                    <RefreshCw size={14} />
                  )}
                  {!canRunAnalysis ? "View Only" : "Ingest Now"}
                </button>
                <button
                  onClick={() => setShowSourceModal(true)}
                  disabled={!canRunAnalysis}
                  className={styles.dataStripIcon}
                  title={
                    !canRunAnalysis
                      ? "You need Editor or Admin role to configure"
                      : "Configure source"
                  }
                >
                  {!canRunAnalysis ? (
                    <Lock size={14} />
                  ) : (
                    <Settings size={14} />
                  )}
                </button>
              </div>
            </div>
          )}
        </div>
      )}

      {showFilters && (
        <FilterPanel
          timeRange={timeRange}
          setTimeRange={setTimeRange}
          thresholdMs={thresholdMs}
          setThresholdMs={setThresholdMs}
          limit={limit}
          setLimit={setLimit}
          onApply={fetchAnalysis}
        />
      )}

      {showHistory && (
        <HistoryPanel
          history={analysisHistory}
          onClose={() => setShowHistory(false)}
          onLoadHistory={loadFromHistory}
          onDeleteHistory={deleteFromHistory}
          getHealthColor={getHealthColor}
          canRunAnalysis={canRunAnalysis}
        />
      )}

      {showAlertPanel && (
        <AlertPanel
          alertSummary={alertSummary}
          loading={loadingAlerts}
          onClose={() => setShowAlertPanel(false)}
          onAcknowledgeAlert={handleAcknowledgeSlowQueryAlert}
          onAcknowledgeAll={handleAcknowledgeAllAlerts}
          getSeverityColor={getSeverityColor}
          canRunAnalysis={canRunAnalysis}
        />
      )}

      {showRegressionPanel && (
        <RegressionPanel
          regressions={regressions}
          onAcknowledge={handleAcknowledgeRegression}
          onResolve={handleResolveRegression}
          getSeverityColor={getSeverityColor}
          canRunAnalysis={canRunAnalysis}
        />
      )}

      <div className={styles.heroGrid}>
        <div className={styles.heroCard}>
          <div className={styles.impactLine}>
            <h2>
              {totalTimeDisplay.value} {totalTimeDisplay.unit}
            </h2>
            <span>of DB time lost to slow queries {timeWindowLabel}</span>
          </div>
          <div className={styles.heroStats}>
            <div className={styles.statCard}>
              <div className={styles.statLabel}>Potential Win</div>
              <div className={styles.statValue}>{potentialWinPct || 0}%</div>
            </div>
            <div className={styles.statCard}>
              <div className={styles.statLabel}>Max Latency</div>
              <div className={styles.statValue}>
                {maxLatencyMs ? formatDuration(maxLatencyMs) : "—"}
              </div>
            </div>
            <div className={styles.statCard}>
              <div className={styles.statLabel}>
                Top {topImpactCount} Patterns
              </div>
              <div className={styles.statValue}>
                {topImpactPct || 0}% impact
              </div>
            </div>
          </div>
        </div>
        <div className={styles.heroCard}>
          <h3>AI-Powered Analysis</h3>
          <div className={styles.callout}>
            {aiSummaryLines.length > 0
              ? aiSummaryLines.map((line, idx) => <p key={idx}>{line}</p>)
              : "No AI summary available for this run."}
          </div>
          {aiRecommendations.length > 0 && (
            <div className={styles.aiRecommendations}>
              {aiRecommendations.map((rec, idx) => (
                <div key={idx} className={styles.aiRecommendationItem}>
                  <Info size={14} />
                  <span>{rec}</span>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>

      <div className={styles.patternsCard}>
        <div className={styles.patternsHeader}>
          <div>
            <h3>Top Impact Patterns</h3>
            <div className={styles.infoInline}>
              <span className={styles.infoIcon}>i</span>
              <span className={styles.tooltip}>
                Impact score = weighted blend of total time, frequency,
                regression, and rows scanned.
              </span>
            </div>
          </div>
          <div className={styles.patternControls}>
            <div className={styles.pill}>Sorted by {sortLabel}</div>
            <select
              value={sortBy}
              onChange={handleSortChange}
              className={styles.sortSelect}
            >
              <option value="impactScore">Impact score</option>
              <option value="totalExecutionTimeMs">Total time</option>
              <option value="avgExecutionTimeMs">Avg latency</option>
              <option value="maxExecutionTimeMs">Max latency</option>
              <option value="callCount">Calls</option>
            </select>
          </div>
        </div>

        {sortedQueries.length > 0 ? (
          <table className={styles.patternTable}>
            <thead>
              <tr>
                <th>Pattern</th>
                <th>Impact</th>
                <th>Total Time</th>
                <th>p95</th>
                <th>Calls</th>
                <th>Trend</th>
              </tr>
            </thead>
            <tbody>
              {displayQueries.map((query, idx) => {
                const queryKey = getQueryKey(query, idx);
                const impactScore = getImpactScore(query);
                const totalTime = getTotalTimeMs(query);
                const trend = getTrendMeta(query);
                const tags = getPatternTags(query);
                return (
                  <tr
                    key={queryKey}
                    className={
                      queryKey === selectedQueryKey
                        ? styles.patternRowSelected
                        : styles.patternRow
                    }
                    role="button"
                    tabIndex={0}
                    onClick={() => handleSelectQuery(queryKey)}
                    onKeyDown={(event) => {
                      if (event.key === "Enter" || event.key === " ") {
                        event.preventDefault();
                        handleSelectQuery(queryKey);
                      }
                    }}
                  >
                    <td>
                      <div className={styles.patternTitle}>
                        {getQueryPreview(
                          query.normalizedQuery || query.queryText,
                        )}
                      </div>
                      {tags.length > 0 && (
                        <div className={styles.patternTags}>
                          {tags.map((tag) => (
                            <span key={tag} className={styles.patternTag}>
                              {tag}
                            </span>
                          ))}
                        </div>
                      )}
                    </td>
                    <td className={styles.patternScore}>
                      {impactScore.toFixed(0)}
                    </td>
                    <td>{formatDuration(totalTime)}</td>
                    <td>
                      {formatDuration(
                        query.maxExecutionTimeMs || query.avgExecutionTimeMs,
                      )}
                    </td>
                    <td>{query.callCount?.toLocaleString() || "—"}</td>
                    <td className={trend.className}>{trend.label}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        ) : (
          <div className={styles.noQueries}>
            <CheckCircle size={48} />
            <h3>No Slow Queries Found</h3>
            <p>
              Your database is performing well! No queries exceed the{" "}
              {thresholdMs}ms threshold.
            </p>
          </div>
        )}

        {remainingQueries.length > 0 && !showAllPatterns && (
          <div className={styles.longTail}>
            Remaining {remainingQueries.length} patterns = {remainingSharePct}%
            time
            {remainingMedianMs != null &&
              ` • median ${formatDuration(remainingMedianMs)}`}
          </div>
        )}

        <div className={styles.ctaRow}>
          {sortedQueries.length > displayLimit && (
            <button
              className={styles.ghostButton}
              onClick={() => setShowAllPatterns(true)}
            >
              Show all {sortedQueries.length} patterns
            </button>
          )}
          {showAllPatterns && sortedQueries.length > 8 && (
            <button
              className={styles.ghostButton}
              onClick={() => setShowAllPatterns(false)}
            >
              Show top patterns
            </button>
          )}
          <button
            className={styles.primaryButton}
            onClick={handleBatchOptimize}
            disabled={batchOptimizeMutation.isPending}
          >
            {batchOptimizeMutation.isPending
              ? "Generating fixes…"
              : "Generate top 5 fixes"}
          </button>
        </div>
      </div>

      {/* Enhanced Query Detail Dialog */}
      <QueryDetailDialog
        query={
          selectedQuery
            ? {
                ...selectedQuery,
                queryText:
                  selectedQuery.normalizedQuery || selectedQuery.queryText,
                avgDurationMs: selectedQuery.avgExecutionTimeMs,
                maxDurationMs: selectedQuery.maxExecutionTimeMs,
                callCount: selectedQuery.callCount,
                impactScore: selectedImpactScore,
                tables: selectedQuery.affectedTables,
              }
            : null
        }
        open={selectedQuery !== null}
        onOpenChange={(open) => {
          if (!open) setSelectedQueryKey(null);
        }}
        onOptimize={handleOptimizeQuery}
        onBenchmarkCandidates={handleBenchmarkCandidates}
        optimization={selectedOptimization}
        optimizationCandidates={optimizationCandidatesData}
        isBenchmarkingCandidates={isBenchmarkingCandidates}
        isOptimizing={isOptimizingSelected}
        optimizationSteps={selectedOptimizationSteps}
        fingerprintData={selectedFingerprint}
      />

      {showCompareModal && (
        <CompareModal
          history={analysisHistory}
          onClose={() => {
            setShowCompareModal(false);
            setComparisonResult(null);
          }}
          onCompare={handleCompareAnalyses}
          comparisonResult={comparisonResult}
          loading={loadingComparison}
          getTrendColor={getTrendColor}
        />
      )}

      {showUploadModal && (
        <UploadModal
          onClose={() => setShowUploadModal(false)}
          onFileUpload={handleFileUpload}
          onS3Analyze={handleS3Analyze}
          uploading={uploading}
          uploadLabel={getUploadLabel()}
          canRunAnalysis={canRunAnalysis}
        />
      )}

      {showSourceModal && (
        <SourceConfigModal
          sourceConfig={sourceConfig}
          setSourceConfig={setSourceConfig}
          onClose={() => setShowSourceModal(false)}
          onSave={handleSaveSourceConfig}
          onIngestNow={() => {
            setShowSourceModal(false);
            openIngestModal();
          }}
          onShowIamPolicy={() => setShowIamPolicyModal(true)}
          ingestStatus={ingestStatus}
          canRunAnalysis={canRunAnalysis}
        />
      )}

      {showIamPolicyModal && (
        <IamPolicyModal
          sourceConfig={sourceConfig}
          onClose={() => setShowIamPolicyModal(false)}
        />
      )}

      {showIngestModal && (
        <IngestionModal
          sourceConfig={sourceConfig}
          selectedTimeRange={selectedTimeRange}
          setSelectedTimeRange={setSelectedTimeRange}
          ingesting={ingesting}
          currentJob={currentJob}
          ingestStatus={ingestStatus}
          onClose={() => setShowIngestModal(false)}
          onStartIngestion={handleIngestNow}
          onCancelIngestion={handleCancelIngestion}
          showDetails={true}
          canRunAnalysis={canRunAnalysis}
        />
      )}
    </div>
  );
}
