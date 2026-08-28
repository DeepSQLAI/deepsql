package com.dbaagent.controller;

import com.dbaagent.model.ConnectionAnalyticsConfig;
import com.dbaagent.model.SlowQueryHistory;
import com.dbaagent.repository.ConnectionAnalyticsConfigRepository;
import com.dbaagent.service.SlowQueryAnalyticsService;
import com.dbaagent.service.SlowQueryDailyAnalysisService;
import com.dbaagent.service.security.AccessControlService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * REST API for the 30-day slow-query analytics store.
 *
 * Read endpoints serve the per-query timeline, regressions, and per-customer
 * breakdown the UI / MCP / CLI consume. Write endpoints manage the
 * per-connection analytics config and trigger an on-demand analysis.
 *
 * <p><b>Authorization:</b> every endpoint here takes a caller-supplied connection id, so
 * each one asserts access itself ({@code assertCanReadConnectionContent} for reads,
 * {@code assertCanManageConnectionContent} for writes). {@code SecurityConfig} only
 * requires an authenticated principal — nothing upstream inspects a connection id. See
 * {@code ConnectionScopedAuthorizationSafetyTest}.
 */
@RestController
@RequestMapping("/slow-query-analytics")
@RequiredArgsConstructor
@Slf4j
public class SlowQueryAnalyticsController {

    private final SlowQueryAnalyticsService analyticsService;
    private final SlowQueryDailyAnalysisService dailyAnalysisService;
    private final ConnectionAnalyticsConfigRepository configRepository;
    private final AccessControlService accessControlService;

    /** Every tracked query for a connection, as of the most recent analysis run. */
    @GetMapping("/{connectionId}/queries")
    public ResponseEntity<List<SlowQueryAnalyticsService.QuerySummary>> queries(
            @PathVariable String connectionId) {
        accessControlService.assertCanReadConnectionContent(connectionId);
        return ResponseEntity.ok(analyticsService.listQueries(connectionId));
    }

    /** The retained day-by-day timeline for one query (chronological). */
    @GetMapping("/{connectionId}/timeline/{fingerprint}")
    public ResponseEntity<List<SlowQueryAnalyticsService.TimelinePoint>> timeline(
            @PathVariable String connectionId,
            @PathVariable String fingerprint) {
        accessControlService.assertCanReadConnectionContent(connectionId);
        return ResponseEntity.ok(analyticsService.timeline(connectionId, fingerprint));
    }

    /**
     * Queries that regressed. Defaults to the most recent analyzed day and a
     * 1.5× slowdown threshold.
     */
    @GetMapping("/{connectionId}/regressions")
    public ResponseEntity<List<SlowQueryAnalyticsService.QueryRegression>> regressions(
            @PathVariable String connectionId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
            @RequestParam(required = false, defaultValue = "1.5") double minFactor) {
        accessControlService.assertCanReadConnectionContent(connectionId);
        return ResponseEntity.ok(analyticsService.regressions(connectionId, day, minFactor));
    }

    /** All customers seen on this connection, ranked by total slow time. */
    @GetMapping("/{connectionId}/customers")
    public ResponseEntity<List<SlowQueryAnalyticsService.CustomerSummary>> listCustomers(
            @PathVariable String connectionId) {
        accessControlService.assertCanReadConnectionContent(connectionId);
        return ResponseEntity.ok(analyticsService.listCustomers(connectionId));
    }

    /** Every slow query attributed to one customer, ranked by their mean exec time. */
    @GetMapping("/{connectionId}/customer/{customerId}/queries")
    public ResponseEntity<List<SlowQueryAnalyticsService.CustomerQueryRow>> queriesForCustomer(
            @PathVariable String connectionId,
            @PathVariable String customerId) {
        accessControlService.assertCanReadConnectionContent(connectionId);
        return ResponseEntity.ok(analyticsService.queriesForCustomer(connectionId, customerId));
    }

    /** Literal-bearing samples for one (customer, query) pair — copyable SQL. */
    @GetMapping("/{connectionId}/customer/{customerId}/query/{fingerprint}/samples")
    public ResponseEntity<List<SlowQueryAnalyticsService.QuerySample>> samplesForCustomerQuery(
            @PathVariable String connectionId,
            @PathVariable String customerId,
            @PathVariable String fingerprint) {
        accessControlService.assertCanReadConnectionContent(connectionId);
        return ResponseEntity.ok(
            analyticsService.samplesForCustomerQuery(connectionId, customerId, fingerprint));
    }

    /** Real literal-bearing executions of one query — copyable SQL, slowest first. */
    @GetMapping("/{connectionId}/query/{fingerprint}/samples")
    public ResponseEntity<List<SlowQueryAnalyticsService.QuerySample>> samples(
            @PathVariable String connectionId,
            @PathVariable String fingerprint) {
        accessControlService.assertCanReadConnectionContent(connectionId);
        return ResponseEntity.ok(analyticsService.querySamples(connectionId, fingerprint));
    }

    /** Per-customer breakdown of one query on a given day (defaults to yesterday). */
    @GetMapping("/{connectionId}/query/{fingerprint}/customers")
    public ResponseEntity<List<SlowQueryAnalyticsService.CustomerBreakdown>> customers(
            @PathVariable String connectionId,
            @PathVariable String fingerprint,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day) {
        accessControlService.assertCanReadConnectionContent(connectionId);
        return ResponseEntity.ok(analyticsService.customerBreakdown(connectionId, fingerprint, day));
    }

