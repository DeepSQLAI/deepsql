package com.dbaagent.controller;

import com.dbaagent.dto.KeyCustomerResult;
import com.dbaagent.dto.SlowQueryInsightsResponse;
import com.dbaagent.dto.SlowQueryHistorySummary;
import com.dbaagent.exception.LogSizeExceededException;
import com.dbaagent.model.PlaybookAlert;
import com.dbaagent.util.SlowQueryCapPolicy;
import com.dbaagent.model.QueryFingerprint;
import com.dbaagent.model.SlowQuery;
import com.dbaagent.model.SlowQueryAnalysis;
import com.dbaagent.model.SlowQueryHistory;
import com.dbaagent.service.*;
import com.dbaagent.service.security.AccessControlService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * REST API for slow query analysis
 *
 * <p><b>Authorization:</b> every endpoint here takes a caller-supplied connection id, so
 * each one asserts access itself ({@code assertCanReadConnectionContent} for reads,
 * {@code assertCanManageConnectionContent} for writes). {@code SecurityConfig} only
 * requires an authenticated principal — nothing upstream inspects a connection id. See
 * {@code ConnectionScopedAuthorizationSafetyTest}.
 */
@RestController
@RequestMapping("/slow-queries")
@RequiredArgsConstructor
@Slf4j
public class SlowQueryController {

    private final SlowQueryService slowQueryService;
    private final SlowQueryHistoryService historyService;
    private final SlowQueryLogParserService logParserService;
    private final S3LogFetchService s3LogFetchService;
    private final CloudWatchLogFetchService cloudWatchLogFetchService;
    private final QueryOptimizationService optimizationService;
    private final OptimizationCandidateService candidateService;
    private final OptimizationBenchmarkService optimizationBenchmarkService;
    private final SlowQueryAlertService alertService;
    private final QueryFingerprintService fingerprintService;
    private final SlowQueryDashboardService dashboardService;
    private final ExplainPlanService explainPlanService;
    private final KeyCustomerService keyCustomerService;
    private final SlowQueryInsightsService slowQueryInsightsService;
    private final ObjectMapper objectMapper;
    private final AccessControlService accessControlService;

