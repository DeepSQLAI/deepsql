package com.dbaagent.service;

import com.dbaagent.dto.KeyCustomerInfo;
import com.dbaagent.dto.KeyCustomerResult;
import com.dbaagent.dto.SlowQueryInsightsResponse;
import com.dbaagent.model.PerformanceIssue;
import com.dbaagent.model.QueryFingerprint;
import com.dbaagent.model.QueryPerformanceHistory;
import com.dbaagent.model.QueryPlanComparison;
import com.dbaagent.model.SlowQuery;
import com.dbaagent.model.SlowQueryAnalysis;
import com.dbaagent.model.SlowQueryHistory;
import com.dbaagent.repository.QueryFingerprintRepository;
import com.dbaagent.repository.QueryPerformanceHistoryRepository;
import com.dbaagent.repository.QueryPlanComparisonRepository;
import com.dbaagent.repository.SlowQueryHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Multi-phase slow-query workload insights service.
 * Focuses on remediation prioritization, hotspots, skew, tail risk and plan drift.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SlowQueryInsightsService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private static final int MAX_HISTORIES = 40;
    private static final Pattern WINDOW_PATTERN = Pattern.compile("^(\\d+)([hd])$", Pattern.CASE_INSENSITIVE);

    private final SlowQueryHistoryRepository historyRepository;
    private final SlowQueryHistoryService historyService;
    private final QueryFingerprintRepository fingerprintRepository;
    private final QueryPlanComparisonRepository planComparisonRepository;
    private final QueryPerformanceHistoryRepository performanceHistoryRepository;
    private final KeyCustomerService keyCustomerService;

    public SlowQueryInsightsResponse getInsights(String connectionId, String window, int limit) {
        WindowConfig windowConfig = parseWindow(window);
        int effectiveLimit = normalizeLimit(limit);

        ObservationExtraction extraction = extractObservations(connectionId, windowConfig);
        List<QueryObservation> observations = extraction.observations;

        SlowQueryInsightsResponse.RemediationInsights remediation =
            buildRemediationInsights(observations, effectiveLimit);
        SlowQueryInsightsResponse.HotspotInsights hotspots =
            buildHotspotInsights(connectionId, windowConfig, observations, effectiveLimit);
        SlowQueryInsightsResponse.SkewInsights skew =
            buildSkewInsights(connectionId, extraction, effectiveLimit);
        SlowQueryInsightsResponse.TailRiskInsights tailRisk =
            buildTailRiskInsights(observations, effectiveLimit);
        SlowQueryInsightsResponse.PlanDriftInsights planDrift =
            buildPlanDriftInsights(connectionId, windowConfig, observations, effectiveLimit);

        return SlowQueryInsightsResponse.builder()
            .metadata(buildMetadata(windowConfig, extraction))
            .remediation(remediation)
            .hotspots(hotspots)
            .skew(skew)
            .tailRisk(tailRisk)
            .planDrift(planDrift)
            .build();
    }

    public SlowQueryInsightsResponse.RemediationInsights getRemediationInsights(
        String connectionId,
        String window,
        int limit
    ) {
        WindowConfig windowConfig = parseWindow(window);
        ObservationExtraction extraction = extractObservations(connectionId, windowConfig);
        return buildRemediationInsights(extraction.observations, normalizeLimit(limit));
    }

    public SlowQueryInsightsResponse.HotspotInsights getHotspotInsights(
        String connectionId,
        String window,
        int limit
    ) {
        WindowConfig windowConfig = parseWindow(window);
        ObservationExtraction extraction = extractObservations(connectionId, windowConfig);
        return buildHotspotInsights(connectionId, windowConfig, extraction.observations, normalizeLimit(limit));
    }

    public SlowQueryInsightsResponse.SkewInsights getSkewInsights(
        String connectionId,
        String window,
        int limit
    ) {
        WindowConfig windowConfig = parseWindow(window);
        ObservationExtraction extraction = extractObservations(connectionId, windowConfig);
        return buildSkewInsights(connectionId, extraction, normalizeLimit(limit));
    }

    public SlowQueryInsightsResponse.TailRiskInsights getTailRiskInsights(
        String connectionId,
        String window,
        int limit
    ) {
        WindowConfig windowConfig = parseWindow(window);
        ObservationExtraction extraction = extractObservations(connectionId, windowConfig);
        return buildTailRiskInsights(extraction.observations, normalizeLimit(limit));
    }

    public SlowQueryInsightsResponse.PlanDriftInsights getPlanDriftInsights(
        String connectionId,
        String window,
        int limit
    ) {
        WindowConfig windowConfig = parseWindow(window);
        ObservationExtraction extraction = extractObservations(connectionId, windowConfig);
        return buildPlanDriftInsights(connectionId, windowConfig, extraction.observations, normalizeLimit(limit));
    }

    private SlowQueryInsightsResponse.InsightMetadata buildMetadata(
        WindowConfig windowConfig,
        ObservationExtraction extraction
    ) {
        double coverage = 0;
        if (extraction.totalSlowQueriesReported > 0) {
            coverage = 100.0 * extraction.totalQueriesCaptured / extraction.totalSlowQueriesReported;
        } else if (extraction.totalQueriesCaptured > 0) {
            coverage = 100.0;
        }
        coverage = round2(coverage);

        String confidence;
        if (coverage >= 70 && extraction.historiesAnalyzed >= 5) {
            confidence = "HIGH";
        } else if (coverage >= 40 && extraction.historiesAnalyzed >= 2) {
            confidence = "MEDIUM";
        } else {
            confidence = "LOW";
        }

        return SlowQueryInsightsResponse.InsightMetadata.builder()
            .generatedAt(Instant.now())
            .window(windowConfig.normalizedWindow)
            .historiesAnalyzed(extraction.historiesAnalyzed)
            .queryObservationsAnalyzed(extraction.observations.size())
            .cappedAnalyses(extraction.cappedAnalyses)
            .dataCoveragePct(coverage)
            .confidence(confidence)
            .build();
    }

    private ObservationExtraction extractObservations(String connectionId, WindowConfig windowConfig) {
        List<SlowQueryHistory> histories = historyRepository.findByConnectionIdSince(
            connectionId,
            windowConfig.since,
            PageRequest.of(0, MAX_HISTORIES)
        );

        List<QueryObservation> observations = new ArrayList<>();
        int cappedAnalyses = 0;
        long totalReported = 0;
        long totalCaptured = 0;

        for (SlowQueryHistory history : histories) {
            SlowQueryAnalysis analysis;
            try {
                analysis = historyService.getAnalysisData(history);
            } catch (Exception e) {
                log.warn("Skipping malformed slow query history {}: {}", history.getId(), e.getMessage());
                continue;
            }

            if (analysis == null) {
                continue;
            }

            List<SlowQuery> top = analysis.getTopSlowQueries() != null
                ? analysis.getTopSlowQueries()
                : Collections.emptyList();

            long reported = analysis.getTotalSlowQueries() != null
                ? analysis.getTotalSlowQueries()
                : top.size();
            totalReported += Math.max(reported, 0);
            totalCaptured += top.size();
            if (reported > top.size()) {
                cappedAnalyses++;
            }

            for (SlowQuery query : top) {
                String queryId = resolveQueryId(query);
                if (queryId == null) {
                    continue;
                }

                observations.add(QueryObservation.builder()
                    .timestamp(history.getCreatedAt())
                    .queryId(queryId)
                    .queryPreview(buildQueryPreview(query))
                    .queryType(detectQueryType(query))
                    .affectedTables(query.getAffectedTables() != null ? query.getAffectedTables() : List.of())
                    .avgExecutionTimeMs(defaultDouble(query.getAvgExecutionTimeMs()))
                    .callCount(defaultLong(query.getCallCount(), 1))
                    .totalDbTimeMs(resolveTotalDbTimeMs(query))
                    .rowsExamined(defaultLong(query.getRowsExamined(), 0))
                    .rowsSent(defaultLong(query.getRowsSent(), 0))
                    .severity(query.getSeverity() != null ? query.getSeverity().name() : "LOW")
                    .suggestedIndexesCount(query.getSuggestedIndexes() != null ? query.getSuggestedIndexes().size() : 0)
                    .hasLockSignal(hasLockSignal(query))
                    .planSignature(query.getExplainAnalysis() != null ? query.getExplainAnalysis().getPlanSignature() : null)
                    .build());
            }
        }

        return new ObservationExtraction(
            observations,
            histories.size(),
            cappedAnalyses,
            totalReported,
            totalCaptured
        );
    }

    private SlowQueryInsightsResponse.RemediationInsights buildRemediationInsights(
        List<QueryObservation> observations,
        int limit
    ) {
        Map<String, QueryAggregate> aggregates = aggregateByQuery(observations);

        List<SlowQueryInsightsResponse.RemediationItem> items = new ArrayList<>();
        for (QueryAggregate agg : aggregates.values()) {
            double wasteRatio = agg.rowsExamined / (double) Math.max(agg.rowsSent, 1L);
            String rootCause = inferRootCause(agg, wasteRatio);
            double improvementPct = estimatedImprovementPct(rootCause);
            long expectedSavingsMs = Math.round(agg.totalDbTimeMs * (improvementPct / 100.0));
            String confidence = confidenceForAggregate(agg);
            double confidenceWeight = "HIGH".equals(confidence) ? 1.0 : "MEDIUM".equals(confidence) ? 0.75 : 0.5;
            double priorityScore = expectedSavingsMs * fixabilityWeight(rootCause) * confidenceWeight;

            items.add(SlowQueryInsightsResponse.RemediationItem.builder()
                .queryId(agg.queryId)
                .queryPreview(agg.queryPreview)
                .queryType(agg.queryType)
                .affectedTables(new ArrayList<>(agg.affectedTables))
                .totalDbTimeMs(agg.totalDbTimeMs)
                .avgExecutionTimeMs(agg.averageAvgMs())
                .callCount(agg.totalCallCount)
                .rootCause(rootCause)
                .estimatedImprovementPct(improvementPct)
                .expectedSavingsMs(expectedSavingsMs)
                .priorityScore(round2(priorityScore))
                .severityMix(agg.severityMix())
                .confidence(confidence)
                .build());
        }

        items.sort(Comparator.comparingDouble(SlowQueryInsightsResponse.RemediationItem::getPriorityScore).reversed());
        if (items.size() > limit) {
            items = new ArrayList<>(items.subList(0, limit));
        }

        long totalSavings = items.stream().mapToLong(SlowQueryInsightsResponse.RemediationItem::getExpectedSavingsMs).sum();
        int highConfidence = (int) items.stream().filter(i -> "HIGH".equals(i.getConfidence())).count();

        return SlowQueryInsightsResponse.RemediationInsights.builder()
            .items(items)
            .totalEstimatedSavingsMs(totalSavings)
            .highConfidenceCount(highConfidence)
            .build();
    }

    private SlowQueryInsightsResponse.HotspotInsights buildHotspotInsights(
        String connectionId,
        WindowConfig windowConfig,
        List<QueryObservation> observations,
        int limit
    ) {
        Map<String, QueryAggregate> queryAgg = aggregateByQuery(observations);

        List<SlowQueryInsightsResponse.ScanWasteHotspot> scanWaste = new ArrayList<>();
        for (QueryAggregate agg : queryAgg.values()) {
            double wasteRatio = agg.rowsExamined / (double) Math.max(agg.rowsSent, 1L);
            double wasteScore = Math.log1p(Math.max(wasteRatio, 1.0)) * Math.max(agg.totalDbTimeMs, 1L);
            scanWaste.add(SlowQueryInsightsResponse.ScanWasteHotspot.builder()
                .scope("QUERY")
                .queryId(agg.queryId)
                .tableName(agg.affectedTables.isEmpty() ? null : agg.affectedTables.iterator().next())
                .wasteRatio(round2(wasteRatio))
                .wasteScore(round2(wasteScore))
                .rowsExamined(agg.rowsExamined)
                .rowsSent(agg.rowsSent)
                .totalDbTimeMs(agg.totalDbTimeMs)
                .recommendation(scanWasteRecommendation(wasteRatio))
                .build());
        }

        Map<String, TableWasteAggregate> tableWaste = new LinkedHashMap<>();
        for (QueryObservation obs : observations) {
            Set<String> tables = obs.affectedTables.isEmpty()
                ? Set.of("UNKNOWN")
                : new LinkedHashSet<>(obs.affectedTables);
            for (String tableName : tables) {
                TableWasteAggregate agg = tableWaste.computeIfAbsent(tableName, k -> new TableWasteAggregate(tableName));
                agg.rowsExamined += obs.rowsExamined;
                agg.rowsSent += obs.rowsSent;
                agg.totalDbTimeMs += obs.totalDbTimeMs;
            }
        }
        for (TableWasteAggregate agg : tableWaste.values()) {
            double wasteRatio = agg.rowsExamined / (double) Math.max(agg.rowsSent, 1L);
            double wasteScore = Math.log1p(Math.max(wasteRatio, 1.0)) * Math.max(agg.totalDbTimeMs, 1L);
            scanWaste.add(SlowQueryInsightsResponse.ScanWasteHotspot.builder()
                .scope("TABLE")
                .queryId(null)
                .tableName(agg.tableName)
                .wasteRatio(round2(wasteRatio))
                .wasteScore(round2(wasteScore))
                .rowsExamined(agg.rowsExamined)
                .rowsSent(agg.rowsSent)
                .totalDbTimeMs(agg.totalDbTimeMs)
                .recommendation(scanWasteRecommendation(wasteRatio))
                .build());
        }

        scanWaste.sort(Comparator.comparingDouble(SlowQueryInsightsResponse.ScanWasteHotspot::getWasteScore).reversed());
        if (scanWaste.size() > limit) {
            scanWaste = new ArrayList<>(scanWaste.subList(0, limit));
        }

        List<SlowQueryInsightsResponse.LockHotspot> lockHotspots =
            buildLockHotspotsFromPerformanceHistory(connectionId, windowConfig.since, limit);
        boolean lockDataAvailable = !lockHotspots.isEmpty() && lockHotspots.stream()
            .anyMatch(h -> "QUERY_PERFORMANCE_HISTORY".equals(h.getSource()));

        if (lockHotspots.isEmpty()) {
            lockHotspots = buildLockHotspotsFromExplainSignals(observations, limit);
        }

        return SlowQueryInsightsResponse.HotspotInsights.builder()
            .scanWaste(scanWaste)
            .lockHotspots(lockHotspots)
            .lockDataAvailable(lockDataAvailable)
            .build();
    }

    private List<SlowQueryInsightsResponse.LockHotspot> buildLockHotspotsFromPerformanceHistory(
        String connectionId,
        LocalDateTime since,
        int limit
    ) {
        List<QueryPerformanceHistory> records = performanceHistoryRepository.findRecentHistory(
            connectionId,
            since,
            PageRequest.of(0, 2000)
        );

        Map<String, LockAggregate> lockMap = new LinkedHashMap<>();
        for (QueryPerformanceHistory row : records) {
            if (row.getLockWaitMs() == null || row.getLockWaitMs() <= 0) {
                continue;
            }
            String key = row.getQueryHash();
            LockAggregate agg = lockMap.computeIfAbsent(key, k -> new LockAggregate(row.getQueryHash()));
            agg.sampleCount++;
            agg.totalLockWaitMs += row.getLockWaitMs();
            agg.totalExecutionMs += defaultDouble(row.getExecutionTimeMs());
        }

        List<SlowQueryInsightsResponse.LockHotspot> hotspots = lockMap.values().stream()
            .map(agg -> {
                double avgLock = agg.sampleCount > 0 ? agg.totalLockWaitMs / agg.sampleCount : 0;
                double amplification = agg.totalLockWaitMs / Math.max(agg.totalExecutionMs, 1.0);
                return SlowQueryInsightsResponse.LockHotspot.builder()
                    .scope("QUERY")
                    .queryId(agg.queryId)
                    .tableName(null)
                    .totalLockWaitMs(round2(agg.totalLockWaitMs))
                    .avgLockWaitMs(round2(avgLock))
                    .lockAmplification(round2(amplification))
                    .sampleCount(agg.sampleCount)
                    .riskLevel(lockRiskLevel(amplification))
                    .source("QUERY_PERFORMANCE_HISTORY")
                    .build();
            })
            .sorted(Comparator.comparingDouble(SlowQueryInsightsResponse.LockHotspot::getTotalLockWaitMs).reversed())
            .collect(Collectors.toList());

        if (hotspots.size() > limit) {
            return new ArrayList<>(hotspots.subList(0, limit));
        }
        return hotspots;
    }

    private List<SlowQueryInsightsResponse.LockHotspot> buildLockHotspotsFromExplainSignals(
        List<QueryObservation> observations,
        int limit
    ) {
        Map<String, QueryAggregate> aggregates = aggregateByQuery(observations);
        List<SlowQueryInsightsResponse.LockHotspot> hotspots = new ArrayList<>();
        for (QueryAggregate agg : aggregates.values()) {
            if (agg.lockSignalCount == 0) {
                continue;
            }
            double proxyLockWait = agg.totalDbTimeMs * 0.2;
            double amplification = proxyLockWait / Math.max(agg.totalDbTimeMs, 1L);
            hotspots.add(SlowQueryInsightsResponse.LockHotspot.builder()
                .scope("QUERY")
                .queryId(agg.queryId)
                .tableName(agg.affectedTables.isEmpty() ? null : agg.affectedTables.iterator().next())
                .totalLockWaitMs(round2(proxyLockWait))
                .avgLockWaitMs(round2(proxyLockWait / Math.max(agg.observationCount, 1)))
                .lockAmplification(round2(amplification))
                .sampleCount(agg.observationCount)
                .riskLevel(lockRiskLevel(amplification))
                .source("EXPLAIN_SIGNAL_PROXY")
                .build());
        }

        hotspots.sort(Comparator.comparingDouble(SlowQueryInsightsResponse.LockHotspot::getTotalLockWaitMs).reversed());
        if (hotspots.size() > limit) {
            hotspots = new ArrayList<>(hotspots.subList(0, limit));
        }
        return hotspots;
    }

    private SlowQueryInsightsResponse.SkewInsights buildSkewInsights(
        String connectionId,
        ObservationExtraction extraction,
        int limit
    ) {
        Optional<KeyCustomerResult> keyCustomersOpt = keyCustomerService.analyze(connectionId, Math.max(limit * 2, 20), null);
        if (keyCustomersOpt.isEmpty() || keyCustomersOpt.get().getKeyCustomers() == null) {
            return SlowQueryInsightsResponse.SkewInsights.builder()
                .items(Collections.emptyList())
                .capped(extraction.cappedAnalyses > 0)
                .build();
        }

        KeyCustomerResult keyCustomerResult = keyCustomersOpt.get();
        int analyzed = Math.max(keyCustomerResult.getTotalSlowQueriesAnalyzed(), 1);

        List<SlowQueryInsightsResponse.SkewItem> items = keyCustomerResult.getKeyCustomers().stream()
            .map(kc -> {
                double dominance = 100.0 * kc.getSlowQueryCount() / analyzed;
                int matchCount = kc.getMatchingQueryIds() != null ? kc.getMatchingQueryIds().size() : 0;
                double blast = matchCount * (1.0 + (kc.getCriticalCount() * 0.6) + (kc.getHighCount() * 0.3));
                // Use raw value for skew insights — these are operational
                // column values (customer_id, status) not PII
                String skewDisplayValue = kc.getRawValue() != null
                    ? kc.getRawValue() : kc.getDisplayValue();
                return SlowQueryInsightsResponse.SkewItem.builder()
                    .id(kc.getId())
                    .tableName(kc.getTableName())
                    .columnName(kc.getColumnName())
                    .displayValue(skewDisplayValue)
                    .slowQueryCount(kc.getSlowQueryCount())
                    .criticalCount(kc.getCriticalCount())
                    .highCount(kc.getHighCount())
                    .dominancePct(round2(dominance))
                    .blastRadiusScore(round2(blast))
                    .riskLevel(skewRiskLevel(dominance, kc.getCriticalCount(), kc.getHighCount()))
                    .matchingQueryIds(kc.getMatchingQueryIds())
                    .worstQueryPreview(kc.getWorstQueryPreview())
                    .build();
            })
            .sorted(Comparator.comparingDouble(
                item -> -1.0 * item.getDominancePct() * Math.max(item.getBlastRadiusScore(), 1.0)))
            .collect(Collectors.toList());

        if (items.size() > limit) {
            items = new ArrayList<>(items.subList(0, limit));
        }

        return SlowQueryInsightsResponse.SkewInsights.builder()
            .items(items)
            .capped(Boolean.TRUE.equals(keyCustomerResult.isQueriesCapped()) || extraction.cappedAnalyses > 0)
            .build();
    }

    private SlowQueryInsightsResponse.TailRiskInsights buildTailRiskInsights(
        List<QueryObservation> observations,
        int limit
    ) {
        Map<String, List<QueryObservation>> byQuery = observations.stream()
            .collect(Collectors.groupingBy(o -> o.queryId, LinkedHashMap::new, Collectors.toList()));

        List<SlowQueryInsightsResponse.TailRiskItem> tailRisks = new ArrayList<>();
        for (Map.Entry<String, List<QueryObservation>> entry : byQuery.entrySet()) {
            List<QueryObservation> queryObs = entry.getValue();
            if (queryObs.isEmpty()) {
                continue;
            }

            List<Double> durations = queryObs.stream()
                .map(o -> Math.max(o.avgExecutionTimeMs, 0.0))
                .sorted()
                .toList();
            double p50 = percentile(durations, 50);
            double p95 = percentile(durations, 95);
            double p99 = percentile(durations, 99);
            double mean = durations.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double stdDev = stdDev(durations, mean);
            double cv = mean > 0 ? stdDev / mean : 0;
            double tailRatio = p95 / Math.max(p50, 1.0);
            double burstFactor = burstFactor(queryObs);
            long totalDbTime = queryObs.stream().mapToLong(o -> o.totalDbTimeMs).sum();
            double riskScore = tailRatio
                * Math.log1p(Math.max(totalDbTime, 1))
                * (1 + Math.min(cv, 3.0) / 3.0)
                * (1 + Math.min(burstFactor, 10.0) / 10.0);

            String recommendation = tailRecommendation(tailRatio, cv, burstFactor);
            QueryObservation latest = queryObs.stream()
                .max(Comparator.comparing(o -> o.timestamp))
                .orElse(queryObs.get(0));

            tailRisks.add(SlowQueryInsightsResponse.TailRiskItem.builder()
                .queryId(entry.getKey())
                .queryPreview(latest.queryPreview)
                .p50Ms(round2(p50))
                .p95Ms(round2(p95))
                .p99Ms(round2(p99))
                .tailRatio(round2(tailRatio))
                .coefficientOfVariation(round2(cv))
                .burstFactor(round2(burstFactor))
                .totalDbTimeMs(totalDbTime)
                .riskScore(round2(riskScore))
                .recommendation(recommendation)
                .build());
        }

        tailRisks.sort(Comparator.comparingDouble(SlowQueryInsightsResponse.TailRiskItem::getRiskScore).reversed());
        if (tailRisks.size() > limit) {
            tailRisks = new ArrayList<>(tailRisks.subList(0, limit));
        }

        List<SlowQueryInsightsResponse.BurstWindow> burstWindows = buildBurstWindows(observations, Math.min(limit, 10));

        return SlowQueryInsightsResponse.TailRiskInsights.builder()
            .tailRisks(tailRisks)
            .burstWindows(burstWindows)
            .build();
    }

    private List<SlowQueryInsightsResponse.BurstWindow> buildBurstWindows(
        List<QueryObservation> observations,
        int limit
    ) {
        Map<LocalDateTime, BurstAggregate> byWindow = new LinkedHashMap<>();
        for (QueryObservation obs : observations) {
            LocalDateTime bucket = obs.timestamp != null
                ? obs.timestamp.truncatedTo(ChronoUnit.HOURS)
                : LocalDateTime.now().truncatedTo(ChronoUnit.HOURS);
            BurstAggregate agg = byWindow.computeIfAbsent(bucket, BurstAggregate::new);
            agg.totalDbTimeMs += obs.totalDbTimeMs;
            agg.totalCallCount += obs.callCount;
            agg.queryDbTime.merge(obs.queryId, obs.totalDbTimeMs, Long::sum);
        }

        List<Long> dbTimes = byWindow.values().stream().map(a -> a.totalDbTimeMs).sorted().toList();
        double highThreshold = percentileLong(dbTimes, 90);
        double medThreshold = percentileLong(dbTimes, 60);

        List<SlowQueryInsightsResponse.BurstWindow> windows = byWindow.values().stream()
            .sorted(Comparator.comparingLong((BurstAggregate b) -> b.totalDbTimeMs).reversed())
            .map(agg -> {
                List<String> topQueries = agg.queryDbTime.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .limit(3)
                    .map(Map.Entry::getKey)
                    .toList();
                String risk;
                if (agg.totalDbTimeMs >= highThreshold) {
                    risk = "HIGH";
                } else if (agg.totalDbTimeMs >= medThreshold) {
                    risk = "MEDIUM";
                } else {
                    risk = "LOW";
                }
                return SlowQueryInsightsResponse.BurstWindow.builder()
                    .windowStart(agg.windowStart)
                    .totalDbTimeMs(agg.totalDbTimeMs)
                    .totalCallCount(agg.totalCallCount)
                    .topQueryIds(topQueries)
                    .riskLevel(risk)
                    .build();
            })
            .collect(Collectors.toList());

        if (windows.size() > limit) {
            windows = new ArrayList<>(windows.subList(0, limit));
        }
        return windows;
    }

    private SlowQueryInsightsResponse.PlanDriftInsights buildPlanDriftInsights(
        String connectionId,
        WindowConfig windowConfig,
        List<QueryObservation> observations,
        int limit
    ) {
        List<QueryPlanComparison> comparisons = planComparisonRepository
            .findTop50ByConnectionIdOrderByComparedAtDesc(connectionId)
            .stream()
            .filter(c -> c.getComparedAt() == null || !c.getComparedAt().isBefore(windowConfig.since))
            .toList();

        if (!comparisons.isEmpty()) {
            List<SlowQueryInsightsResponse.PlanDriftItem> items = comparisons.stream()
                .limit(limit)
                .map(c -> {
                    String queryPreview = fingerprintRepository
                        .findByConnectionIdAndFingerprint(connectionId, c.getQueryHash())
                        .map(QueryFingerprint::getNormalizedQuery)
                        .map(q -> truncate(q, 180))
                        .orElse(truncate(c.getQueryHash(), 180));

                    return SlowQueryInsightsResponse.PlanDriftItem.builder()
                        .queryId(c.getQueryHash())
                        .queryPreview(queryPreview)
                        .previousPlanSignature(c.getBaselinePlanId())
                        .currentPlanSignature(c.getCurrentPlanId())
                        .planChangeCount(Boolean.TRUE.equals(c.getPlanChanged()) ? 1 : 0)
                        .costChangePct(round2(defaultDouble(c.getCostChangePercent())))
                        .runtimeRegressionPct(round2(defaultDouble(c.getCostChangePercent())))
                        .severity(c.getSeverity() != null ? c.getSeverity().name() : "INFO")
                        .detectedAt(c.getComparedAt())
                        .source("QUERY_PLAN_COMPARISON")
                        .notes(truncate(c.getSummary(), 220))
                        .build();
                })
                .collect(Collectors.toList());

            int critical = (int) items.stream().filter(i -> "CRITICAL".equalsIgnoreCase(i.getSeverity())).count();
            return SlowQueryInsightsResponse.PlanDriftInsights.builder()
                .totalPlanDriftQueries(items.size())
                .criticalRegressions(critical)
                .fromPlanComparisonTable(true)
                .items(items)
                .build();
        }

        Map<String, List<QueryObservation>> byQuery = observations.stream()
            .filter(o -> o.planSignature != null && !o.planSignature.isBlank())
            .collect(Collectors.groupingBy(o -> o.queryId, LinkedHashMap::new, Collectors.toList()));

        List<SlowQueryInsightsResponse.PlanDriftItem> items = new ArrayList<>();
        for (Map.Entry<String, List<QueryObservation>> entry : byQuery.entrySet()) {
            List<QueryObservation> queryObs = entry.getValue().stream()
                .sorted(Comparator.comparing(o -> o.timestamp))
                .toList();
            Set<String> signatures = queryObs.stream()
                .map(o -> o.planSignature)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
            if (signatures.size() <= 1) {
                continue;
            }

            String previousSignature = signatures.iterator().next();
            String currentSignature = queryObs.get(queryObs.size() - 1).planSignature;
            double prevAvg = queryObs.stream()
                .filter(o -> Objects.equals(o.planSignature, previousSignature))
                .mapToDouble(o -> o.avgExecutionTimeMs)
                .average().orElse(0);
            double currAvg = queryObs.stream()
                .filter(o -> Objects.equals(o.planSignature, currentSignature))
                .mapToDouble(o -> o.avgExecutionTimeMs)
                .average().orElse(0);
            double regressionPct = prevAvg > 0 ? ((currAvg - prevAvg) / prevAvg) * 100.0 : 0.0;

            String severity;
            if (regressionPct > 100) {
                severity = "CRITICAL";
            } else if (regressionPct > 30) {
                severity = "WARNING";
            } else {
                severity = "INFO";
            }

            QueryObservation latest = queryObs.get(queryObs.size() - 1);
            items.add(SlowQueryInsightsResponse.PlanDriftItem.builder()
                .queryId(entry.getKey())
                .queryPreview(latest.queryPreview)
                .previousPlanSignature(previousSignature)
                .currentPlanSignature(currentSignature)
                .planChangeCount(signatures.size() - 1)
                .costChangePct(0)
                .runtimeRegressionPct(round2(regressionPct))
                .severity(severity)
                .detectedAt(latest.timestamp)
                .source("SLOW_QUERY_EXPLAIN_SIGNATURE")
                .notes("Detected plan signature change across ingested slow-query analyses")
                .build());
        }

        items.sort(Comparator.comparingDouble(
            (SlowQueryInsightsResponse.PlanDriftItem item) -> Math.abs(item.getRuntimeRegressionPct())
        ).reversed());
        if (items.size() > limit) {
            items = new ArrayList<>(items.subList(0, limit));
        }
        int critical = (int) items.stream().filter(i -> "CRITICAL".equalsIgnoreCase(i.getSeverity())).count();

        return SlowQueryInsightsResponse.PlanDriftInsights.builder()
            .totalPlanDriftQueries(items.size())
            .criticalRegressions(critical)
            .fromPlanComparisonTable(false)
            .items(items)
            .build();
    }

    private Map<String, QueryAggregate> aggregateByQuery(List<QueryObservation> observations) {
        Map<String, QueryAggregate> result = new LinkedHashMap<>();
        for (QueryObservation obs : observations) {
            QueryAggregate agg = result.computeIfAbsent(obs.queryId, k -> new QueryAggregate(obs.queryId));
            agg.queryPreview = obs.queryPreview;
            agg.queryType = obs.queryType;
            agg.affectedTables.addAll(obs.affectedTables);
            agg.totalDbTimeMs += obs.totalDbTimeMs;
            agg.totalCallCount += obs.callCount;
            agg.totalAvgExecutionMs += obs.avgExecutionTimeMs;
            agg.observationCount++;
            agg.rowsExamined += obs.rowsExamined;
            agg.rowsSent += obs.rowsSent;
            agg.suggestedIndexesCount += obs.suggestedIndexesCount;
            if (obs.hasLockSignal) {
                agg.lockSignalCount++;
            }
            agg.severityCounts.merge(obs.severity, 1, Integer::sum);
        }
        return result;
    }

    private String inferRootCause(QueryAggregate agg, double wasteRatio) {
        String queryType = agg.queryType != null ? agg.queryType : "UNKNOWN";
        if (agg.lockSignalCount > 0 && agg.lockSignalCount >= Math.max(1, agg.observationCount / 2)) {
            return "LOCK_CONTENTION";
        }
        if (wasteRatio >= 100) {
            return "SCAN_WASTE";
        }
        if (agg.suggestedIndexesCount > 0) {
            return "MISSING_INDEX";
        }
        if (("UPDATE".equals(queryType) || "DELETE".equals(queryType)) && agg.totalCallCount > 1000) {
            return "WRITE_CONTENTION";
        }
        return "GENERAL_TUNING";
    }

    private double estimatedImprovementPct(String rootCause) {
        return switch (rootCause) {
            case "SCAN_WASTE" -> 45.0;
            case "MISSING_INDEX" -> 40.0;
            case "LOCK_CONTENTION" -> 30.0;
            case "WRITE_CONTENTION" -> 25.0;
            default -> 20.0;
        };
    }

    private double fixabilityWeight(String rootCause) {
        return switch (rootCause) {
            case "MISSING_INDEX" -> 1.2;
            case "SCAN_WASTE" -> 1.1;
            case "LOCK_CONTENTION" -> 0.9;
            default -> 1.0;
        };
    }

    private String confidenceForAggregate(QueryAggregate agg) {
        boolean hasRows = agg.rowsExamined > 0 || agg.rowsSent > 0;
        if (agg.observationCount >= 4 && hasRows) {
            return "HIGH";
        }
        if (agg.observationCount >= 2) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private String scanWasteRecommendation(double wasteRatio) {
        if (wasteRatio >= 100) {
            return "Add selective indexes and tighten WHERE predicates";
        }
        if (wasteRatio >= 20) {
            return "Review filter selectivity and composite index order";
        }
        return "Monitor and validate index effectiveness";
    }

    private String lockRiskLevel(double lockAmplification) {
        if (lockAmplification >= 0.50) {
            return "HIGH";
        }
        if (lockAmplification >= 0.20) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private String skewRiskLevel(double dominancePct, int criticalCount, int highCount) {
        if (dominancePct >= 30 || criticalCount >= 2) {
            return "HIGH";
        }
        if (dominancePct >= 15 || highCount >= 3) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private String tailRecommendation(double tailRatio, double cv, double burstFactor) {
        if (tailRatio >= 4.0 && burstFactor >= 2.0) {
            return "Burst-driven tail latency: add throttling/caching and tune hot predicates";
        }
        if (cv >= 1.0) {
            return "High variance: inspect parameter skew and plan stability";
        }
        if (tailRatio >= 2.5) {
            return "Elevated tail latency: prioritize index and join-shape optimization";
        }
        return "Monitor trend and keep regression alerts active";
    }

    private double burstFactor(List<QueryObservation> observations) {
        Map<LocalDateTime, Long> callsByHour = new HashMap<>();
        for (QueryObservation obs : observations) {
            LocalDateTime bucket = obs.timestamp != null
                ? obs.timestamp.truncatedTo(ChronoUnit.HOURS)
                : LocalDateTime.now().truncatedTo(ChronoUnit.HOURS);
            callsByHour.merge(bucket, obs.callCount, Long::sum);
        }
        if (callsByHour.isEmpty()) {
            return 1.0;
        }
        double avg = callsByHour.values().stream().mapToLong(Long::longValue).average().orElse(1.0);
        long max = callsByHour.values().stream().mapToLong(Long::longValue).max().orElse(1L);
        return avg > 0 ? max / avg : 1.0;
    }

    private String resolveQueryId(SlowQuery query) {
        if (query == null) {
            return null;
        }
        if (query.getQueryId() != null && !query.getQueryId().isBlank()) {
            return query.getQueryId();
        }
        String normalized = query.getNormalizedQuery();
        if (normalized == null || normalized.isBlank()) {
            normalized = query.getQueryText();
        }
        if (normalized == null || normalized.isBlank()) {
            return null;
        }
        return "nq_" + hashShort(normalized);
    }

    private String buildQueryPreview(SlowQuery query) {
        if (query == null) {
            return "—";
        }
        String sql = query.getNormalizedQuery();
        if (sql == null || sql.isBlank()) {
            sql = query.getQueryText();
        }
        if (sql == null || sql.isBlank()) {
            return "—";
        }
        return truncate(sql.replaceAll("\\s+", " ").trim(), 180);
    }

    private String detectQueryType(SlowQuery query) {
        if (query == null) {
            return "UNKNOWN";
        }
        String sql = query.getNormalizedQuery();
        if (sql == null || sql.isBlank()) {
            sql = query.getQueryText();
        }
        if (sql == null || sql.isBlank()) {
            return "UNKNOWN";
        }
        String upper = sql.trim().toUpperCase(Locale.ROOT);
        if (upper.startsWith("SELECT")) return "SELECT";
        if (upper.startsWith("UPDATE")) return "UPDATE";
        if (upper.startsWith("DELETE")) return "DELETE";
        if (upper.startsWith("INSERT")) return "INSERT";
        if (upper.startsWith("WITH")) return "CTE";
        return "OTHER";
    }

    private boolean hasLockSignal(SlowQuery query) {
        if (query == null || query.getExplainAnalysis() == null || query.getExplainAnalysis().getIssues() == null) {
            return false;
        }
        for (PerformanceIssue issue : query.getExplainAnalysis().getIssues()) {
            if (issue == null) {
                continue;
            }
            String type = issue.getType() != null ? issue.getType().name() : "";
            String message = issue.getMessage() != null ? issue.getMessage() : "";
            String recommendation = issue.getRecommendation() != null ? issue.getRecommendation() : "";
            String combined = (type + " " + message + " " + recommendation).toLowerCase(Locale.ROOT);
            if (combined.contains("lock") || combined.contains("wait") || combined.contains("deadlock")) {
                return true;
            }
        }
        return false;
    }

    private long resolveTotalDbTimeMs(SlowQuery query) {
        if (query == null) {
            return 0;
        }
        if (query.getTotalExecutionTimeMs() != null && query.getTotalExecutionTimeMs() > 0) {
            return Math.round(query.getTotalExecutionTimeMs());
        }
        double avg = defaultDouble(query.getAvgExecutionTimeMs());
        long calls = defaultLong(query.getCallCount(), 1);
        return Math.round(avg * Math.max(calls, 1));
    }

    private WindowConfig parseWindow(String window) {
        String candidate = window != null ? window.trim().toLowerCase(Locale.ROOT) : "";
        int amount = 7;
        char unit = 'd';

        Matcher matcher = WINDOW_PATTERN.matcher(candidate);
        if (matcher.matches()) {
            amount = Integer.parseInt(matcher.group(1));
            unit = matcher.group(2).toLowerCase(Locale.ROOT).charAt(0);
        }

        if (unit == 'h') {
            amount = Math.min(Math.max(amount, 1), 24 * 365);
        } else {
            unit = 'd';
            amount = Math.min(Math.max(amount, 1), 365);
        }

        LocalDateTime since = unit == 'h'
            ? LocalDateTime.now().minusHours(amount)
            : LocalDateTime.now().minusDays(amount);

        return new WindowConfig(amount + String.valueOf(unit), since);
    }

    private int normalizeLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private double percentile(List<Double> sortedValues, int percentile) {
        if (sortedValues == null || sortedValues.isEmpty()) {
            return 0;
        }
        if (sortedValues.size() == 1) {
            return sortedValues.get(0);
        }
        double rank = (percentile / 100.0) * (sortedValues.size() - 1);
        int low = (int) Math.floor(rank);
        int high = (int) Math.ceil(rank);
        if (low == high) {
            return sortedValues.get(low);
        }
        double weight = rank - low;
        return sortedValues.get(low) + weight * (sortedValues.get(high) - sortedValues.get(low));
    }

    private double percentileLong(List<Long> sortedValues, int percentile) {
        if (sortedValues == null || sortedValues.isEmpty()) {
            return 0;
        }
        List<Double> asDouble = sortedValues.stream().map(Long::doubleValue).toList();
        return percentile(asDouble, percentile);
    }

    private double stdDev(List<Double> values, double mean) {
        if (values == null || values.size() < 2) {
            return 0;
        }
        double variance = values.stream()
            .mapToDouble(v -> Math.pow(v - mean, 2))
            .average()
            .orElse(0);
        return Math.sqrt(variance);
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "...";
    }

    private String hashShort(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.substring(0, 16);
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private long defaultLong(Long value, long fallback) {
        return value != null ? value : fallback;
    }

    private double defaultDouble(Double value) {
        return value != null ? value : 0;
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private record WindowConfig(String normalizedWindow, LocalDateTime since) {}

    private static class ObservationExtraction {
        final List<QueryObservation> observations;
        final int historiesAnalyzed;
        final int cappedAnalyses;
        final long totalSlowQueriesReported;
        final long totalQueriesCaptured;

        ObservationExtraction(
            List<QueryObservation> observations,
            int historiesAnalyzed,
            int cappedAnalyses,
            long totalSlowQueriesReported,
            long totalQueriesCaptured
        ) {
            this.observations = observations;
            this.historiesAnalyzed = historiesAnalyzed;
            this.cappedAnalyses = cappedAnalyses;
            this.totalSlowQueriesReported = totalSlowQueriesReported;
            this.totalQueriesCaptured = totalQueriesCaptured;
        }
    }

    @lombok.Builder
    private static class QueryObservation {
        LocalDateTime timestamp;
        String queryId;
        String queryPreview;
        String queryType;
        List<String> affectedTables;
        double avgExecutionTimeMs;
        long callCount;
        long totalDbTimeMs;
        long rowsExamined;
        long rowsSent;
        String severity;
        int suggestedIndexesCount;
        boolean hasLockSignal;
        String planSignature;
    }

    private static class QueryAggregate {
        final String queryId;
        String queryPreview;
        String queryType;
        final Set<String> affectedTables = new LinkedHashSet<>();
        long totalDbTimeMs;
        long totalCallCount;
        double totalAvgExecutionMs;
        int observationCount;
        long rowsExamined;
        long rowsSent;
        int suggestedIndexesCount;
        int lockSignalCount;
        final Map<String, Integer> severityCounts = new HashMap<>();

        QueryAggregate(String queryId) {
            this.queryId = queryId;
        }

        double averageAvgMs() {
            return observationCount > 0 ? round(totalAvgExecutionMs / observationCount) : 0;
        }

        String severityMix() {
            int c = severityCounts.getOrDefault("CRITICAL", 0);
            int h = severityCounts.getOrDefault("HIGH", 0);
            int m = severityCounts.getOrDefault("MEDIUM", 0);
            int l = severityCounts.getOrDefault("LOW", 0);
            return "C:" + c + " H:" + h + " M:" + m + " L:" + l;
        }

        private double round(double value) {
            return Math.round(value * 100.0) / 100.0;
        }
    }

    private static class TableWasteAggregate {
        final String tableName;
        long rowsExamined;
        long rowsSent;
        long totalDbTimeMs;

        TableWasteAggregate(String tableName) {
            this.tableName = tableName;
        }
    }

    private static class LockAggregate {
        final String queryId;
        int sampleCount;
        double totalLockWaitMs;
        double totalExecutionMs;

        LockAggregate(String queryId) {
            this.queryId = queryId;
        }
    }

    private static class BurstAggregate {
        final LocalDateTime windowStart;
        long totalDbTimeMs;
        long totalCallCount;
        final Map<String, Long> queryDbTime = new HashMap<>();

        BurstAggregate(LocalDateTime windowStart) {
            this.windowStart = windowStart;
        }
    }
}