    /**
     * Recommend candidate tenant/customer columns for this connection, ranked
     * by how many tables they scope. Feeds the Settings panel so the user can
     * pick a tenant column instead of guessing.
     */
    @GetMapping("/{connectionId}/tenant-column-suggestions")
    public ResponseEntity<List<SlowQueryAnalyticsService.TenantColumnSuggestion>> tenantColumnSuggestions(
            @PathVariable String connectionId) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            return ResponseEntity.ok(analyticsService.suggestTenantColumns(connectionId));
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Tenant-column suggestions failed for {}: {}", connectionId, e.getMessage());
            return ResponseEntity.ok(List.of());
        }
    }

    /**
     * Read the per-connection analytics config. When nothing is saved yet, the
     * response is an auto-detected proposal (top tenant column + matching name
     * lookup) flagged {@code autoDetected: true} — so the UI shows a sensible
     * default instead of an empty form.
     */
    @GetMapping("/{connectionId}/config")
    public ResponseEntity<ConnectionAnalyticsConfig> getConfig(@PathVariable String connectionId) {
        accessControlService.assertCanReadConnectionContent(connectionId);
        return ResponseEntity.ok(analyticsService.effectiveConfig(connectionId));
    }

    /** Create or update the per-connection analytics config. */
    @PutMapping("/{connectionId}/config")
    public ResponseEntity<ConnectionAnalyticsConfig> putConfig(
            @PathVariable String connectionId,
            @RequestBody ConnectionAnalyticsConfig body) {
        accessControlService.assertCanManageConnectionContent(connectionId);
        ConnectionAnalyticsConfig cfg = configRepository.findById(connectionId)
            .orElseGet(() -> ConnectionAnalyticsConfig.builder()
                .connectionId(connectionId)
                .dailyAnalysisEnabled(true)
                .build());
        cfg.setConnectionId(connectionId);
        cfg.setTenantColumn(body.getTenantColumn());
        cfg.setCustomerLookupTable(body.getCustomerLookupTable());
        cfg.setCustomerLookupIdCol(body.getCustomerLookupIdCol());
        cfg.setCustomerLookupNameCol(body.getCustomerLookupNameCol());
        cfg.setDailyAnalysisEnabled(body.isDailyAnalysisEnabled());
        return ResponseEntity.ok(configRepository.save(cfg));
    }

    /**
     * Run the analysis for this connection right now instead of waiting for
     * the 01:30 daily job — used by the UI's "Analyze now" action.
     *
     * <p>Runs in <b>fast mode</b>: skips per-query EXPLAIN enrichment and the
     * AI summary so the call returns in seconds. The full enrichment (which
     * adds ~5 s per query × up to 100 queries plus an LLM round trip) exceeds
     * the client's 300 s HTTP timeout and is reserved for the nightly job.
     * The "Tracked Queries" panel and per-customer rollups — what the UI
     * actually displays — are populated either way.
     */
    @PostMapping("/{connectionId}/analyze-now")
    public ResponseEntity<Map<String, Object>> analyzeNow(@PathVariable String connectionId) {
        try {
            accessControlService.assertCanManageConnectionContent(connectionId);
            SlowQueryHistory header = dailyAnalysisService.analyzeAndPersist(connectionId);
            if (header != null) {
                return ResponseEntity.ok(Map.of(
                    "success", true,
                    "analysisRunId", header.getId(),
                    "overallHealth", header.getOverallHealth() != null
                        ? header.getOverallHealth() : "UNKNOWN"));
            }
            // null = no log source OR no new events. Distinguish for the UI
            // so it can render the "Configure log source" CTA vs a plain
            // "nothing new" toast.
            boolean hasSource = analyticsService.hasLogSource(connectionId);
            Map<String, Object> body = new java.util.HashMap<>();
            body.put("success", false);
            body.put("code", hasSource ? "NO_NEW_LOG_EVENTS" : "LOG_INGESTION_NOT_CONFIGURED");
            body.put("message", hasSource
                ? "No new slow-query log events since the last run."
                : "No slow-log source configured for this connection. Configure one to start collecting slow queries.");
            return ResponseEntity.status(hasSource ? 200 : 412).body(body);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.warn("On-demand slow-query analysis failed for {}: {}", connectionId, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "code", "ANALYSIS_FAILED",
                "message", e.getMessage() != null ? e.getMessage() : "analysis failed"));
        }
    }

    /**
     * Wipe all slow-query analytics data for a connection and reset the
     * ingestion cursor — equivalent to starting from scratch.
     *
     * Cleared: slow_query_run, slow_query_sample, slow_query_customer,
     *          slow_query_customer_day, slow_query_history, query_fingerprints.
     * Preserved: slow_log_source_config, connection_analytics_config.
     * Side-effect: sets slow_log_source_config.last_processed_at = null so the
     * next "SINCE_LAST" ingestion re-reads from the beginning.
     */
    @DeleteMapping("/{connectionId}/reset")
    public ResponseEntity<Map<String, Object>> reset(@PathVariable String connectionId) {
        try {
            accessControlService.assertCanManageConnectionContent(connectionId);
            analyticsService.resetAnalytics(connectionId);
            return ResponseEntity.ok(Map.of("success", true, "connectionId", connectionId));
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Analytics reset failed for {}: {}", connectionId, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                "success", false,
                "message", e.getMessage() != null ? e.getMessage() : "reset failed"));
        }
    }
}
