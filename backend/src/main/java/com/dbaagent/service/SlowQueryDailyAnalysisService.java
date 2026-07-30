package com.dbaagent.service;

import com.dbaagent.dto.SlowLogIngestRequest;
import com.dbaagent.model.ConnectionAnalyticsConfig;
import com.dbaagent.model.SlowLogSourceConfig;
import com.dbaagent.model.SlowQueryHistory;
import com.dbaagent.model.DatabaseConnection;
import com.dbaagent.provider.DatabaseProviderRegistry;
import com.dbaagent.repository.ConnectionAnalyticsConfigRepository;
import com.dbaagent.repository.CredentialRepository;
import com.dbaagent.repository.SlowLogSourceConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Drives the once-a-day slow-query analysis for every connection that opted in.
 *
 * <p><b>Unified pipeline (pre-launch refactor).</b> Until 2026-05, slow-query
 * data flowed through two parallel paths: a 60-second log-ingestion poll
 * (logs → fingerprints + samples) and a nightly pg_stat_statements read
 * (live stats → fingerprints + slow_query_run). The two paths produced
 * different fingerprints for the same query and wrote disjoint subsets of
 * the vault tables, so {@code slow_query_run} entries had no matching
 * {@code slow_query_sample} bind values — breaking per-customer
 * attribution wherever logs hadn't caught up.
 *
 * <p>Now everything goes through {@link SlowLogIngestionService}. This
 * service is just the daily wrapper: for each connection that has a
 * configured log source, pull anything new since the last ingestion and let
 * {@code SlowLogIngestionService.postProcessAnalysis()} do the rest
 * (fingerprints, regression, alerts, slow_query_run decomposition,
 * customer attribution, retention purge).
 *
 * <p><b>What used to live here:</b> {@code persistRun()} (now in
 * {@code SlowLogIngestionService}), {@code pg_stat_statements} reads (deleted —
 * see {@code SlowQueryService} for the legacy direct-stats path, which the
 * MCP/CLI {@code analyze_slow_queries} surfaces also no longer call).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SlowQueryDailyAnalysisService {

    private final CredentialRepository credentialRepository;
    private final ConnectionAnalyticsConfigRepository configRepository;
    private final SlowLogSourceConfigRepository logSourceRepository;
    private final SlowLogIngestionService slowLogIngestionService;
    private final ConnectionService connectionService;
    private final DatabaseProviderRegistry providerRegistry;

    /** Warn after this many consecutive runs with no ingested events. */
    private static final int DRY_RUN_WARN_THRESHOLD = 3;

    /**
     * Scheduler entry point — analyze every connection that hasn't opted out
     * and has a slow-log source configured. Per-connection failures are
     * logged and skipped so one bad connection never aborts the cycle.
     */
    public void runDailyAnalysis() {
        List<String> connectionIds = credentialRepository.findAllIds();
        log.info("Daily slow-query analysis starting for {} connection(s)", connectionIds.size());
        int ok = 0, skippedOptOut = 0, skippedNoSource = 0, empty = 0, failed = 0;
        for (String connectionId : connectionIds) {
            ConnectionAnalyticsConfig cfg = configRepository.findById(connectionId).orElse(null);
            if (cfg != null && !cfg.isDailyAnalysisEnabled()) {
                skippedOptOut++;
                continue;
            }
            if (logSourceRepository.findByConnectionId(connectionId).isEmpty()) {
                skippedNoSource++;
                continue;
            }
            try {
                SlowQueryHistory header = analyzeAndPersist(connectionId);
                if (header != null) ok++; else empty++;
            } catch (Exception e) {
                failed++;
                log.warn("Daily slow-query analysis failed for connection {}: {}",
                    connectionId, e.getMessage());
            }
        }
        log.info("Daily slow-query analysis complete: {} ok, {} empty, {} opted-out, {} no-log-source, {} failed",
            ok, empty, skippedOptOut, skippedNoSource, failed);
    }

    /**
     * Analyze one connection now: pull anything new from the connection's
     * configured log source since the last ingestion, parse it, and let
     * {@link SlowLogIngestionService#postProcessAnalysis} populate the vault.
     *
     * <p>Returns {@code null} if the connection has no log source, if no new
     * log events have arrived, or if the parser found no slow queries.
     */
    public SlowQueryHistory analyzeAndPersist(String connectionId) {
        Optional<SlowLogSourceConfig> sourceOpt = logSourceRepository.findByConnectionId(connectionId);
        if (sourceOpt.isEmpty()) {
            log.info("Skipping slow-query analysis for {}: no log source configured", connectionId);
            return null;
        }

        SlowLogIngestRequest request = new SlowLogIngestRequest();
        request.setConnectionId(connectionId);
        request.setTimeRange("SINCE_LAST");
        // The DTO uses per-provider include flags rather than a single
        // providerType field — flip the one matching the configured source.
        String provider = sourceOpt.get().getProviderType();
        if (provider != null) {
            switch (provider.toUpperCase()) {
                case "S3" -> request.setIncludeS3(true);
                case "CLOUDWATCH" -> request.setIncludeCloudWatch(true);
                case "AZURE_BLOB" -> request.setIncludeAzureBlob(true);
                case "GCP_LOGGING" -> request.setIncludeGcpLogging(true);
                case "DATADOG" -> request.setIncludeDatadog(true);
                case "ELASTICSEARCH" -> request.setIncludeElasticsearch(true);
                default -> {
                    log.warn("Unknown slow-log provider {} for {}", provider, connectionId);
                    return null;
                }
            }
        }

        SlowLogIngestionService.IngestResult result =
            slowLogIngestionService.ingestNowWithDetails(request);

        if (!result.success()) {
            // ingestNowWithDetails distinguishes "nothing to do" (no new logs)
            // from real failures via the message; the failure log line covers
            // the latter, the info line the former.
            log.info("Slow-query ingestion for {} returned no data: {}", connectionId, result.message());
            evaluateDrySource(connectionId, result.message());
            return null;
        }
        // Real data ingested — the source is healthy; clear staleness tracking.
        resetDrySource(connectionId);
        return result.history();
    }

    /**
     * A run ingested no events. Decide whether that's genuinely quiet or a
     * misconfigured source: if the target DB's slow-query counter is climbing
     * while the source stays dry, the slow-log export is broken and we surface a
     * clear warning (on the source config + a WARN log) instead of the silent
     * "this is normal" message. Best-effort; never throws.
     *
     * @return true if a staleness warning was raised
     */
    boolean evaluateDrySource(String connectionId, String ingestMessage) {
        try {
            SlowLogSourceConfig cfg = logSourceRepository.findByConnectionId(connectionId).orElse(null);
            if (cfg == null) return false;

            int dry = (cfg.getConsecutiveDryRuns() == null ? 0 : cfg.getConsecutiveDryRuns()) + 1;
            cfg.setConsecutiveDryRuns(dry);

            Long dbSlow = readDbSlowQueryCount(connectionId);
            Long prev = cfg.getLastDbSlowQueryCount();
            long newSlow = (dbSlow != null && prev != null && dbSlow > prev) ? (dbSlow - prev) : -1;
            if (dbSlow != null) cfg.setLastDbSlowQueryCount(dbSlow);

            String warning = null;
            if (newSlow > 0) {
                warning = String.format(
                    "⚠ Slow-log source is dry, but the database logged %,d new slow queries since the last check "
                    + "(%d run%s with no events). The slow-log export (CloudWatch/S3/etc.) is likely disabled or "
                    + "misconfigured — slow queries are happening but none are reaching DeepSQL.",
                    newSlow, dry, dry == 1 ? "" : "s");
            } else if (dry >= DRY_RUN_WARN_THRESHOLD) {
                warning = String.format(
                    "⚠ Slow-log source has returned no events for %d consecutive runs. Verify the log export "
                    + "is still enabled on the source.",
                    dry);
            }

            if (warning != null) {
                cfg.setLastAutoIngestMessage(warning);
                log.warn("Stale slow-log source for connection {}: {}", connectionId, warning);
            } else {
                cfg.setLastAutoIngestMessage(ingestMessage);
            }
            logSourceRepository.save(cfg);
            return warning != null;
        } catch (Exception e) {
            log.debug("Dry-source evaluation failed for {}: {}", connectionId, e.getMessage());
            return false;
        }
    }

    /** A healthy run ingested data — clear the dry-run streak and refresh the DB baseline. */
    // Package-private (not private) so the self-heal behavior can be unit-tested.
    void resetDrySource(String connectionId) {
        try {
            SlowLogSourceConfig cfg = logSourceRepository.findByConnectionId(connectionId).orElse(null);
            if (cfg == null) return;
            boolean changed = false;
            // Self-heal a stale enabled=false flag. The live ingestion path no
            // longer gates on `enabled` (the per-config poll task was removed in
            // V102), so a config left disabled by that retired machinery would
            // otherwise display as "disabled" forever even while it is actively
            // ingesting — and "never recover on its own". A successful run is
            // proof the source is healthy, so flip it back on.
            if (!cfg.isEnabled()) {
                cfg.setEnabled(true);
                changed = true;
            }
            if (cfg.getConsecutiveDryRuns() != null && cfg.getConsecutiveDryRuns() != 0) {
                cfg.setConsecutiveDryRuns(0);
                changed = true;
            }
            Long dbSlow = readDbSlowQueryCount(connectionId);
            if (dbSlow != null && !dbSlow.equals(cfg.getLastDbSlowQueryCount())) {
                cfg.setLastDbSlowQueryCount(dbSlow);
                changed = true;
            }
            if (changed) logSourceRepository.save(cfg);
        } catch (Exception e) {
            log.debug("Dry-source reset failed for {}: {}", connectionId, e.getMessage());
        }
    }

    /**
     * Cumulative slow-query count on the target DB (MySQL {@code Slow_queries}
     * global status). MySQL-only — returns null for other engines or on error.
     */
    private Long readDbSlowQueryCount(String connectionId) {
        try {
            DatabaseConnection conn = credentialRepository.findById(connectionId).orElse(null);
            if (conn == null) return null;
            String dbType = providerRegistry.getCanonicalName(conn.getDbType());
            if (!"mysql".equals(dbType)) return null; // SHOW GLOBAL STATUS is MySQL-specific
            JdbcTemplate jdbc = connectionService.getJdbcTemplateForBackgroundJob(connectionId);
            return jdbc.query("SHOW GLOBAL STATUS LIKE 'Slow_queries'",
                rs -> rs.next() ? rs.getLong(2) : null);
        } catch (Exception e) {
            log.debug("Could not read Slow_queries for {}: {}", connectionId, e.getMessage());
            return null;
        }
    }
}
