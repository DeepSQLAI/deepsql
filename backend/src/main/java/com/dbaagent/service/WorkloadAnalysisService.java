package com.dbaagent.service;

import com.dbaagent.dto.WorkloadAnalysisData;
import com.dbaagent.model.IndexRecommendationEntity;
import com.dbaagent.model.QueryFingerprint;
import com.dbaagent.model.SlowQuery;
import com.dbaagent.model.SlowQueryRun;
import com.dbaagent.model.WorkloadAnalysisReport;
import com.dbaagent.repository.QueryFingerprintRepository;
import com.dbaagent.repository.SlowQueryRunRepository;
import com.dbaagent.repository.WorkloadAnalysisReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The on-demand holistic Workload Analysis orchestrator.
 *
 * <p>Composes a single report from engines that already exist plus the net-new
 * pre-aggregation analyzer (Phase 3). It does NOT re-rank indexes or re-run
 * plan analysis — that would duplicate {@code IndexRecommendationService}.
 * Instead it:
 * <ol>
 *   <li>triggers a fresh index-advisor cycle so index recs reflect the latest workload,</li>
 *   <li>builds a candidate query set (most-affected ∪ regressed ∪ high-volume),</li>
 *   <li>reads the advisor's top-N index recs,</li>
 *   <li>calls the rewrites-only optimizer for the most-affected candidates,</li>
 *   <li>runs the pre-aggregation detector (Phase 3),</li>
 *   <li>folds in index-health diagnostics (unused / duplicate),</li>
 *   <li>persists one {@link WorkloadAnalysisReport}.</li>
 * </ol>
 *
 * <p>Runs on a caller-supplied virtual thread (see the controller) — it can
 * take minutes (index refresh + N LLM rewrites), so the report row is created
 * up front and progress is saved at each step for the UI to poll.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkloadAnalysisService {

    private final WorkloadAnalysisReportRepository reportRepository;
    private final IndexRecommendationService indexRecommendationService;
    private final QueryOptimizationService queryOptimizationService;
    private final PerformanceMonitoringService performanceMonitoringService;
    private final SlowQueryRunRepository slowQueryRunRepository;
    private final QueryFingerprintRepository fingerprintRepository;
    private final PreAggregationAnalyzer preAggregationAnalyzer;

    @Value("${workload-analysis.candidates.per-angle:20}")
    private int candidatesPerAngle;

    @Value("${workload-analysis.rewrites.max:12}")
    private int maxRewrites;

    @Value("${workload-analysis.regression.min-factor:1.3}")
    private double regressionMinFactor;

    @Value("${workload-analysis.index-recs.top:10}")
    private int indexTopN;

    /**
     * Create the report row in PENDING so the caller can hand back an id to poll
     * immediately, then the async worker flips it to RUNNING and fills it in.
     */
    public WorkloadAnalysisReport createPendingReport(String connectionId, String triggeredBy) {
        WorkloadAnalysisReport report = WorkloadAnalysisReport.builder()
            .connectionId(connectionId)
            .status(WorkloadAnalysisReport.Status.PENDING)
            .progress(0)
            .currentStep("Queued")
            .startedAt(LocalDateTime.now())
            .triggeredBy(triggeredBy)
            .build();
        return reportRepository.save(report);
    }

    /** True when a report for this connection is already PENDING or RUNNING. */
    public Optional<WorkloadAnalysisReport> findActive(String connectionId) {
        Optional<WorkloadAnalysisReport> running = reportRepository
            .findFirstByConnectionIdAndStatusOrderByStartedAtDesc(connectionId, WorkloadAnalysisReport.Status.RUNNING);
        if (running.isPresent()) return running;
        return reportRepository
            .findFirstByConnectionIdAndStatusOrderByStartedAtDesc(connectionId, WorkloadAnalysisReport.Status.PENDING);
    }

    /**
     * Execute the analysis for an already-created report row. Long-running;
     * call from a virtual thread. Never throws — failures are recorded on the
     * report as FAILED so the UI can show them.
     */
    public void runAnalysis(String reportId) {
        WorkloadAnalysisReport report = reportRepository.findById(reportId).orElse(null);
        if (report == null) {
            log.warn("Workload analysis report {} vanished before the worker started", reportId);
            return;
        }
        String connectionId = report.getConnectionId();
        try {
            mark(report, WorkloadAnalysisReport.Status.RUNNING, 5, "Refreshing index advisor");

            // 1. Fresh index-advisor cycle so the index section is current and
            //    self-consistent with the rewrites/pre-agg computed below.
            try {
                indexRecommendationService.generateRecommendations(connectionId);
            } catch (Exception e) {
                log.warn("Index advisor refresh failed during workload analysis for {}: {}",
                    connectionId, e.getMessage());
            }

            // 2. Candidate set.
            mark(report, WorkloadAnalysisReport.Status.RUNNING, 25, "Selecting candidate queries");
            LocalDate analyzedOn = slowQueryRunRepository
                .findTopByConnectionIdOrderByAnalyzedOnDesc(connectionId)
                .map(SlowQueryRun::getAnalyzedOn)
                .orElse(null);

            List<Candidate> candidates = analyzedOn == null
                ? List.of()
                : selectCandidates(connectionId, analyzedOn);

            // 3. Index recommendations — read the advisor's top-N (no re-ranking).
            mark(report, WorkloadAnalysisReport.Status.RUNNING, 45, "Composing index recommendations");
            List<WorkloadAnalysisData.IndexRec> indexRecs = composeIndexRecs(connectionId);

            // 4. Rewrites — the now-rewrites-only optimizer, capped.
            mark(report, WorkloadAnalysisReport.Status.RUNNING, 65, "Generating query rewrites");
            List<WorkloadAnalysisData.RewriteRec> rewrites = composeRewrites(connectionId, candidates);

            // 5. Pre-aggregation — detect shared GROUP BY/aggregate shapes across
            //    the candidate set and recommend materialized views / summary tables.
            mark(report, WorkloadAnalysisReport.Status.RUNNING, 80, "Detecting pre-aggregation opportunities");
            List<WorkloadAnalysisData.PreAggRec> preAgg = composePreAggregations(connectionId, candidates);

            // 6. Index health (cleanup).
            mark(report, WorkloadAnalysisReport.Status.RUNNING, 90, "Checking index health");
            List<WorkloadAnalysisData.IndexHealthItem> health = composeIndexHealth(connectionId);

            // Assemble.
            long totalSlowMs = candidates.stream()
                .mapToLong(c -> c.run.getTotalExecMsDelta() == null ? 0L
                    : Math.round(c.run.getTotalExecMsDelta()))
                .sum();
            WorkloadAnalysisData data = WorkloadAnalysisData.builder()
                .summary(WorkloadAnalysisData.WorkloadSummary.builder()
                    .candidatesAnalyzed(candidates.size())
                    .totalSlowMsAnalyzed(totalSlowMs)
                    .analyzedOn(analyzedOn == null ? null : analyzedOn.toString())
                    .headline(buildHeadline(candidates.size(), indexRecs.size(), rewrites.size(), preAgg.size()))
                    .build())
                .indexRecommendations(indexRecs)
                .rewriteRecommendations(rewrites)
                .preAggregationRecommendations(preAgg)
                .indexHealth(health)
                .build();

            report.setReportData(data);
            report.setCandidateCount(candidates.size());
            report.setIndexRecCount(indexRecs.size());
            report.setRewriteRecCount(rewrites.size());
            report.setPreAggRecCount(preAgg.size());
            report.setCompletedAt(LocalDateTime.now());
            mark(report, WorkloadAnalysisReport.Status.COMPLETED, 100, "Complete");
            log.info("Workload analysis {} complete for {}: {} candidates, {} index, {} rewrite, {} pre-agg",
                reportId, connectionId, candidates.size(), indexRecs.size(), rewrites.size(), preAgg.size());
        } catch (Exception e) {
            log.error("Workload analysis {} failed for {}: {}", reportId, connectionId, e.getMessage(), e);
            report.setStatus(WorkloadAnalysisReport.Status.FAILED);
            report.setMessage(e.getMessage() != null ? e.getMessage() : "analysis failed");
            report.setCompletedAt(LocalDateTime.now());
            reportRepository.save(report);
        }
    }

    // ── candidate selection ──────────────────────────────────────────────────

    /** A candidate query plus why it was selected. */
    private record Candidate(SlowQueryRun run, String reason) {}

    private List<Candidate> selectCandidates(String connectionId, LocalDate analyzedOn) {
        // Union the three angles, dedupe by fingerprint, preserving the first
        // (strongest) reason a query was picked. LinkedHashMap keeps order.
        Map<String, Candidate> byFingerprint = new LinkedHashMap<>();
        PageRequest cap = PageRequest.of(0, candidatesPerAngle);

        for (SlowQueryRun r : slowQueryRunRepository.findMostAffected(connectionId, analyzedOn, cap)) {
            byFingerprint.putIfAbsent(r.getFingerprint(), new Candidate(r, "MOST_AFFECTED"));
        }
        for (SlowQueryRun r : slowQueryRunRepository.findRegressions(connectionId, analyzedOn, regressionMinFactor)) {
            byFingerprint.putIfAbsent(r.getFingerprint(), new Candidate(r, "REGRESSED"));
        }
        for (SlowQueryRun r : slowQueryRunRepository.findHighVolume(connectionId, analyzedOn, cap)) {
            byFingerprint.putIfAbsent(r.getFingerprint(), new Candidate(r, "HIGH_VOLUME"));
        }
        return new ArrayList<>(byFingerprint.values());
    }

    // ── section composers ────────────────────────────────────────────────────

    private List<WorkloadAnalysisData.IndexRec> composeIndexRecs(String connectionId) {
        List<WorkloadAnalysisData.IndexRec> out = new ArrayList<>();
        for (IndexRecommendationEntity e : indexRecommendationService.getTopRecommendations(connectionId, indexTopN)) {
            long workload = e.getWorkloadScoreMs() == null ? 0L : e.getWorkloadScoreMs();
            long write = e.getWriteCostScore() == null ? 0L : e.getWriteCostScore();
            out.add(WorkloadAnalysisData.IndexRec.builder()
                .recommendationId(e.getId())
                .tableName(e.getTableName())
                .columnNames(e.getColumnNames())
                .createStatement(e.getCreateStatement())
                .kind(e.getKind() == null ? null : e.getKind().name())
                .priority(e.getPriority() == null ? null : e.getPriority().name())
                .workloadScoreMs(workload)
                .writeCostScore(write)
                .netBenefit(Math.max(0, workload - write))
                .hypopgReductionPct(e.getHypopgReductionPct())
                .contributingQueries(e.getEvidenceCount() == null ? 0 : e.getEvidenceCount())
                .rationale(e.getReason())
                .build());
        }
        return out;
    }

    private List<WorkloadAnalysisData.RewriteRec> composeRewrites(String connectionId, List<Candidate> candidates) {
        List<WorkloadAnalysisData.RewriteRec> out = new ArrayList<>();
        int budget = maxRewrites;
        for (Candidate c : candidates) {
            if (budget <= 0) break;
            QueryFingerprint fp = fingerprintRepository
                .findByConnectionIdAndFingerprint(connectionId, c.run.getFingerprint())
                .orElse(null);
            if (fp == null || fp.getNormalizedQuery() == null || fp.getNormalizedQuery().isBlank()) {
                continue;
            }
            try {
                SlowQuery ctx = new SlowQuery();
                ctx.setQueryId(c.run.getFingerprint());
                ctx.setQueryText(fp.getNormalizedQuery());
                ctx.setAvgExecutionTimeMs(c.run.getMeanExecMs());
                ctx.setMaxExecutionTimeMs(c.run.getMaxExecMs());
                ctx.setCallCount(c.run.getCallsDelta());

                QueryOptimizationService.OptimizationResult result = queryOptimizationService.optimizeQuery(
                    connectionId, fp.getNormalizedQuery(), fp.getSampleQuery(), ctx);

                // Only surface a rewrite when the optimizer actually produced one
                // that differs from the original — otherwise it's noise.
                String rewritten = result == null ? null : result.getOptimizedQuery();
                if (rewritten == null || rewritten.isBlank()
                    || rewritten.trim().equalsIgnoreCase(fp.getNormalizedQuery().trim())) {
                    continue;
                }
                // Show the literal-bearing sample as the "original" so it lines
                // up with the rewrite (which the optimizer produces with real
                // values). Fall back to the normalized pattern if no sample.
                String original = fp.getSampleQuery() != null && !fp.getSampleQuery().isBlank()
                    ? fp.getSampleQuery() : fp.getNormalizedQuery();
                out.add(WorkloadAnalysisData.RewriteRec.builder()
                    .fingerprint(c.run.getFingerprint())
                    .originalQuery(original)
                    .rewrittenQuery(rewritten)
                    .estimatedImprovementPct(result.getEstimatedImprovement())
                    .diagnosis(result.getExplanation())
                    .candidateReason(c.reason)
                    .build());
                budget--;
            } catch (Exception e) {
                log.warn("Rewrite failed for fingerprint {} on {}: {}",
                    c.run.getFingerprint(), connectionId, e.getMessage());
            }
        }
        return out;
    }

    private List<WorkloadAnalysisData.PreAggRec> composePreAggregations(
            String connectionId, List<Candidate> candidates) {
        if (candidates.isEmpty()) return List.of();
        // Resolve each candidate's normalized SQL + workload weight for the analyzer.
        // Pre-agg detection benefits from the WHOLE candidate set (shapes shared
        // across queries), not just the rewrite-capped subset.
        List<PreAggregationAnalyzer.QueryInput> inputs = new ArrayList<>();
        for (Candidate c : candidates) {
            QueryFingerprint fp = fingerprintRepository
                .findByConnectionIdAndFingerprint(connectionId, c.run.getFingerprint())
                .orElse(null);
            if (fp == null) continue;
            // Prefer the literal-bearing sample (real names/case); fall back to normalized.
            String sql = fp.getSampleQuery() != null && !fp.getSampleQuery().isBlank()
                ? fp.getSampleQuery() : fp.getNormalizedQuery();
            if (sql == null || sql.isBlank()) continue;
            long score = c.run.getTotalExecMsDelta() == null ? 0L : Math.round(c.run.getTotalExecMsDelta());
            inputs.add(new PreAggregationAnalyzer.QueryInput(c.run.getFingerprint(), sql, score));
        }
        try {
            return preAggregationAnalyzer.analyze(connectionId, inputs);
        } catch (Exception e) {
            log.warn("Pre-aggregation analysis failed for {}: {}", connectionId, e.getMessage());
            return List.of();
        }
    }

    /**
     * Folds the unused/duplicate index probes into the report. The probe rows
     * use camelCase keys (see PerformanceMonitoringService): unused →
     * {@code tableName,indexName,indexScans,indexSize,indexSizeBytes,rowsSelected};
     * duplicate (PG) → {@code tableName,index1Name,index2Name,columns};
     * duplicate (MySQL) → {@code tableName,redundantIndexName,dominantIndexName,
     * redundantIndexColumns,dropStatement}. Builds a human-readable detail and a
     * concrete DROP action.
     */
    private List<WorkloadAnalysisData.IndexHealthItem> composeIndexHealth(String connectionId) {
        List<WorkloadAnalysisData.IndexHealthItem> out = new ArrayList<>();
        try {
            for (Map<String, Object> u : performanceMonitoringService.getUnusedIndexes(connectionId)) {
                String table = str(u.get("tableName"), u.get("table_name"));
                String index = str(u.get("indexName"), u.get("index_name"));
                String size = str(u.get("indexSize"));               // PG pretty size
                if (size == null) size = formatBytes(u.get("indexSizeBytes"));
                String scans = str(u.get("indexScans"), u.get("rowsSelected"));
                List<String> bits = new ArrayList<>();
                if (size != null) bits.add(size);
                bits.add((scans == null ? "0" : scans) + " scans");
                out.add(WorkloadAnalysisData.IndexHealthItem.builder()
                    .issue("UNUSED")
                    .tableName(table)
                    .indexName(index)
                    .detail(String.join(" · ", bits))
                    .suggestedAction(index == null ? "Consider dropping — zero/near-zero scans"
                        : "DROP INDEX " + index + ";")
                    .build());
            }
            for (Map<String, Object> d : performanceMonitoringService.getDuplicateIndexes(connectionId)) {
                String table = str(d.get("tableName"), d.get("table_name"));
                // The "redundant" index is the one we suggest dropping.
                String redundant = str(d.get("index1Name"), d.get("redundantIndexName"));
                String dominant = str(d.get("index2Name"), d.get("dominantIndexName"));
                String columns = str(d.get("columns"), d.get("redundantIndexColumns"));
                String drop = str(d.get("dropStatement"));
                StringBuilder detail = new StringBuilder();
                if (dominant != null) detail.append("duplicate of ").append(dominant);
                if (columns != null) detail.append(detail.length() > 0 ? " · " : "").append("(").append(columns).append(")");
                out.add(WorkloadAnalysisData.IndexHealthItem.builder()
                    .issue("DUPLICATE")
                    .tableName(table)
                    .indexName(redundant)
                    .detail(detail.length() == 0 ? "redundant index" : detail.toString())
                    .suggestedAction(drop != null ? drop
                        : (redundant == null ? "Redundant — consider dropping the narrower index"
                            : "DROP INDEX " + redundant + ";"))
                    .build());
            }
        } catch (Exception e) {
            log.warn("Index-health section failed for {}: {}", connectionId, e.getMessage());
        }
        return out;
    }

    /** Pretty-print a byte count from a probe row (Long/Number/String) → "12 MB". */
    private static String formatBytes(Object raw) {
        if (raw == null) return null;
        long bytes;
        try {
            bytes = (raw instanceof Number n) ? n.longValue() : Long.parseLong(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return null;
        }
        if (bytes <= 0) return null;
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        double v = bytes;
        int i = 0;
        while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
        return (v >= 10 || i == 0 ? String.valueOf(Math.round(v)) : String.format("%.1f", v)) + " " + units[i];
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void mark(WorkloadAnalysisReport report, WorkloadAnalysisReport.Status status,
                      int progress, String step) {
        report.setStatus(status);
        report.setProgress(progress);
        report.setCurrentStep(step);
        reportRepository.save(report);
    }

    private static String buildHeadline(int candidates, int indexes, int rewrites, int preAgg) {
        if (candidates == 0) {
            return "No slow-query data yet — configure a log source and run the daily analysis first.";
        }
        return String.format(
            "Analyzed %d high-impact quer%s: %d index recommendation%s, %d rewrite%s, %d pre-aggregation%s.",
            candidates, candidates == 1 ? "y" : "ies",
            indexes, indexes == 1 ? "" : "s",
            rewrites, rewrites == 1 ? "" : "s",
            preAgg, preAgg == 1 ? "" : "s");
    }

    /** First non-blank string among the candidates (handles PG vs MySQL column naming). */
    private static String str(Object... candidates) {
        for (Object c : candidates) {
            if (c != null) {
                String s = String.valueOf(c);
                if (!s.isBlank()) return s;
            }
        }
        return null;
    }
}