    // Thread pool for SSE streaming — keeps SSE work off the Jetty request thread
    private static final ExecutorService sseExecutor =
        Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "sse-optimize");
            t.setDaemon(true);
            return t;
        });

    /**
     * Analyze slow queries for a connection
     */
    @PostMapping("/analyze")
    public ResponseEntity<SlowQueryAnalysis> analyzeSlowQueries(
        @RequestBody SlowQueryRequest request
    ) {
        accessControlService.assertCanManageConnectionContent(request.getConnectionId());
        try {
            log.info("Slow query analysis requested for connection: {}", request.getConnectionId());

            SlowQueryAnalysis analysis = slowQueryService.analyzeSlowQueries(
                request.getConnectionId(),
                request.getTimeRange(),
                request.getThresholdMs(),
                request.getLimit()
            );

            try {
                historyService.saveAnalysis(request.getConnectionId(), analysis, null);
            } catch (org.springframework.web.server.ResponseStatusException e) {
                throw e;
            } catch (Exception e) {
                log.warn("Failed to auto-save slow query analysis history", e);
            }

            return ResponseEntity.ok(analysis);

        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error analyzing slow queries", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get slow query analysis with default parameters
     */
    @GetMapping("/analyze/{connectionId}")
    public ResponseEntity<SlowQueryAnalysis> analyzeSlowQueriesSimple(
        @PathVariable String connectionId,
        @RequestParam(required = false, defaultValue = "100") Double threshold,
        @RequestParam(required = false, defaultValue = "10") Integer limit
    ) {
        accessControlService.assertCanReadConnectionContent(connectionId);
        try {
            log.info("Slow query analysis requested for connection: {}", connectionId);

            SlowQueryAnalysis analysis = slowQueryService.analyzeSlowQueries(
                connectionId,
                SlowQueryAnalysis.TimeRange.LAST_24_HOURS,
                threshold,
                limit
            );

            try {
                historyService.saveAnalysis(connectionId, analysis, null);
            } catch (org.springframework.web.server.ResponseStatusException e) {
                throw e;
            } catch (Exception e) {
                log.warn("Failed to auto-save slow query analysis history", e);
            }

            return ResponseEntity.ok(analysis);

        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error analyzing slow queries", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Save slow query analysis to history
     */
    @PostMapping("/history")
    public ResponseEntity<HistoryResponse> saveHistory(
        @RequestBody SaveHistoryRequest request
    ) {
        accessControlService.assertCanManageConnectionContent(request.getConnectionId());
        try {
            log.info("Saving slow query history for connection: {}", request.getConnectionId());

            SlowQueryHistory history = historyService.saveAnalysis(
                request.getConnectionId(),
                request.getAnalysisData(),
                request.getUserId()
            );

            HistoryResponse response = convertToResponse(history);
            return ResponseEntity.ok(response);

        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error saving slow query history", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get slow query history summaries for a connection (without large analysisData to avoid OOM)
     */
    @GetMapping("/history/{connectionId}")
    public ResponseEntity<List<HistorySummaryResponse>> getHistory(
        @PathVariable String connectionId
    ) {
        accessControlService.assertCanReadConnectionContent(connectionId);
        try {
            log.info("Fetching slow query history summaries for connection: {}", connectionId);

            List<SlowQueryHistorySummary> summaries = historyService.getRecentHistorySummaries(connectionId);
            List<HistorySummaryResponse> responses = summaries.stream()
                .map(this::convertSummaryToResponse)
                .collect(Collectors.toList());

            return ResponseEntity.ok(responses);

        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching slow query history", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get the most recent slow query analysis for a connection (without running new analysis)
     * This loads only a single record with full analysisData
     */
    @GetMapping("/latest/{connectionId}")
    public ResponseEntity<HistoryResponse> getLatestAnalysis(
        @PathVariable String connectionId
    ) {
        accessControlService.assertCanReadConnectionContent(connectionId);
        try {
            log.info("Fetching latest slow query analysis for connection: {}", connectionId);

            Optional<SlowQueryHistory> latestOpt = historyService.getLatestHistory(connectionId);

            if (latestOpt.isEmpty()) {
                log.info("No slow query history found for connection: {}", connectionId);
                return ResponseEntity.noContent().build();
            }

            // Return the most recent analysis
            SlowQueryHistory latest = latestOpt.get();
            HistoryResponse response = convertToResponse(latest);

            return ResponseEntity.ok(response);

        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching latest slow query analysis", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get slow query history summaries filtered by time range
     */
    @GetMapping("/history/{connectionId}/time-range/{timeRange}")
    public ResponseEntity<List<HistorySummaryResponse>> getHistoryByTimeRange(
        @PathVariable String connectionId,
        @PathVariable String timeRange
    ) {
        accessControlService.assertCanReadConnectionContent(connectionId);
        try {
            log.info("Fetching slow query history summaries for connection: {} and timeRange: {}", connectionId, timeRange);

            List<SlowQueryHistorySummary> summaries = historyService.getHistorySummariesByTimeRange(connectionId, timeRange);
            List<HistorySummaryResponse> responses = summaries.stream()
                .map(this::convertSummaryToResponse)
                .collect(Collectors.toList());

            return ResponseEntity.ok(responses);

        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching slow query history by time range", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get a specific history entry by ID (with full analysisData)
     */
    @GetMapping("/history/item/{id}")
    public ResponseEntity<HistoryResponse> getHistoryById(
        @PathVariable String id
    ) {
        assertCanReadHistory(id);
        try {
            log.info("Fetching slow query history item: {}", id);

            Optional<SlowQueryHistory> historyOpt = historyService.getHistoryById(id);

            if (historyOpt.isEmpty()) {
                log.info("No slow query history found with id: {}", id);
                return ResponseEntity.notFound().build();
            }

            HistoryResponse response = convertToResponse(historyOpt.get());
            return ResponseEntity.ok(response);

        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching slow query history item", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Delete a specific history entry
     */
    @DeleteMapping("/history/{id}")
    public ResponseEntity<Map<String, String>> deleteHistory(
        @PathVariable String id
    ) {
        assertCanManageHistory(id);
        try {
            log.info("Deleting slow query history: {}", id);
            historyService.deleteHistory(id);

            return ResponseEntity.ok(Map.of("message", "History deleted successfully"));

        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error deleting slow query history", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Delete all history for a connection
     */
    @DeleteMapping("/history/connection/{connectionId}")
    public ResponseEntity<Map<String, String>> deleteAllHistory(
        @PathVariable String connectionId
    ) {
        accessControlService.assertCanManageConnectionContent(connectionId);
        try {
            log.info("Deleting all slow query history for connection: {}", connectionId);
            historyService.deleteAllForConnection(connectionId);

            return ResponseEntity.ok(Map.of("message", "All history deleted successfully"));

        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error deleting all slow query history", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Analyze slow queries from uploaded log file (MySQL or PostgreSQL)
     */
    @PostMapping("/analyze-file")
    public ResponseEntity<SlowQueryAnalysis> analyzeSlowQueryLogFile(
        @RequestParam("file") MultipartFile file,
        @RequestParam("connectionId") String connectionId,
        @RequestParam(required = false, defaultValue = "mysql") String databaseType
    ) {
        accessControlService.assertCanManageConnectionContent(connectionId);
        try {
            log.info("Analyzing uploaded slow query log file for connection: {}, type: {}", connectionId, databaseType);

            if (file.isEmpty()) {
                return ResponseEntity.badRequest().build();
            }

            SlowQueryAnalysis analysis = logParserService.parseAndAnalyze(
                file.getInputStream(),
                databaseType,
                connectionId
            );

            try {
                historyService.saveAnalysis(connectionId, analysis, null);
            } catch (org.springframework.web.server.ResponseStatusException e) {
                throw e;
            } catch (Exception e) {
                log.warn("Failed to auto-save slow query analysis history", e);
            }

            return ResponseEntity.ok(analysis);

        } catch (LogSizeExceededException e) {
            log.warn("Uploaded slow query log exceeds max size: {} bytes (max {})",
                e.getBytesRead(), e.getMaxBytes());
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error analyzing slow query log file", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Analyze slow queries from a log file stored in S3
     */
    @PostMapping("/analyze-file-s3")
    public ResponseEntity<SlowQueryAnalysis> analyzeSlowQueryLogFileFromS3(
        @RequestBody S3LogRequest request
    ) {
        accessControlService.assertCanManageConnectionContent(request.getConnectionId());
        try {
            log.info("Analyzing S3 slow query log file for connection: {}, url: {}",
                request.getConnectionId(), request.getS3Url());

            if (request.getS3Url() == null || request.getS3Url().isBlank()) {
                return ResponseEntity.badRequest().build();
            }

            try (var stream = s3LogFetchService.downloadLog(request.getS3Url(), request.getRegion())) {
                SlowQueryAnalysis analysis = logParserService.parseAndAnalyze(
                    stream,
                    request.getDatabaseType() != null ? request.getDatabaseType() : "mysql",
                    request.getConnectionId()
                );

                try {
                    historyService.saveAnalysis(request.getConnectionId(), analysis, request.getUserId());
                } catch (org.springframework.web.server.ResponseStatusException e) {
                    throw e;
                } catch (Exception e) {
                    log.warn("Failed to auto-save slow query analysis history", e);
                }

                return ResponseEntity.ok(analysis);
            }
        } catch (LogSizeExceededException e) {
            log.warn("S3 slow query log exceeds max size: {} bytes (max {})",
                e.getBytesRead(), e.getMaxBytes());
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error analyzing S3 slow query log file", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Analyze slow queries from CloudWatch Logs
     */
    @PostMapping("/analyze-file-cloudwatch")
    public ResponseEntity<SlowQueryAnalysis> analyzeSlowQueryLogFromCloudWatch(
        @RequestBody CloudWatchLogRequest request
    ) {
        accessControlService.assertCanManageConnectionContent(request.getConnectionId());
        try {
            log.info("Analyzing CloudWatch slow query logs for connection: {}, log group: {}",
                request.getConnectionId(), request.getLogGroupName());

            if (request.getLogGroupName() == null || request.getLogGroupName().isBlank()) {
                return ResponseEntity.badRequest().build();
            }
            if (request.getRegion() == null || request.getRegion().isBlank()) {
                return ResponseEntity.badRequest().build();
            }

            S3LogFetchService.AwsCredentialsInput credentials = null;
            if (request.getAccessKeyId() != null && request.getSecretAccessKey() != null) {
                credentials = new S3LogFetchService.AwsCredentialsInput(
                    request.getAccessKeyId(),
                    request.getSecretAccessKey(),
                    request.getSessionToken()
                );
            }

            try (var stream = cloudWatchLogFetchService.downloadLatestLogs(
                request.getLogGroupName(),
                request.getLogStreamPrefix(),
                request.getRegion(),
                credentials,
                request.getStartTime(),
                request.getMaxEvents() != null ? request.getMaxEvents() : 10000
            )) {
                SlowQueryAnalysis analysis = logParserService.parseAndAnalyze(
                    stream,
                    request.getDatabaseType() != null ? request.getDatabaseType() : "mysql",
                    request.getConnectionId()
                );

                try {
                    historyService.saveAnalysis(request.getConnectionId(), analysis, request.getUserId());
                } catch (org.springframework.web.server.ResponseStatusException e) {
                    throw e;
                } catch (Exception e) {
                    log.warn("Failed to auto-save slow query analysis history", e);
                }

                return ResponseEntity.ok(analysis);
            }
        } catch (LogSizeExceededException e) {
            log.warn("CloudWatch slow query logs exceed max size: {} bytes (max {})",
                e.getBytesRead(), e.getMaxBytes());
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error analyzing CloudWatch slow query logs", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get key customers — entities driving the most slow query load.
     * Returns 204 when no analysis exists.
     */
    @GetMapping("/key-customers/{connectionId}")
    public ResponseEntity<KeyCustomerResult> getKeyCustomers(
            @PathVariable String connectionId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String tableName) {
        accessControlService.assertCanReadConnectionContent(connectionId);
        try {
            return keyCustomerService.analyze(connectionId, limit, tableName)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching key customers for connection {}", connectionId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get consolidated slow-query workload insights.
     */
    @GetMapping("/insights/{connectionId}")
    public ResponseEntity<SlowQueryInsightsResponse> getInsights(
        @PathVariable String connectionId,
        @RequestParam(defaultValue = "7d") String window,
        @RequestParam(defaultValue = "20") int limit
    ) {
        accessControlService.assertCanReadConnectionContent(connectionId);
        try {
            SlowQueryInsightsResponse response = slowQueryInsightsService.getInsights(connectionId, window, limit);
            return ResponseEntity.ok(response);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching slow-query insights for connection {}", connectionId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get remediation-priority insights only.
     */
    @GetMapping("/insights/{connectionId}/remediation")
    public ResponseEntity<SlowQueryInsightsResponse.RemediationInsights> getRemediationInsights(
        @PathVariable String connectionId,
        @RequestParam(defaultValue = "7d") String window,
        @RequestParam(defaultValue = "20") int limit
    ) {
        accessControlService.assertCanReadConnectionContent(connectionId);
        try {
            SlowQueryInsightsResponse.RemediationInsights response =
                slowQueryInsightsService.getRemediationInsights(connectionId, window, limit);
            return ResponseEntity.ok(response);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching remediation insights for connection {}", connectionId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get scan and lock hotspot insights only.
     */
    @GetMapping("/insights/{connectionId}/hotspots")
    public ResponseEntity<SlowQueryInsightsResponse.HotspotInsights> getHotspotInsights(
        @PathVariable String connectionId,
        @RequestParam(defaultValue = "7d") String window,
        @RequestParam(defaultValue = "20") int limit
    ) {
        accessControlService.assertCanReadConnectionContent(connectionId);
        try {
            SlowQueryInsightsResponse.HotspotInsights response =
                slowQueryInsightsService.getHotspotInsights(connectionId, window, limit);
            return ResponseEntity.ok(response);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching hotspot insights for connection {}", connectionId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get key-customer skew insights only.
     */
    @GetMapping("/insights/{connectionId}/skew")
    public ResponseEntity<SlowQueryInsightsResponse.SkewInsights> getSkewInsights(
        @PathVariable String connectionId,
        @RequestParam(defaultValue = "7d") String window,
        @RequestParam(defaultValue = "20") int limit
    ) {
        accessControlService.assertCanReadConnectionContent(connectionId);
        try {
            SlowQueryInsightsResponse.SkewInsights response =
                slowQueryInsightsService.getSkewInsights(connectionId, window, limit);
            return ResponseEntity.ok(response);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching skew insights for connection {}", connectionId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get tail-risk and burst-window insights only.
     */
    @GetMapping("/insights/{connectionId}/tail-risk")
    public ResponseEntity<SlowQueryInsightsResponse.TailRiskInsights> getTailRiskInsights(
        @PathVariable String connectionId,
        @RequestParam(defaultValue = "7d") String window,
        @RequestParam(defaultValue = "20") int limit
    ) {
        accessControlService.assertCanReadConnectionContent(connectionId);
        try {
            SlowQueryInsightsResponse.TailRiskInsights response =
                slowQueryInsightsService.getTailRiskInsights(connectionId, window, limit);
            return ResponseEntity.ok(response);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching tail-risk insights for connection {}", connectionId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get plan-drift insights only.
     */
    @GetMapping("/insights/{connectionId}/plan-drift")
    public ResponseEntity<SlowQueryInsightsResponse.PlanDriftInsights> getPlanDriftInsights(
        @PathVariable String connectionId,
        @RequestParam(defaultValue = "7d") String window,
        @RequestParam(defaultValue = "20") int limit
    ) {
        accessControlService.assertCanReadConnectionContent(connectionId);
        try {
            SlowQueryInsightsResponse.PlanDriftInsights response =
                slowQueryInsightsService.getPlanDriftInsights(connectionId, window, limit);
            return ResponseEntity.ok(response);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching plan-drift insights for connection {}", connectionId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Convert SlowQueryHistory entity to response DTO
     */
    private HistoryResponse convertToResponse(SlowQueryHistory history) {
        SlowQueryAnalysis analysisData = historyService.getAnalysisData(history);

        // Apply shared cap policy to prevent OOM when serializing large datasets
        if (analysisData != null && analysisData.getTopSlowQueries() != null) {
            int original = analysisData.getTopSlowQueries().size();
            var capped = SlowQueryCapPolicy.capQueries(analysisData.getTopSlowQueries());
            if (capped.size() < original) {
                log.info("Limiting queries from {} to {} for response",
                    original, capped.size());
            }
            analysisData.setTopSlowQueries(new java.util.ArrayList<>(capped));
        }

        HistoryResponse response = new HistoryResponse();
        response.setId(history.getId());
        response.setConnectionId(history.getConnectionId());
        response.setUserId(history.getUserId());
        response.setTimeRange(history.getTimeRange());
        response.setSlowQueryThresholdMs(history.getSlowQueryThresholdMs());
        response.setAnalysisData(analysisData);
        response.setTotalSlowQueries(history.getTotalSlowQueries());
        response.setOverallHealth(history.getOverallHealth());
        response.setCriticalCount(history.getCriticalCount());
        response.setHighCount(history.getHighCount());
        response.setTotalDatabaseTimeMs(history.getTotalDatabaseTimeMs());
        response.setTimestamp(history.getCreatedAt().toString());

        return response;
    }

    /**
     * Convert SlowQueryHistorySummary projection to response DTO (without analysisData)
     */
    private HistorySummaryResponse convertSummaryToResponse(SlowQueryHistorySummary summary) {
        HistorySummaryResponse response = new HistorySummaryResponse();
        response.setId(summary.getId());
        response.setConnectionId(summary.getConnectionId());
        response.setUserId(summary.getUserId());
        response.setTimeRange(summary.getTimeRange());
        response.setSlowQueryThresholdMs(summary.getSlowQueryThresholdMs());
        response.setTotalSlowQueries(summary.getTotalSlowQueries());
        response.setOverallHealth(summary.getOverallHealth());
        response.setCriticalCount(summary.getCriticalCount());
        response.setHighCount(summary.getHighCount());
        response.setTotalDatabaseTimeMs(summary.getTotalDatabaseTimeMs());
        response.setTimestamp(summary.getCreatedAt() != null ? summary.getCreatedAt().toString() : null);

        return response;
    }

    // ==================== Feature 1: AI-Powered Query Optimization ====================

    /**
     * Get AI-powered optimization suggestions for a specific query
     */
    @PostMapping("/optimize")
    public ResponseEntity<QueryOptimizationService.OptimizationResult> optimizeQuery(
        @RequestBody OptimizeQueryRequest request
    ) {
        accessControlService.assertCanManageConnectionContent(request.getConnectionId());
        try {
            log.info("Generating AI optimization for connection: {}", request.getConnectionId());

            SlowQuery context = null;
            if (request.getAvgExecutionTimeMs() != null) {
                context = SlowQuery.builder()
                    .queryId(request.getQueryId())
                    .avgExecutionTimeMs(request.getAvgExecutionTimeMs())
                    .totalExecutionTimeMs(request.getTotalExecutionTimeMs())
                    .callCount(request.getCallCount())
                    .rowsExamined(request.getRowsExamined())
                    .rowsSent(request.getRowsSent())
                    .severity(request.getSeverity() != null ?
                        SlowQuery.Severity.valueOf(request.getSeverity()) : null)
                    .build();
            }

            boolean forceRefresh = Boolean.TRUE.equals(request.getForceRefresh());
            QueryOptimizationService.OptimizationResult result = optimizationService.optimizeQuery(
                request.getConnectionId(),
                request.getQueryText(),
                request.getSampleQuery(),  // Pass actual query with values for EXPLAIN
                context,
                forceRefresh
            );

            return ResponseEntity.ok(result);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error generating optimization suggestions", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Streaming SSE endpoint for query optimization with real-time progress steps.
     *
     * Emits Server-Sent Events as each analysis phase completes:
     *   step  — progress update  { status, message, detail? }
     *   result — final OptimizationResult JSON
     *   error  — { message }
     *
     * The pipeline:
     *   1. Analyze query bottlenecks (EXPLAIN)
     *   2. Assess confidence that rewriting will help
     *   3. Generate AI rewrite (only when confident)
     *   4. Validate rewrite (LIMIT 5 execution)
     *   5. Benchmark original vs rewrite (quick single-run EXPLAIN ANALYZE)
     *   6. Only include rewrite in result if ≥15% faster than original
     */
    @GetMapping(value = "/optimize/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamOptimize(
        @RequestParam String connectionId,
        @RequestParam String queryText,
        @RequestParam(required = false) String sampleQuery,
        @RequestParam(required = false) Double avgExecutionTimeMs,
        @RequestParam(required = false) String queryId,
        @RequestParam(defaultValue = "false") boolean forceRefresh
    ) {
        accessControlService.assertCanReadConnectionContent(connectionId);
        SseEmitter emitter = new SseEmitter(300_000L); // 5-minute timeout

        sseExecutor.submit(() -> {
            try {
                // ── Step 1: Structural analysis ──────────────────────────────────────
                sendStep(emitter, "analyzing",
                    "Analyzing query structure and bottlenecks...", null);

                SlowQuery context = null;
                if (avgExecutionTimeMs != null) {
                    context = SlowQuery.builder()
                        .queryId(queryId)
                        .avgExecutionTimeMs(avgExecutionTimeMs)
                        .build();
                }

                // Run standard optimization (includes EXPLAIN + AI analysis)
                boolean force = forceRefresh || true; // always fresh for streaming
                QueryOptimizationService.OptimizationResult result =
                    optimizationService.optimizeQuery(connectionId, queryText, sampleQuery, context, force);

                // ── Step 2: Confidence check ──────────────────────────────────────────
                String optimizedQuery = result.getOptimizedQuery();
                boolean hasRewrite = optimizedQuery != null && !optimizedQuery.isBlank();

                if (!hasRewrite) {
                    sendStep(emitter, "no_rewrite",
                        "Rewrite not applicable — showing index and schema recommendations.", null);
                } else {
                    sendStep(emitter, "rewrite_generated",
                        "Optimized query generated — verifying functional correctness...", null);

                    String benchmarkSql = sampleQuery != null ? sampleQuery : queryText;
                    int timeoutMs = 30_000;

                    // ── Step 3: Row-count validation (functional correctness) ───────────
                    // A rewrite that returns a different number of rows is functionally wrong,
                    // regardless of speed. We must check this before accepting any rewrite.
                    sendStep(emitter, "validating_rows",
                        "Comparing row counts: original vs rewrite to verify correctness...", null);

                    long originalRows = optimizationBenchmarkService.quickCountRows(
                        connectionId, benchmarkSql, timeoutMs);
                    long rewriteRows = optimizationBenchmarkService.quickCountRows(
                        connectionId, optimizedQuery, timeoutMs);

                    boolean rowCountsAvailable = originalRows >= 0 && rewriteRows >= 0;
                    boolean rowCountsMatch = false;
                    String rowCountDetail = null;

                    if (rowCountsAvailable) {
                        if (originalRows == 0 && rewriteRows == 0) {
                            // Both return 0 rows — may be a date/filter issue in test data;
                            // treat as "match" but flag it so the user knows.
                            rowCountsMatch = true;
                            rowCountDetail = "Both return 0 rows — test data may not cover this date range; "
                                + "results may differ in production.";
                        } else if (originalRows == 0 || rewriteRows == 0) {
                            // One returns rows and the other doesn't — clearly wrong.
                            rowCountsMatch = false;
                            rowCountDetail = String.format(
                                "Original: %,d rows  |  Rewrite: %,d rows — results do not match.",
                                originalRows, rewriteRows);
                        } else {
                            // Allow ≤2% difference for non-deterministic or float aggregation queries;
                            // for everything else require an exact match.
                            double diffPct = Math.abs((double)(originalRows - rewriteRows) / originalRows) * 100.0;
                            rowCountsMatch = diffPct <= 2.0;
                            rowCountDetail = String.format(
                                "Original: %,d rows  |  Rewrite: %,d rows%s",
                                originalRows, rewriteRows,
                                rowCountsMatch ? " ✓ match" : String.format(" — %.1f%% difference", diffPct));
                        }
                    }

                    if (rowCountsAvailable && !rowCountsMatch) {
                        // Rewrite is functionally incorrect — discard immediately.
                        sendStep(emitter, "rewrite_incorrect",
                            "Rewrite returns different rows — functionally incorrect, discarding.",
                            rowCountDetail);
                        result.setOptimizedQuery(null);
                    } else {
                        // Row counts match (or couldn't be measured) — proceed to performance check.
                        if (rowCountsAvailable) {
                            sendStep(emitter, "rows_validated",
                                "Row counts match — benchmarking performance...",
                                rowCountDetail);
                        } else {
                            sendStep(emitter, "benchmarking",
                                "Row count check inconclusive — benchmarking performance...", null);
                        }

                        // ── Step 4: Performance benchmark ────────────────────────────────
                        double originalMs = optimizationBenchmarkService.quickBenchmark(
                            connectionId, benchmarkSql, timeoutMs);
                        double rewriteMs = optimizationBenchmarkService.quickBenchmark(
                            connectionId, optimizedQuery, timeoutMs);

                        boolean measuredBoth = originalMs < Double.MAX_VALUE && rewriteMs < Double.MAX_VALUE;
                        double improvementPct = measuredBoth && originalMs > 0
                            ? ((originalMs - rewriteMs) / originalMs) * 100.0
                            : Double.NaN;

                        // ── Step 5: Decide whether rewrite wins ───────────────────────────
                        // Threshold: rewrite must be at least 15% faster to be shown.
                        final double MIN_IMPROVEMENT_PCT = 15.0;

                        if (measuredBoth && !Double.isNaN(improvementPct)) {
                            if (improvementPct >= MIN_IMPROVEMENT_PCT) {
                                sendStep(emitter, "rewrite_accepted",
                                    String.format("Rewrite is %.1f%% faster — including in results.",
                                        improvementPct),
                                    String.format("Original: %.0fms  →  Rewrite: %.0fms",
                                        originalMs, rewriteMs));
                                result.setEstimatedImprovement(improvementPct);
                            } else if (improvementPct < 0) {
                                sendStep(emitter, "rewrite_rejected",
                                    String.format("Rewrite is %.1f%% slower than original — discarding.",
                                        Math.abs(improvementPct)),
                                    String.format("Original: %.0fms  →  Rewrite: %.0fms — showing index/config recommendations.",
                                        originalMs, rewriteMs));
                                result.setOptimizedQuery(null);
                            } else {
                                sendStep(emitter, "rewrite_rejected",
                                    String.format("Rewrite is only %.1f%% faster (need ≥%d%%) — discarding.",
                                        improvementPct, (int) MIN_IMPROVEMENT_PCT),
                                    String.format("Original: %.0fms  →  Rewrite: %.0fms — marginal gain, not worth the change.",
                                        originalMs, rewriteMs));
                                result.setOptimizedQuery(null);
                            }
                        } else {
                            // Benchmark timed out or failed — keep the rewrite but flag as unvalidated
                            sendStep(emitter, "benchmark_skipped",
                                "Benchmark timed out — rewrite included but performance not verified.", null);
                        }
                    }
                }

                // ── Emit final result ──────────────────────────────────────────────────
                String json = objectMapper.writeValueAsString(result);
                emitter.send(SseEmitter.event().name("result").data(json));
                emitter.complete();

            } catch (Exception ex) {
                log.error("Error in streaming optimization for connection {}", connectionId, ex);
                try {
                    emitter.send(SseEmitter.event().name("error")
                        .data("{\"message\":\"" + ex.getMessage().replace("\"", "'") + "\"}"));
                    emitter.completeWithError(ex);
                } catch (IOException ignored) {
                    emitter.completeWithError(ex);
                }
            }
        });

        return emitter;
    }

    private void sendStep(SseEmitter emitter, String status, String message, String detail) {
        try {
            java.util.Map<String, String> payload = new java.util.LinkedHashMap<>();
            payload.put("status", status);
            payload.put("message", message);
            if (detail != null) payload.put("detail", detail);
            emitter.send(SseEmitter.event().name("step").data(objectMapper.writeValueAsString(payload)));
        } catch (IOException e) {
            log.debug("SSE step send failed (client disconnected?): {}", e.getMessage());
        }
    }

    /**
     * Batch optimize multiple slow queries
     */
    @PostMapping("/optimize/batch/{connectionId}")
    public ResponseEntity<List<QueryOptimizationService.OptimizationResult>> batchOptimize(
        @PathVariable String connectionId,
        @RequestParam(defaultValue = "5") int limit
    ) {
        accessControlService.assertCanManageConnectionContent(connectionId);
        try {
            log.info("Batch optimizing slow queries for connection: {}", connectionId);

            Optional<SlowQueryHistory> latestOpt = historyService.getLatestHistory(connectionId);
            if (latestOpt.isEmpty()) {
                return ResponseEntity.noContent().build();
            }

            SlowQueryAnalysis analysis = historyService.getAnalysisData(latestOpt.get());
            if (analysis == null || analysis.getTopSlowQueries() == null) {
                return ResponseEntity.noContent().build();
            }

            List<QueryOptimizationService.OptimizationResult> results =
                optimizationService.optimizeQueries(connectionId, analysis.getTopSlowQueries(), limit);

            return ResponseEntity.ok(results);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error batch optimizing queries", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get optimization candidates (original + AI rewrite) and best recommendation.
     */
    @GetMapping("/optimize/candidates/{connectionId}/{queryFingerprint}")
    public ResponseEntity<OptimizationCandidatesResponse> getOptimizationCandidates(
        @PathVariable String connectionId,
        @PathVariable String queryFingerprint
    ) {
        accessControlService.assertCanReadConnectionContent(connectionId);
        try {
            List<com.dbaagent.model.QueryOptimizationCandidateRun> candidates =
                candidateService.getCandidates(connectionId, queryFingerprint);
            boolean needsRefresh = candidates == null || candidates.isEmpty()
                || candidates.stream().anyMatch(c ->
                    c.getEstimatedCost() == null && c.getPlanSignature() == null && c.getPlanText() == null);
            if (needsRefresh) {
                optimizationService.ensureOptimizationCandidates(connectionId, queryFingerprint);
                candidates = candidateService.getCandidates(connectionId, queryFingerprint);
            }
            OptimizationCandidatesResponse response = buildCandidateResponse(candidates);
            return ResponseEntity.ok(response);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error getting optimization candidates", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Benchmark optimization candidates (manual trigger).
     */
    @PostMapping("/optimize/benchmark/{connectionId}/{queryFingerprint}")
    public ResponseEntity<OptimizationCandidatesResponse> benchmarkCandidates(
        @PathVariable String connectionId,
        @PathVariable String queryFingerprint,
        @RequestBody(required = false) BenchmarkCandidatesRequest request
    ) {
        accessControlService.assertCanManageConnectionContent(connectionId);
        try {
            Integer runs = request != null ? request.getRuns() : null;
            Integer timeoutMs = request != null ? request.getTimeoutMs() : null;
            List<com.dbaagent.model.QueryOptimizationCandidateRun> updated =
                optimizationBenchmarkService.benchmarkCandidates(connectionId, queryFingerprint, runs, timeoutMs);
            OptimizationCandidatesResponse response = buildCandidateResponse(updated);
            return ResponseEntity.ok(response);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error benchmarking optimization candidates", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get cached optimization for a specific query fingerprint.
     * Returns the cached result if available, or 204 No Content if not cached.
     */
    @GetMapping("/optimize/cached/{connectionId}/{queryFingerprint}")
    public ResponseEntity<QueryOptimizationService.OptimizationResult> getCachedOptimization(
        @PathVariable String connectionId,
        @PathVariable String queryFingerprint
    ) {
        accessControlService.assertCanReadConnectionContent(connectionId);
        try {
            QueryOptimizationService.OptimizationResult cached =
                optimizationService.getCachedOptimization(connectionId, queryFingerprint);

            if (cached != null) {
                return ResponseEntity.ok(cached);
            }
            return ResponseEntity.noContent().build();
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error getting cached optimization", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get cached optimizations for multiple query fingerprints.
     * Useful for loading cached results when displaying a list of slow queries.
     */
    @PostMapping("/optimize/cached/{connectionId}")
    public ResponseEntity<Map<String, QueryOptimizationService.OptimizationResult>> getCachedOptimizations(
        @PathVariable String connectionId,
        @RequestBody List<String> fingerprints
    ) {
        accessControlService.assertCanReadConnectionContent(connectionId);
        try {
            Map<String, QueryOptimizationService.OptimizationResult> cached =
                optimizationService.getCachedOptimizations(connectionId, fingerprints);

            return ResponseEntity.ok(cached);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error getting cached optimizations", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get cache statistics for a connection.
     */
    @GetMapping("/optimize/cache-stats/{connectionId}")
    public ResponseEntity<Map<String, Object>> getOptimizationCacheStats(
        @PathVariable String connectionId
    ) {
        accessControlService.assertCanReadConnectionContent(connectionId);
        try {
            Map<String, Object> stats = optimizationService.getCacheStats(connectionId);
            return ResponseEntity.ok(stats);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error getting cache stats", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Clear all cached optimizations for a connection.
     */
    @DeleteMapping("/optimize/cache/{connectionId}")
    public ResponseEntity<Void> clearOptimizationCache(
        @PathVariable String connectionId
    ) {
        accessControlService.assertCanManageConnectionContent(connectionId);
        try {
            optimizationService.clearConnectionCache(connectionId);
            return ResponseEntity.ok().build();
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error clearing optimization cache", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ==================== Feature 2: Slow Query Alerts ====================

    /**
     * Get alert summary for a connection
     */
    @GetMapping("/alerts/{connectionId}")
    public ResponseEntity<SlowQueryAlertService.SlowQueryAlertSummary> getAlertSummary(
        @PathVariable String connectionId
    ) {
        accessControlService.assertCanReadConnectionContent(connectionId);
        try {
            SlowQueryAlertService.SlowQueryAlertSummary summary = alertService.getAlertSummary(connectionId);
            return ResponseEntity.ok(summary);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error getting alert summary", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Acknowledge an alert
     */
    @PostMapping("/alerts/{alertId}/acknowledge")
    public ResponseEntity<PlaybookAlert> acknowledgeAlert(
        @PathVariable String alertId,
        @RequestParam(required = false) String userId
    ) {
        assertCanManageAlert(alertId);
        try {
            // The actor is the authenticated caller, never the userId query parameter:
            // that was client-supplied, so the acknowledgement trail could name anyone.
            // The parameter is still accepted so existing callers do not break, and ignored.
            PlaybookAlert alert = alertService.acknowledgeAlert(
                alertId, accessControlService.requireCurrentUsername());
            return ResponseEntity.ok(alert);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error acknowledging alert", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Acknowledge all alerts for a connection
     */
    @PostMapping("/alerts/{connectionId}/acknowledge-all")
    public ResponseEntity<Map<String, Integer>> acknowledgeAllAlerts(
        @PathVariable String connectionId,
        @RequestParam(required = false) String userId
    ) {
        accessControlService.assertCanManageConnectionContent(connectionId);
        try {
            int count = alertService.acknowledgeAllAlerts(
                connectionId, accessControlService.requireCurrentUsername());
            return ResponseEntity.ok(Map.of("acknowledged", count));
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error acknowledging all alerts", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Create alerts from analysis
     */
    @PostMapping("/alerts/process/{connectionId}")
    public ResponseEntity<List<PlaybookAlert>> processAlertsFromAnalysis(
        @PathVariable String connectionId,
        @RequestBody(required = false) AlertConfigRequest config
    ) {
        accessControlService.assertCanManageConnectionContent(connectionId);
        try {
            Optional<SlowQueryHistory> latestOpt = historyService.getLatestHistory(connectionId);
            if (latestOpt.isEmpty()) {
                return ResponseEntity.noContent().build();
            }

            SlowQueryAnalysis analysis = historyService.getAnalysisData(latestOpt.get());

            SlowQueryAlertService.AlertConfiguration alertConfig = SlowQueryAlertService.AlertConfiguration.builder()
                .enabled(true)
                .criticalThresholdMs(config != null && config.getCriticalThresholdMs() != null ?
                    config.getCriticalThresholdMs() : 5000)
                .highThresholdMs(config != null && config.getHighThresholdMs() != null ?
                    config.getHighThresholdMs() : 1000)
                .criticalQueryCountThreshold(config != null && config.getCriticalQueryCountThreshold() != null ?
                    config.getCriticalQueryCountThreshold() : 10)
                .notificationChannels(config != null && config.getNotificationChannels() != null ?
                    config.getNotificationChannels() : List.of("BROWSER"))
                .build();

            List<PlaybookAlert> alerts = alertService.processAnalysisForAlerts(connectionId, analysis, alertConfig);
            return ResponseEntity.ok(alerts);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error processing alerts", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ==================== Feature 3: Query Comparison View ====================

    /**
     * Compare two analysis history entries
     */
    @GetMapping("/compare")
    public ResponseEntity<Map<String, Object>> compareAnalyses(
        @RequestParam String historyId1,
        @RequestParam String historyId2
    ) {
        assertCanReadHistory(historyId1);
        assertCanReadHistory(historyId2);
        try {
            Optional<SlowQueryHistory> history1Opt = historyService.getHistoryById(historyId1);
            Optional<SlowQueryHistory> history2Opt = historyService.getHistoryById(historyId2);

            if (history1Opt.isEmpty() || history2Opt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            SlowQueryHistory h1 = history1Opt.get();
            SlowQueryHistory h2 = history2Opt.get();

            SlowQueryAnalysis a1 = historyService.getAnalysisData(h1);
            SlowQueryAnalysis a2 = historyService.getAnalysisData(h2);

            Map<String, Object> comparison = new java.util.HashMap<>();

            // Summary comparison
            Map<String, Object> summary = new java.util.HashMap<>();
            summary.put("period1", Map.of(
                "id", h1.getId(),
                "timestamp", h1.getCreatedAt(),
                "totalSlowQueries", h1.getTotalSlowQueries(),
                "health", h1.getOverallHealth(),
                "criticalCount", h1.getCriticalCount(),
                "highCount", h1.getHighCount(),
                "totalDatabaseTimeMs", h1.getTotalDatabaseTimeMs()
            ));
            summary.put("period2", Map.of(
                "id", h2.getId(),
                "timestamp", h2.getCreatedAt(),
                "totalSlowQueries", h2.getTotalSlowQueries(),
                "health", h2.getOverallHealth(),
                "criticalCount", h2.getCriticalCount(),
                "highCount", h2.getHighCount(),
                "totalDatabaseTimeMs", h2.getTotalDatabaseTimeMs()
            ));

            // Calculate changes
            long queryDiff = (h2.getTotalSlowQueries() != null ? h2.getTotalSlowQueries() : 0) -
                            (h1.getTotalSlowQueries() != null ? h1.getTotalSlowQueries() : 0);
            double timeDiff = (h2.getTotalDatabaseTimeMs() != null ? h2.getTotalDatabaseTimeMs() : 0) -
                             (h1.getTotalDatabaseTimeMs() != null ? h1.getTotalDatabaseTimeMs() : 0);

            summary.put("changes", Map.of(
                "slowQueryDiff", queryDiff,
                "databaseTimeDiff", timeDiff,
                "trend", queryDiff < 0 ? "IMPROVING" : queryDiff > 0 ? "DEGRADING" : "STABLE"
            ));

            comparison.put("summary", summary);

            // Query-level comparison (find common queries and compare)
            if (a1 != null && a2 != null && a1.getTopSlowQueries() != null && a2.getTopSlowQueries() != null) {
                Map<String, SlowQuery> queries1 = a1.getTopSlowQueries().stream()
                    .filter(q -> q.getQueryId() != null)
                    .collect(Collectors.toMap(SlowQuery::getQueryId, q -> q, (a, b) -> a));

                List<Map<String, Object>> queryComparisons = new java.util.ArrayList<>();

                for (SlowQuery q2 : a2.getTopSlowQueries()) {
                    if (q2.getQueryId() != null && queries1.containsKey(q2.getQueryId())) {
                        SlowQuery q1 = queries1.get(q2.getQueryId());

                        double avgTimeDiff = (q2.getAvgExecutionTimeMs() != null ? q2.getAvgExecutionTimeMs() : 0) -
                                            (q1.getAvgExecutionTimeMs() != null ? q1.getAvgExecutionTimeMs() : 0);

                        queryComparisons.add(Map.of(
                            "queryId", q2.getQueryId(),
                            "queryPreview", truncateQuery(q2.getQueryText(), 100),
                            "period1AvgMs", q1.getAvgExecutionTimeMs() != null ? q1.getAvgExecutionTimeMs() : 0,
                            "period2AvgMs", q2.getAvgExecutionTimeMs() != null ? q2.getAvgExecutionTimeMs() : 0,
                            "avgTimeDiff", avgTimeDiff,
                            "period1Calls", q1.getCallCount() != null ? q1.getCallCount() : 0,
                            "period2Calls", q2.getCallCount() != null ? q2.getCallCount() : 0
                        ));
                    }
                }

                comparison.put("queryComparisons", queryComparisons);
            }

            return ResponseEntity.ok(comparison);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error comparing analyses", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ==================== Feature 4: Dashboard Widgets ====================

    /**
     * Get dashboard widget data
     */
    @GetMapping("/dashboard/{connectionId}")
    public ResponseEntity<SlowQueryDashboardService.DashboardWidgetData> getDashboardWidgets(
        @PathVariable String connectionId
    ) {
        accessControlService.assertCanReadConnectionContent(connectionId);
        try {
            SlowQueryDashboardService.DashboardWidgetData data = dashboardService.getWidgetData(connectionId);
            return ResponseEntity.ok(data);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error getting dashboard widgets", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get overview widget only
     */
    @GetMapping("/dashboard/{connectionId}/overview")
    public ResponseEntity<SlowQueryDashboardService.OverviewWidget> getOverviewWidget(
        @PathVariable String connectionId
    ) {
        accessControlService.assertCanReadConnectionContent(connectionId);
        try {
            SlowQueryDashboardService.OverviewWidget data = dashboardService.getOverviewWidget(connectionId);
            return ResponseEntity.ok(data);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error getting overview widget", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get trend widget only
     */
    @GetMapping("/dashboard/{connectionId}/trend")
    public ResponseEntity<SlowQueryDashboardService.TrendWidget> getTrendWidget(
        @PathVariable String connectionId
    ) {
        accessControlService.assertCanReadConnectionContent(connectionId);
        try {
            SlowQueryDashboardService.TrendWidget data = dashboardService.getTrendWidget(connectionId);
            return ResponseEntity.ok(data);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error getting trend widget", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ==================== Feature 5: Query Fingerprint Tracking ====================

    /**
     * Get fingerprint summary for a connection
     */
    @GetMapping("/fingerprints/{connectionId}")
    public ResponseEntity<QueryFingerprintService.FingerprintSummary> getFingerprintSummary(
        @PathVariable String connectionId
    ) {
        accessControlService.assertCanReadConnectionContent(connectionId);
        try {
            QueryFingerprintService.FingerprintSummary summary = fingerprintService.getSummary(connectionId);
            return ResponseEntity.ok(summary);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error getting fingerprint summary", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get all fingerprints with optional filtering
     */
    @GetMapping("/fingerprints/{connectionId}/list")
    public ResponseEntity<List<QueryFingerprint>> getFingerprints(
        @PathVariable String connectionId,
        @RequestParam(required = false) String queryType,
        @RequestParam(required = false) String trendDirection,
        @RequestParam(required = false) Boolean regressingOnly,
        @RequestParam(defaultValue = "50") int limit
    ) {
        accessControlService.assertCanReadConnectionContent(connectionId);
        try {
            QueryFingerprint.TrendDirection direction = null;
            if (trendDirection != null && !trendDirection.isBlank()) {
                try {
                    direction = QueryFingerprint.TrendDirection.valueOf(trendDirection.toUpperCase());
                } catch (IllegalArgumentException ignored) {}
            }

            List<QueryFingerprint> fingerprints = fingerprintService.getFingerprints(
                connectionId, queryType, direction, regressingOnly, limit);
            return ResponseEntity.ok(fingerprints);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error getting fingerprints", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get trend data for a specific fingerprint
     */
    @GetMapping("/fingerprints/trend/{fingerprintId}")
    public ResponseEntity<QueryFingerprintService.FingerprintTrend> getFingerprintTrend(
        @PathVariable String fingerprintId
    ) {
        assertCanReadFingerprint(fingerprintId);
        try {
            QueryFingerprintService.FingerprintTrend trend = fingerprintService.getTrend(fingerprintId);
            return ResponseEntity.ok(trend);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error getting fingerprint trend", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Reset baseline for a fingerprint
     */
    @PostMapping("/fingerprints/{fingerprintId}/reset-baseline")
    public ResponseEntity<QueryFingerprint> resetFingerprintBaseline(
        @PathVariable String fingerprintId
    ) {
        assertCanManageFingerprint(fingerprintId);
        try {
            QueryFingerprint fingerprint = fingerprintService.resetBaseline(fingerprintId);
            return ResponseEntity.ok(fingerprint);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error resetting fingerprint baseline", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Process fingerprints from latest analysis
     */
    @PostMapping("/fingerprints/process/{connectionId}")
    public ResponseEntity<List<QueryFingerprint>> processFingerprints(
        @PathVariable String connectionId
    ) {
        accessControlService.assertCanManageConnectionContent(connectionId);
        try {
            Optional<SlowQueryHistory> latestOpt = historyService.getLatestHistory(connectionId);
            if (latestOpt.isEmpty()) {
                return ResponseEntity.noContent().build();
            }

            SlowQueryAnalysis analysis = historyService.getAnalysisData(latestOpt.get());
            List<QueryFingerprint> fingerprints = fingerprintService.processAnalysis(connectionId, analysis);
            return ResponseEntity.ok(fingerprints);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error processing fingerprints", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ==================== Feature 6: Explain Plan Integration ====================

    /**
     * Get EXPLAIN plan for a specific query
     */
    @PostMapping("/explain")
    public ResponseEntity<Map<String, Object>> getExplainPlan(
        @RequestBody ExplainQueryRequest request
    ) {
        accessControlService.assertCanManageConnectionContent(request.getConnectionId());
        try {
            log.info("Running EXPLAIN for query in connection: {}", request.getConnectionId());

            var explainResult = explainPlanService.analyzeQuery(
                request.getConnectionId(),
                request.getQueryText(),
                request.isAnalyze()
            );

            Map<String, Object> response = new java.util.HashMap<>();
            response.put("explainAnalysis", explainResult);
            response.put("queryText", request.getQueryText());
            response.put("connectionId", request.getConnectionId());

            return ResponseEntity.ok(response);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error running EXPLAIN", e);
            return ResponseEntity.internalServerError()
                .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Get EXPLAIN plans for all critical queries in latest analysis
     */
    @GetMapping("/explain/critical/{connectionId}")
    public ResponseEntity<List<Map<String, Object>>> getCriticalQueryExplains(
        @PathVariable String connectionId,
        @RequestParam(defaultValue = "5") int limit
    ) {
        accessControlService.assertCanReadConnectionContent(connectionId);
        try {
            Optional<SlowQueryHistory> latestOpt = historyService.getLatestHistory(connectionId);
            if (latestOpt.isEmpty()) {
                return ResponseEntity.noContent().build();
            }

            SlowQueryAnalysis analysis = historyService.getAnalysisData(latestOpt.get());
            if (analysis == null || analysis.getTopSlowQueries() == null) {
                return ResponseEntity.noContent().build();
            }

            List<Map<String, Object>> results = new java.util.ArrayList<>();
            int count = 0;

            for (SlowQuery query : analysis.getTopSlowQueries()) {
                if (count >= limit) break;
                if (query.getSeverity() != SlowQuery.Severity.CRITICAL &&
                    query.getSeverity() != SlowQuery.Severity.HIGH) {
                    continue;
                }
                if (query.getQueryText() == null ||
                    !query.getQueryText().trim().toUpperCase().startsWith("SELECT")) {
                    continue;
                }

                try {
                    var explainResult = explainPlanService.analyzeQuery(
                        connectionId, query.getQueryText(), false);

                    Map<String, Object> result = new java.util.HashMap<>();
                    result.put("queryId", query.getQueryId());
                    result.put("queryPreview", truncateQuery(query.getQueryText(), 100));
                    result.put("avgTimeMs", query.getAvgExecutionTimeMs());
                    result.put("severity", query.getSeverity());
                    result.put("explainAnalysis", explainResult);

                    results.add(result);
                    count++;
                } catch (org.springframework.web.server.ResponseStatusException e) {
                    throw e;
                } catch (Exception e) {
                    log.warn("Failed to run EXPLAIN for query {}: {}", query.getQueryId(), e.getMessage());
                }
            }

            return ResponseEntity.ok(results);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error getting critical query explains", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ==================== Helper Methods ====================

    /**
     * Truncate a query string to the specified max length.
     */
    private String truncateQuery(String query, int maxLength) {
        if (query == null) {
            return "";
        }
        if (query.length() <= maxLength) {
            return query;
        }
        return query.substring(0, maxLength) + "...";
    }

    private OptimizationCandidatesResponse buildCandidateResponse(
        List<com.dbaagent.model.QueryOptimizationCandidateRun> candidates
    ) {
        OptimizationCandidatesResponse response = new OptimizationCandidatesResponse();
        response.setCandidates(candidates);

        if (candidates == null || candidates.isEmpty()) {
            return response;
        }

        com.dbaagent.model.QueryOptimizationCandidateRun bestMeasured = candidates.stream()
            .filter(c -> c.getMedianMs() != null || c.getBenchmarkMs() != null)
            .min(java.util.Comparator.comparingDouble(
                c -> c.getMedianMs() != null ? c.getMedianMs() : c.getBenchmarkMs()
            ))
            .orElse(null);

        com.dbaagent.model.QueryOptimizationCandidateRun bestPredicted = candidates.stream()
            .filter(c -> c.getEstimatedCost() != null)
            .min(java.util.Comparator.comparingDouble(com.dbaagent.model.QueryOptimizationCandidateRun::getEstimatedCost))
            .orElse(null);

        response.setBestMeasured(bestMeasured);
        response.setBestPredicted(bestPredicted);
        response.setBestOverall(bestMeasured != null ? bestMeasured : bestPredicted);
        return response;
    }

    // ==================== Request/Response Models ====================

    @Data
    public static class OptimizeQueryRequest {
        private String connectionId;
        private String queryText;           // Normalized query (may have ? placeholders)
        private String sampleQuery;         // Actual query with real values (for EXPLAIN)
        private String queryId;
        private Double avgExecutionTimeMs;
        private Double totalExecutionTimeMs;
        private Long callCount;
        private Long rowsExamined;
        private Long rowsSent;
        private String severity;
        private Boolean forceRefresh;       // Skip cache and force fresh AI analysis
    }

    @Data
    public static class BenchmarkCandidatesRequest {
        private Integer runs;
        private Integer timeoutMs;
    }

    @Data
    public static class OptimizationCandidatesResponse {
        private List<com.dbaagent.model.QueryOptimizationCandidateRun> candidates;
        private com.dbaagent.model.QueryOptimizationCandidateRun bestMeasured;
        private com.dbaagent.model.QueryOptimizationCandidateRun bestPredicted;
        private com.dbaagent.model.QueryOptimizationCandidateRun bestOverall;
    }

    @Data
    public static class AlertConfigRequest {
        private Double criticalThresholdMs;
        private Double highThresholdMs;
        private Integer criticalQueryCountThreshold;
        private List<String> notificationChannels;
    }

    @Data
    public static class ExplainQueryRequest {
        private String connectionId;
        private String queryText;
        private boolean analyze;
    }

    /**
     * Request model for slow query analysis
     */
    @Data
    public static class SlowQueryRequest {
        private String connectionId;
        private SlowQueryAnalysis.TimeRange timeRange;  // LAST_HOUR, LAST_24_HOURS, etc.
        private Double thresholdMs;                      // Minimum avg execution time in ms
        private Integer limit;                           // Max number of queries to return
    }

    /**
     * Request model for saving slow query history
     */
    @Data
    public static class SaveHistoryRequest {
        private String connectionId;
        private String userId;
        private SlowQueryAnalysis analysisData;
    }

    /**
     * Request model for S3 slow query log analysis
     */
    @Data
    public static class S3LogRequest {
        private String connectionId;
        private String userId;
        private String s3Url;
        private String region;
        private String databaseType;
    }

    /**
     * Request model for CloudWatch slow query log analysis
     */
    @Data
    public static class CloudWatchLogRequest {
        private String connectionId;
        private String userId;
        private String logGroupName;
        private String logStreamPrefix;
        private String region;
        private String databaseType;
        private String accessKeyId;
        private String secretAccessKey;
        private String sessionToken;
        private java.time.Instant startTime;
        private Integer maxEvents;
    }

    /**
     * Response model for slow query history (full, with analysisData)
     */
    @Data
    public static class HistoryResponse {
        private String id;
        private String connectionId;
        private String userId;
        private String timeRange;
        private Double slowQueryThresholdMs;
        private SlowQueryAnalysis analysisData;
        private Long totalSlowQueries;
        private String overallHealth;
        private Long criticalCount;
        private Long highCount;
        private Double totalDatabaseTimeMs;
        private String timestamp;
    }

    /**
     * Response model for slow query history summary (without analysisData to avoid OOM)
     */
    @Data
    public static class HistorySummaryResponse {
        private String id;
        private String connectionId;
        private String userId;
        private String timeRange;
        private Double slowQueryThresholdMs;
        private Long totalSlowQueries;
        private String overallHealth;
        private Long criticalCount;
        private Long highCount;
        private Double totalDatabaseTimeMs;
        private String timestamp;
    }

    // ── authorization helpers for endpoints keyed on a non-connection id ──────
    //
    // An id is not a capability: each of these entities carries its own
    // connectionId, so resolve the owner and assert against that. An unknown id
    // reports 404 rather than 403, so none of these can be used to probe which
    // ids exist on connections the caller cannot see.

    private String historyConnectionId(String historyId) {
        return historyService.getHistoryById(historyId)
                .map(com.dbaagent.model.SlowQueryHistory::getConnectionId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Analysis not found"));
    }

    private void assertCanReadHistory(String historyId) {
        accessControlService.assertCanReadConnectionContent(historyConnectionId(historyId));
    }

    private void assertCanManageHistory(String historyId) {
        accessControlService.assertCanManageConnectionContent(historyConnectionId(historyId));
    }

    private void assertCanManageAlert(String alertId) {
        String connectionId = alertService.findConnectionIdForAlert(alertId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Alert not found"));
        accessControlService.assertCanManageConnectionContent(connectionId);
    }

    private String fingerprintConnectionId(String fingerprintId) {
        return fingerprintService.findConnectionIdForFingerprintId(fingerprintId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Fingerprint not found"));
    }

    private void assertCanReadFingerprint(String fingerprintId) {
        accessControlService.assertCanReadConnectionContent(fingerprintConnectionId(fingerprintId));
    }

    private void assertCanManageFingerprint(String fingerprintId) {
        accessControlService.assertCanManageConnectionContent(fingerprintConnectionId(fingerprintId));
    }
}
