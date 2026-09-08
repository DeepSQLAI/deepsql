package com.dbaagent.service.digest;

import com.dbaagent.model.*;
import com.dbaagent.model.brain.*;
import com.dbaagent.model.digest.*;
import com.dbaagent.repository.*;
import com.dbaagent.repository.brain.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Assembles role-aware, personalized digest insights from Brain stores.
 *
 * <p>This service mines insights from multiple sources:
 * <ul>
 *   <li>Brain V2 alerts (workload changes, config drift, plan regressions)</li>
 *   <li>Slow query analysis (performance regressions, lock contention)</li>
 *   <li>Index recommendations (missing indexes, unused indexes)</li>
 *   <li>Schema changes (new tables, breaking changes)</li>
 *   <li>Growth anomalies (size spikes, unexpected growth)</li>
 *   <li>Brain scores and learning progress</li>
 * </ul>
 *
 * <h3>Ranking Formula</h3>
 * <pre>
 * score = categoryWeight × severityMultiplier × freshnessMultiplier
 *       × personaRelevance × actionabilityBonus
 * </pre>
 *
 * <h3>Suppression Logic</h3>
 * <ul>
 *   <li>Acknowledged insights are filtered out</li>
 *   <li>Duplicate signatures from last digest are suppressed unless changed</li>
 *   <li>INFO-level insights are deprioritized when many higher-severity exist</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DigestInsightAssemblerService {

    private final BrainV2AlertRepository brainAlertRepository;
    private final BrainScoreRepository brainScoreRepository;
    private final BrainLearningProgressRepository learningProgressRepository;
    private final PlanPatternRepository planPatternRepository;
    private final IndexRecommendationRepository indexRecommendationRepository;
    private final SchemaChangeRepository schemaChangeRepository;
    private final GrowthAnomalyRepository growthAnomalyRepository;
    private final PlaybookAlertRepository playbookAlertRepository;
    private final SlackDigestLogRepository digestLogRepository;
    private final SlowQueryHistoryRepository slowQueryHistoryRepository;
    private final LockContentionRepository lockContentionRepository;

    private static final int DEFAULT_MAX_INSIGHTS = 15;
    private static final int EXEC_MAX_INSIGHTS = 5;

    /**
     * Assemble a personalized digest for a user.
     *
     * @param username      the user receiving the digest
     * @param connectionId  the database connection to report on
     * @param role          user's RBAC role
     * @param personaTag    optional persona for content prioritization
     * @param sinceLast     time window start (null = last 24 hours)
     * @return assembled digest with ranked insights
     */
    public DigestAssemblyResult assembleDigest(String username, String connectionId,
                                                Role role, PersonaTag personaTag,
                                                LocalDateTime sinceLast) {
        LocalDateTime windowStart = sinceLast != null ? sinceLast : LocalDateTime.now().minusHours(24);
        LocalDateTime windowEnd = LocalDateTime.now();

        log.debug("Assembling digest for user={}, connection={}, persona={}, window=[{} to {}]",
            username, connectionId, personaTag, windowStart, windowEnd);

        List<DigestInsight> candidates = new ArrayList<>();

        // Mine from all Brain stores — each method surfaces signals differently
        // based on what's available in the store (persona filtering happens at ranking)
        candidates.addAll(mineBrainAlerts(connectionId, windowStart));
        candidates.addAll(mineIndexRecommendations(connectionId));
        candidates.addAll(mineSchemaChanges(connectionId, windowStart));
        candidates.addAll(mineGrowthAnomalies(connectionId, windowStart));
        candidates.addAll(minePlaybookAlerts(connectionId, windowStart));
        candidates.addAll(mineSlowQueryInsights(connectionId, windowStart));
        candidates.addAll(mineBrainProgress(connectionId));
        candidates.addAll(mineLockContention(connectionId, windowStart));

        int totalCandidates = candidates.size();
        log.debug("Mined {} candidate insights from all sources", totalCandidates);

        int filteredAcknowledged = (int) candidates.stream()
            .filter(DigestInsight::isAcknowledged)
            .count();
        candidates = candidates.stream()
            .filter(i -> !i.isAcknowledged())
            .collect(Collectors.toList());

        Set<String> lastDigestSignatures = getLastDigestSignatures(connectionId, username);
        int suppressedDuplicates = 0;
        List<DigestInsight> filtered = new ArrayList<>();
        for (DigestInsight insight : candidates) {
            if (lastDigestSignatures.contains(insight.getSignatureKey())) {
                if (!hasSignificantChange(insight)) {
                    suppressedDuplicates++;
                    continue;
                }
            }
            filtered.add(insight);
        }
        candidates = filtered;

        List<DigestInsight> ranked = rankInsights(candidates, role, personaTag);

        int maxInsights = personaTag == PersonaTag.EXEC ? EXEC_MAX_INSIGHTS : DEFAULT_MAX_INSIGHTS;
        ranked = ranked.stream().limit(maxInsights).toList();

        Map<InsightCategory, Integer> categoryCounts = ranked.stream()
            .collect(Collectors.groupingBy(
                DigestInsight::getCategory,
                Collectors.collectingAndThen(Collectors.counting(), Long::intValue)
            ));

        DigestAssemblyResult.DigestAssemblyResultBuilder builder = DigestAssemblyResult.builder()
            .username(username)
            .connectionId(connectionId)
            .role(role)
            .personaTag(personaTag)
            .assembledAt(LocalDateTime.now())
            .windowStart(windowStart)
            .windowEnd(windowEnd)
            .insights(ranked)
            .categoryCounts(categoryCounts)
            .totalCandidates(totalCandidates)
            .suppressedDuplicates(suppressedDuplicates)
            .filteredAcknowledged(filteredAcknowledged)
            .empty(ranked.isEmpty());

        if (personaTag == PersonaTag.EXEC) {
            builder.executiveSummary(generateExecutiveSummary(ranked))
                   .decisionAsk(generateDecisionAsk(ranked));
        }

        return builder.build();
    }

    /**
     * Rank insights by the composite scoring formula.
     */
    List<DigestInsight> rankInsights(List<DigestInsight> candidates, Role role, PersonaTag personaTag) {
        return candidates.stream()
            .map(insight -> {
                double categoryWeight = insight.getCategory().getBaseWeight();
                double personaMultiplier = insight.getCategory().getPersonaMultiplier(personaTag);
                double roleMultiplier = getRoleMultiplier(insight.getCategory(), role);

                double score = categoryWeight
                    * insight.getSeverityMultiplier()
                    * insight.getFreshnessMultiplier()
                    * personaMultiplier
                    * roleMultiplier
                    * insight.getActionabilityBonus();

                return insight.withRankScore(score);
            })
            .sorted(Comparator.comparingDouble(DigestInsight::getRankScore).reversed())
            .collect(Collectors.toList());
    }

    private double getRoleMultiplier(InsightCategory category, Role role) {
        if (role == null) return 1.0;
        return switch (role) {
            case ADMIN -> 1.0;
            case DBA -> switch (category) {
                case LOCK_CONCURRENCY -> 1.4;  // idle-in-txn, locks are DBA operational priority
                case QUERY_PERFORMANCE, INDEX_RECOMMENDATIONS, CONFIG_TUNING -> 1.3;
                case BRAIN_INTELLIGENCE, GROWTH_ANOMALIES -> 1.2;
                default -> 1.0;
            };
            case DATA_ENGINEER -> switch (category) {
                case SCHEMA_CHANGES, DOCUMENTATION_GAPS -> 1.3;
                case GROWTH_ANOMALIES -> 1.2;
                default -> 1.0;
            };
            case DEVELOPER -> switch (category) {
                case QUERY_PERFORMANCE, SCHEMA_CHANGES -> 1.2;
                case LOCK_CONCURRENCY -> 1.1;  // ACCESS EXCLUSIVE during deploys
                default -> 1.0;
            };
        };
    }

    private List<DigestInsight> mineBrainAlerts(String connectionId, LocalDateTime since) {
        List<DigestInsight> insights = new ArrayList<>();

        List<BrainV2Alert> activeAlerts = brainAlertRepository
            .findByConnectionIdAndStatusOrderByCreatedAtDesc(connectionId, BrainV2Alert.Status.ACTIVE);

        for (BrainV2Alert alert : activeAlerts) {
            if (alert.getCreatedAt().isBefore(since)) continue;

            InsightCategory category = mapAlertTypeToCategory(alert.getAlertType());
            int severity = mapAlertSeverity(alert.getSeverity());

            DigestInsight insight = DigestInsight.builder()
                .category(category)
                .headline(alert.getTitle())
                .description(alert.getMessage())
                .severity(severity)
                .timestamp(alert.getCreatedAt())
                .actionable(alert.getRecommendedAction() != null)
                .suggestedAction(alert.getRecommendedAction())
                .sourceId(alert.getId())
                .sourceType("BrainV2Alert")
                .signatureKey(buildSignatureKey(category, "BrainV2Alert",
                    alert.getAlertType().name() + ":" + alert.getConnectionId()))
                .connectionId(connectionId)
                .contextData(alert.getContextData())
                .acknowledged(alert.getStatus() == BrainV2Alert.Status.ACKNOWLEDGED)
                .build();

            insights.add(insight);
        }

        return insights;
    }

    private List<DigestInsight> mineIndexRecommendations(String connectionId) {
        List<DigestInsight> insights = new ArrayList<>();

        List<IndexRecommendationEntity> pending = indexRecommendationRepository
            .findByConnectionIdAndStatusOrderByPriorityAscCreatedAtDesc(
                connectionId, IndexRecommendationEntity.Status.PENDING);

        Map<IndexRecommendationEntity.Priority, List<IndexRecommendationEntity>> byPriority =
            pending.stream().collect(Collectors.groupingBy(IndexRecommendationEntity::getPriority));

        for (var entry : byPriority.entrySet()) {
            IndexRecommendationEntity.Priority priority = entry.getKey();
            List<IndexRecommendationEntity> recs = entry.getValue();
            if (recs.isEmpty()) continue;

            IndexRecommendationEntity topRec = recs.get(0);
            int severity = switch (priority) {
                case HIGH -> 80;
                case MEDIUM -> 55;
                case LOW -> 30;
            };

            String headline = recs.size() == 1
                ? String.format("Index recommendation: %s", topRec.getIndexName())
                : String.format("%d %s-priority index recommendations", recs.size(), priority.name().toLowerCase());

            String description = recs.size() == 1
                ? String.format("Table: %s, Columns: %s. %s",
                    topRec.getTableName(), topRec.getColumnNames(),
                    topRec.getReason() != null ? topRec.getReason() : "")
                : String.format("Top candidate: %s on %s(%s). Estimated workload savings: %dms",
                    topRec.getIndexName(), topRec.getTableName(), topRec.getColumnNames(),
                    topRec.netBenefitMs());

            DigestInsight insight = DigestInsight.builder()
                .category(InsightCategory.INDEX_RECOMMENDATIONS)
                .headline(headline)
                .description(description)
                .severity(severity)
                .timestamp(topRec.getLastSeenAt() != null ? topRec.getLastSeenAt() : topRec.getCreatedAt())
                .actionable(true)
                .suggestedAction("Review and apply index recommendations in the Index Advisor")
                .sourceId(topRec.getId())
                .sourceType("IndexRecommendation")
                .signatureKey(buildSignatureKey(InsightCategory.INDEX_RECOMMENDATIONS,
                    "IndexRecommendation", priority.name() + ":" + recs.size()))
                .connectionId(connectionId)
                .contextData(Map.of(
                    "priority", priority.name(),
                    "count", recs.size(),
                    "topTable", topRec.getTableName(),
                    "topColumns", topRec.getColumnNames()
                ))
                .involvedTables(recs.stream().map(IndexRecommendationEntity::getTableName)
                    .distinct().toArray(String[]::new))
                .build();

            insights.add(insight);
        }

        return insights;
    }

    private List<DigestInsight> mineSchemaChanges(String connectionId, LocalDateTime since) {
        List<DigestInsight> insights = new ArrayList<>();

        List<SchemaChange> unacked = schemaChangeRepository
            .findByConnectionIdAndIsAcknowledgedFalseOrderByDetectedAtDesc(connectionId);

        List<SchemaChange> recent = unacked.stream()
            .filter(c -> c.getDetectedAt().isAfter(since))
            .toList();

        if (recent.isEmpty()) return insights;

        List<SchemaChange> breaking = recent.stream()
            .filter(c -> Boolean.TRUE.equals(c.getIsBreakingChange()))
            .toList();

        List<SchemaChange> critical = recent.stream()
            .filter(c -> c.getSeverity() == SchemaChange.Severity.CRITICAL)
            .toList();

        if (!breaking.isEmpty()) {
            SchemaChange top = breaking.get(0);
            insights.add(DigestInsight.builder()
                .category(InsightCategory.SCHEMA_CHANGES)
                .headline(String.format("%d breaking schema change%s detected",
                    breaking.size(), breaking.size() != 1 ? "s" : ""))
                .description(top.getDescription())
                .severity(95)
                .timestamp(top.getDetectedAt())
                .actionable(true)
                .suggestedAction("Review breaking changes immediately to prevent application errors")
                .sourceId(top.getId())
                .sourceType("SchemaChange")
                .signatureKey(buildSignatureKey(InsightCategory.SCHEMA_CHANGES,
                    "SchemaChange", "breaking:" + breaking.size()))
                .connectionId(connectionId)
                .contextData(Map.of(
                    "breakingCount", breaking.size(),
                    "changeTypes", breaking.stream()
                        .map(c -> c.getChangeType().name())
                        .distinct().toList()
                ))
                .involvedTables(breaking.stream()
                    .filter(c -> c.getTableName() != null)
                    .map(SchemaChange::getTableName)
                    .distinct().toArray(String[]::new))
                .build());
        } else if (!critical.isEmpty()) {
            SchemaChange top = critical.get(0);
            insights.add(DigestInsight.builder()
                .category(InsightCategory.SCHEMA_CHANGES)
                .headline(String.format("%d critical schema change%s",
                    critical.size(), critical.size() != 1 ? "s" : ""))
                .description(top.getDescription())
                .severity(80)
                .timestamp(top.getDetectedAt())
                .actionable(true)
                .suggestedAction("Review schema changes for potential impact")
                .sourceId(top.getId())
                .sourceType("SchemaChange")
                .signatureKey(buildSignatureKey(InsightCategory.SCHEMA_CHANGES,
                    "SchemaChange", "critical:" + critical.size()))
                .connectionId(connectionId)
                .build());
        } else if (recent.size() > 0) {
            SchemaChange top = recent.get(0);
            int severity = switch (top.getSeverity()) {
                case CRITICAL -> 80;
                case WARNING -> 50;
                case INFO -> 25;
            };
            insights.add(DigestInsight.builder()
                .category(InsightCategory.SCHEMA_CHANGES)
                .headline(String.format("%d schema change%s since last digest",
                    recent.size(), recent.size() != 1 ? "s" : ""))
                .description(top.getDescription())
                .severity(severity)
                .timestamp(top.getDetectedAt())
                .actionable(false)
                .sourceId(top.getId())
                .sourceType("SchemaChange")
                .signatureKey(buildSignatureKey(InsightCategory.SCHEMA_CHANGES,
                    "SchemaChange", "count:" + recent.size()))
                .connectionId(connectionId)
                .build());
        }

        return insights;
    }

    private List<DigestInsight> mineGrowthAnomalies(String connectionId, LocalDateTime since) {
        List<DigestInsight> insights = new ArrayList<>();

        List<GrowthAnomaly> unacked = growthAnomalyRepository
            .findByConnectionIdAndAcknowledgedFalseOrderByDetectionTimestampDesc(connectionId);

        List<GrowthAnomaly> recent = unacked.stream()
            .filter(a -> a.getDetectionTimestamp().isAfter(since))
            .toList();

        if (recent.isEmpty()) return insights;

        List<GrowthAnomaly> critical = recent.stream()
            .filter(a -> a.getSeverity() == GrowthAnomaly.Severity.CRITICAL)
            .toList();

        if (!critical.isEmpty()) {
            GrowthAnomaly top = critical.get(0);
            insights.add(DigestInsight.fromGrowthAnomaly()
                .headline(String.format("Critical growth anomaly: %s", top.getTableName()))
                .description(top.getDescription())
                .severity(90)
                .timestamp(top.getDetectionTimestamp())
                .actionable(true)
                .suggestedAction("Investigate table growth and consider cleanup or archiving")
                .sourceId(top.getId())
                .signatureKey(buildSignatureKey(InsightCategory.GROWTH_ANOMALIES,
                    "GrowthAnomaly", "critical:" + top.getTableName()))
                .connectionId(connectionId)
                .contextData(Map.of(
                    "tableName", top.getTableName(),
                    "anomalyType", top.getAnomalyType().name(),
                    "growthPercent", top.getSizeGrowthPercent() != null ? top.getSizeGrowthPercent() : 0
                ))
                .involvedTables(new String[]{top.getTableName()})
                .build());
        }

        List<GrowthAnomaly> warnings = recent.stream()
            .filter(a -> a.getSeverity() == GrowthAnomaly.Severity.WARNING)
            .toList();

        if (!warnings.isEmpty() && critical.isEmpty()) {
            GrowthAnomaly top = warnings.get(0);
            insights.add(DigestInsight.fromGrowthAnomaly()
                .headline(String.format("%d table%s with unusual growth",
                    warnings.size(), warnings.size() != 1 ? "s" : ""))
                .description(top.getDescription())
                .severity(55)
                .timestamp(top.getDetectionTimestamp())
                .actionable(true)
                .suggestedAction("Review growth patterns in the Growth Insights dashboard")
                .sourceId(top.getId())
                .signatureKey(buildSignatureKey(InsightCategory.GROWTH_ANOMALIES,
                    "GrowthAnomaly", "warnings:" + warnings.size()))
                .connectionId(connectionId)
                .involvedTables(warnings.stream()
                    .map(GrowthAnomaly::getTableName)
                    .distinct().toArray(String[]::new))
                .build());
        }

        return insights;
    }

    private List<DigestInsight> minePlaybookAlerts(String connectionId, LocalDateTime since) {
        List<DigestInsight> insights = new ArrayList<>();

        List<PlaybookAlert> recent = playbookAlertRepository.findRecentAlerts(connectionId, since);
        List<PlaybookAlert> unacked = recent.stream()
            .filter(a -> !Boolean.TRUE.equals(a.getAcknowledged()))
            .toList();

        if (unacked.isEmpty()) return insights;

        Map<PlaybookAlert.Severity, List<PlaybookAlert>> bySeverity = unacked.stream()
            .collect(Collectors.groupingBy(PlaybookAlert::getSeverity));

        List<PlaybookAlert> criticals = bySeverity.getOrDefault(PlaybookAlert.Severity.CRITICAL, List.of());
        if (!criticals.isEmpty()) {
            PlaybookAlert top = criticals.get(0);
            insights.add(DigestInsight.builder()
                .category(InsightCategory.SYSTEM_ALERTS)
                .headline(String.format("%d critical alert%s",
                    criticals.size(), criticals.size() != 1 ? "s" : ""))
                .description(top.getMessage())
                .severity(95)
                .timestamp(top.getCreatedAt())
                .actionable(true)
                .suggestedAction("Review critical alerts immediately")
                .sourceId(top.getId())
                .sourceType("PlaybookAlert")
                .signatureKey(buildSignatureKey(InsightCategory.SYSTEM_ALERTS,
                    "PlaybookAlert", "critical:" + criticals.size()))
                .connectionId(connectionId)
                .build());
        }

        List<PlaybookAlert> warnings = bySeverity.getOrDefault(PlaybookAlert.Severity.WARNING, List.of());
        if (!warnings.isEmpty() && criticals.isEmpty()) {
            PlaybookAlert top = warnings.get(0);
            insights.add(DigestInsight.builder()
                .category(InsightCategory.SYSTEM_ALERTS)
                .headline(String.format("%d warning alert%s", warnings.size(), warnings.size() != 1 ? "s" : ""))
                .description(top.getMessage())
                .severity(55)
                .timestamp(top.getCreatedAt())
                .actionable(true)
                .suggestedAction("Review warning alerts")
                .sourceId(top.getId())
                .sourceType("PlaybookAlert")
                .signatureKey(buildSignatureKey(InsightCategory.SYSTEM_ALERTS,
                    "PlaybookAlert", "warnings:" + warnings.size()))
                .connectionId(connectionId)
                .build());
        }

        return insights;
    }

    private List<DigestInsight> mineSlowQueryInsights(String connectionId, LocalDateTime since) {
        List<DigestInsight> insights = new ArrayList<>();

        List<SlowQueryHistory> recent = slowQueryHistoryRepository.findByConnectionIdSince(
            connectionId, since, PageRequest.of(0, 5));

        if (recent.isEmpty()) return insights;

        SlowQueryHistory latest = recent.get(0);

        if (latest.getCriticalCount() != null && latest.getCriticalCount() > 0) {
            insights.add(DigestInsight.builder()
                .category(InsightCategory.QUERY_PERFORMANCE)
                .headline(String.format("%d critical slow quer%s detected",
                    latest.getCriticalCount(), latest.getCriticalCount() != 1 ? "ies" : "y"))
                .description(String.format("Total slow queries: %d. Overall health: %s",
                    latest.getTotalSlowQueries(), latest.getOverallHealth()))
                .severity(85)
                .timestamp(latest.getCreatedAt())
                .actionable(true)
                .suggestedAction("Review slow query analysis in the Performance tab")
                .sourceId(latest.getId())
                .sourceType("SlowQueryHistory")
                .signatureKey(buildSignatureKey(InsightCategory.QUERY_PERFORMANCE,
                    "SlowQueryHistory", "critical:" + latest.getCriticalCount()))
                .connectionId(connectionId)
                .contextData(Map.of(
                    "totalSlowQueries", latest.getTotalSlowQueries(),
                    "criticalCount", latest.getCriticalCount(),
                    "highCount", latest.getHighCount() != null ? latest.getHighCount() : 0,
                    "overallHealth", latest.getOverallHealth()
                ))
                .build());
        } else if (latest.getHighCount() != null && latest.getHighCount() > 0) {
            insights.add(DigestInsight.builder()
                .category(InsightCategory.QUERY_PERFORMANCE)
                .headline(String.format("%d high-impact slow quer%s",
                    latest.getHighCount(), latest.getHighCount() != 1 ? "ies" : "y"))
                .description(String.format("Total slow queries: %d. Overall health: %s",
                    latest.getTotalSlowQueries(), latest.getOverallHealth()))
                .severity(65)
                .timestamp(latest.getCreatedAt())
                .actionable(true)
                .suggestedAction("Review slow query analysis")
                .sourceId(latest.getId())
                .sourceType("SlowQueryHistory")
                .signatureKey(buildSignatureKey(InsightCategory.QUERY_PERFORMANCE,
                    "SlowQueryHistory", "high:" + latest.getHighCount()))
                .connectionId(connectionId)
                .build());
        } else if (latest.getTotalSlowQueries() != null && latest.getTotalSlowQueries() > 10) {
            insights.add(DigestInsight.builder()
                .category(InsightCategory.QUERY_PERFORMANCE)
                .headline(String.format("%d slow queries in analysis window", latest.getTotalSlowQueries()))
                .description(String.format("Overall health: %s", latest.getOverallHealth()))
                .severity(40)
                .timestamp(latest.getCreatedAt())
                .actionable(true)
                .suggestedAction("Review slow query patterns")
                .sourceId(latest.getId())
                .sourceType("SlowQueryHistory")
                .signatureKey(buildSignatureKey(InsightCategory.QUERY_PERFORMANCE,
                    "SlowQueryHistory", "total:" + latest.getTotalSlowQueries()))
                .connectionId(connectionId)
                .build());
        }

        return insights;
    }

    private List<DigestInsight> mineBrainProgress(String connectionId) {
        List<DigestInsight> insights = new ArrayList<>();

        Optional<BrainLearningProgress> progressOpt = learningProgressRepository.findByConnectionId(connectionId);
        if (progressOpt.isPresent()) {
            BrainLearningProgress progress = progressOpt.get();

            if (Boolean.TRUE.equals(progress.getBrainV2Ready()) &&
                progress.getSuccessfulExperiments() != null &&
                progress.getSuccessfulExperiments() > 0) {

                insights.add(DigestInsight.builder()
                    .category(InsightCategory.BRAIN_INTELLIGENCE)
                    .headline("Brain 2.0 tuning recommendations available")
                    .description(String.format(
                        "Readiness: %.0f%%. %d successful experiments completed. %s",
                        progress.getReadinessPercent(),
                        progress.getSuccessfulExperiments(),
                        progress.getStageDescription()
                    ))
                    .severity(35)
                    .timestamp(progress.getUpdatedAt() != null ? progress.getUpdatedAt() : progress.getCreatedAt())
                    .actionable(true)
                    .suggestedAction("Review Brain tuning recommendations")
                    .sourceId(progress.getId())
                    .sourceType("BrainLearningProgress")
                    .signatureKey(buildSignatureKey(InsightCategory.BRAIN_INTELLIGENCE,
                        "BrainLearningProgress", "ready:" + progress.getReadinessPercent().intValue()))
                    .connectionId(connectionId)
                    .contextData(Map.of(
                        "readinessPercent", progress.getReadinessPercent(),
                        "experimentsCompleted", progress.getExperimentsCompleted(),
                        "successfulExperiments", progress.getSuccessfulExperiments()
                    ))
                    .build());
            }
        }

        Optional<BrainScore> latestScore = brainScoreRepository.findLatestByConnectionId(connectionId);
        if (latestScore.isPresent()) {
            BrainScore score = latestScore.get();
            if (score.getOverallScore() != null && score.getOverallScore().doubleValue() < 50) {
                insights.add(DigestInsight.builder()
                    .category(InsightCategory.BRAIN_INTELLIGENCE)
                    .headline(String.format("Brain health score: %.0f/100", score.getOverallScore()))
                    .description(buildScoreDescription(score))
                    .severity(60)
                    .timestamp(score.getCalculatedAt())
                    .actionable(true)
                    .suggestedAction("Review Brain insights to improve database health")
                    .sourceId(score.getId())
                    .sourceType("BrainScore")
                    .signatureKey(buildSignatureKey(InsightCategory.BRAIN_INTELLIGENCE,
                        "BrainScore", "low:" + score.getOverallScore().intValue()))
                    .connectionId(connectionId)
                    .contextData(Map.of(
                        "overallScore", score.getOverallScore(),
                        "schemaScore", score.getSchemaDesignScore() != null ? score.getSchemaDesignScore() : 0,
                        "queryScore", score.getQueryQualityScore() != null ? score.getQueryQualityScore() : 0,
                        "indexScore", score.getIndexAccessScore() != null ? score.getIndexAccessScore() : 0
                    ))
                    .build());
            }
        }

        return insights;
    }

    private String buildScoreDescription(BrainScore score) {
        StringBuilder sb = new StringBuilder();
        if (score.getSchemaDesignScore() != null) {
            sb.append(String.format("Schema: %.0f", score.getSchemaDesignScore()));
        }
        if (score.getQueryQualityScore() != null) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(String.format("Query: %.0f", score.getQueryQualityScore()));
        }
        if (score.getIndexAccessScore() != null) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(String.format("Index: %.0f", score.getIndexAccessScore()));
        }
        return sb.toString();
    }

    /**
     * Mine lock contention insights: idle-in-txn, lock waits, ACCESS EXCLUSIVE blocks.
     *
     * <h4>DBA signals:</h4>
     * <ul>
     *   <li>Idle-in-transaction sessions (holding locks, blocking others)</li>
     *   <li>Long lock waits (critical if > 60s)</li>
     *   <li>Blocking chains (one session blocks many)</li>
     * </ul>
     *
     * <h4>APP_ENG signals:</h4>
     * <ul>
     *   <li>ACCESS EXCLUSIVE locks (DDL blocking queries during deploys)</li>
     *   <li>Lock waits on tables they own</li>
     * </ul>
     */
    private List<DigestInsight> mineLockContention(String connectionId, LocalDateTime since) {
        List<DigestInsight> insights = new ArrayList<>();

        List<LockContention> recent = lockContentionRepository.findRecentForDigest(connectionId, since);
        if (recent.isEmpty()) return insights;

        // Group by severity
        long criticalCount = recent.stream()
            .filter(lc -> lc.getSeverity() == LockContention.Severity.CRITICAL)
            .count();
        long highCount = recent.stream()
            .filter(lc -> lc.getSeverity() == LockContention.Severity.HIGH)
            .count();

        // Find longest wait
        Optional<LockContention> longestWait = recent.stream()
            .filter(lc -> lc.getWaitDurationSeconds() != null)
            .max(Comparator.comparing(LockContention::getWaitDurationSeconds));

        // Check for ACCESS EXCLUSIVE (DDL blocking) — APP_ENG signal
        List<LockContention> accessExclusive = recent.stream()
            .filter(lc -> lc.getLockMode() != null &&
                         lc.getLockMode().toUpperCase().contains("EXCLUSIVE"))
            .toList();

        // Identify blocking chains (one PID blocking multiple sessions) — DBA signal
        Map<String, Long> blockingPidCounts = recent.stream()
            .filter(lc -> !Boolean.TRUE.equals(lc.getResolved()))
            .collect(Collectors.groupingBy(LockContention::getBlockingPid, Collectors.counting()));
        Optional<Map.Entry<String, Long>> topBlocker = blockingPidCounts.entrySet().stream()
            .filter(e -> e.getValue() > 1)
            .max(Map.Entry.comparingByValue());

        // Critical lock contention — DBA priority
        if (criticalCount > 0 && longestWait.isPresent()) {
            LockContention top = longestWait.get();
            String headline = criticalCount == 1
                ? String.format("Critical lock wait: %ds on %s",
                    top.getWaitDurationSeconds(),
                    top.getTableName() != null ? top.getTableName() : top.getLockTarget())
                : String.format("%d critical lock contentions (max wait: %ds)",
                    criticalCount, top.getWaitDurationSeconds());

            insights.add(DigestInsight.builder()
                .category(InsightCategory.LOCK_CONCURRENCY)
                .headline(headline)
                .description(buildLockDescription(top))
                .severity(92)
                .timestamp(top.getDetectedAt())
                .actionable(true)
                .suggestedAction("Investigate blocking session " + top.getBlockingPid() +
                    " — consider terminating if safe")
                .sourceId(top.getId())
                .sourceType("LockContention")
                .signatureKey(buildSignatureKey(InsightCategory.LOCK_CONCURRENCY,
                    "LockContention", "critical:" + criticalCount))
                .connectionId(connectionId)
                .contextData(Map.of(
                    "criticalCount", criticalCount,
                    "maxWaitSeconds", top.getWaitDurationSeconds(),
                    "blockingPid", top.getBlockingPid(),
                    "lockType", top.getLockType() != null ? top.getLockType() : "unknown"
                ))
                .involvedTables(top.getTableName() != null ? new String[]{top.getTableName()} : null)
                .build());
        }

        // ACCESS EXCLUSIVE / DDL blocking — APP_ENG priority
        if (!accessExclusive.isEmpty() && criticalCount == 0) {
            LockContention top = accessExclusive.get(0);
            insights.add(DigestInsight.builder()
                .category(InsightCategory.LOCK_CONCURRENCY)
                .headline(String.format("DDL blocking risk: %d ACCESS EXCLUSIVE lock%s",
                    accessExclusive.size(), accessExclusive.size() != 1 ? "s" : ""))
                .description(String.format("Table %s locked with %s — queries blocked until DDL completes",
                    top.getTableName() != null ? top.getTableName() : "unknown",
                    top.getLockMode()))
                .severity(75)
                .timestamp(top.getDetectedAt())
                .actionable(true)
                .suggestedAction("Schedule DDL operations during low-traffic windows")
                .sourceId(top.getId())
                .sourceType("LockContention")
                .signatureKey(buildSignatureKey(InsightCategory.LOCK_CONCURRENCY,
                    "LockContention", "ddl:" + accessExclusive.size()))
                .connectionId(connectionId)
                .contextData(Map.of(
                    "accessExclusiveCount", accessExclusive.size(),
                    "lockMode", top.getLockMode()
                ))
                .build());
        }

        // Blocking chain — DBA signal
        if (topBlocker.isPresent() && topBlocker.get().getValue() >= 3) {
            String blockerPid = topBlocker.get().getKey();
            long blockedCount = topBlocker.get().getValue();
            Optional<LockContention> blockerInfo = recent.stream()
                .filter(lc -> blockerPid.equals(lc.getBlockingPid()))
                .findFirst();

            String query = blockerInfo.map(LockContention::getBlockingQuery)
                .map(q -> q.length() > 100 ? q.substring(0, 100) + "..." : q)
                .orElse("unknown query");

            insights.add(DigestInsight.builder()
                .category(InsightCategory.LOCK_CONCURRENCY)
                .headline(String.format("Blocking chain: PID %s blocking %d sessions", blockerPid, blockedCount))
                .description("Blocker query: " + query)
                .severity(80)
                .timestamp(blockerInfo.map(LockContention::getDetectedAt).orElse(LocalDateTime.now()))
                .actionable(true)
                .suggestedAction("Review and potentially terminate session " + blockerPid)
                .sourceId(blockerInfo.map(LockContention::getId).orElse(null))
                .sourceType("LockContention")
                .signatureKey(buildSignatureKey(InsightCategory.LOCK_CONCURRENCY,
                    "LockContention", "chain:" + blockerPid))
                .connectionId(connectionId)
                .contextData(Map.of(
                    "blockerPid", blockerPid,
                    "blockedSessionCount", blockedCount
                ))
                .build());
        }

        // High-severity summary if no criticals
        if (criticalCount == 0 && highCount > 0) {
            LockContention top = recent.stream()
                .filter(lc -> lc.getSeverity() == LockContention.Severity.HIGH)
                .findFirst()
                .orElse(recent.get(0));

            insights.add(DigestInsight.builder()
                .category(InsightCategory.LOCK_CONCURRENCY)
                .headline(String.format("%d lock wait%s in digest window",
                    highCount, highCount != 1 ? "s" : ""))
                .description(buildLockDescription(top))
                .severity(65)
                .timestamp(top.getDetectedAt())
                .actionable(true)
                .suggestedAction("Review lock patterns in Performance tab")
                .sourceId(top.getId())
                .sourceType("LockContention")
                .signatureKey(buildSignatureKey(InsightCategory.LOCK_CONCURRENCY,
                    "LockContention", "high:" + highCount))
                .connectionId(connectionId)
                .build());
        }

        return insights;
    }

    private String buildLockDescription(LockContention lc) {
        StringBuilder sb = new StringBuilder();
        if (lc.getBlockingUser() != null) {
            sb.append("Blocker: ").append(lc.getBlockingUser());
        }
        if (lc.getBlockedUser() != null) {
            if (sb.length() > 0) sb.append(" → ");
            sb.append("Blocked: ").append(lc.getBlockedUser());
        }
        if (lc.getLockType() != null) {
            if (sb.length() > 0) sb.append(". ");
            sb.append("Lock: ").append(lc.getLockType());
            if (lc.getLockMode() != null) {
                sb.append(" (").append(lc.getLockMode()).append(")");
            }
        }
        if (lc.getTableName() != null) {
            if (sb.length() > 0) sb.append(" on ");
            sb.append(lc.getTableName());
        }
        return sb.toString();
    }

    private InsightCategory mapAlertTypeToCategory(BrainV2Alert.AlertType alertType) {
        return switch (alertType) {
            case WORKLOAD_CHANGE, SIMILAR_WORKLOAD_FOUND, LEARNING_MILESTONE -> InsightCategory.BRAIN_INTELLIGENCE;
            case CONFIG_DRIFT, EXPERIMENT_FAILED, EXPERIMENT_SUCCESS, MODEL_RETRAIN_NEEDED -> InsightCategory.CONFIG_TUNING;
            case CARDINALITY_DEGRADATION, PLAN_REGRESSION -> InsightCategory.QUERY_PERFORMANCE;
        };
    }

    private int mapAlertSeverity(BrainV2Alert.Severity severity) {
        return switch (severity) {
            case CRITICAL -> 95;
            case HIGH -> 75;
            case WARNING -> 50;
            case INFO -> 25;
        };
    }

    private String buildSignatureKey(InsightCategory category, String sourceType, String keyData) {
        return String.format("%s:%s:%s", category.name(), sourceType, keyData);
    }

    private Set<String> getLastDigestSignatures(String connectionId, String username) {
        Optional<SlackDigestLog> lastDigest = digestLogRepository
            .findTopByConnectionIdAndRecipientUsernameOrderBySentAtDesc(connectionId, username);

        if (lastDigest.isEmpty()) {
            return Set.of();
        }

        String content = lastDigest.get().getContent();
        if (content == null || content.isBlank()) {
            return Set.of();
        }

        Set<String> signatures = new HashSet<>();
        int idx = 0;
        while ((idx = content.indexOf("sig:", idx)) >= 0) {
            int end = content.indexOf("]", idx);
            if (end > idx) {
                signatures.add(content.substring(idx + 4, end));
            }
            idx = end > idx ? end : idx + 1;
        }

        return signatures;
    }

    private boolean hasSignificantChange(DigestInsight insight) {
        if (insight.getSeverity() >= 80) return true;
        if (insight.getTimestamp() != null &&
            insight.getTimestamp().isAfter(LocalDateTime.now().minusHours(6))) {
            return true;
        }
        return false;
    }

    private List<String> generateExecutiveSummary(List<DigestInsight> insights) {
        List<String> bullets = new ArrayList<>();

        long criticalCount = insights.stream().filter(i -> i.getSeverity() >= 90).count();
        if (criticalCount > 0) {
            bullets.add(String.format("⚠️ %d critical issue%s require attention",
                criticalCount, criticalCount != 1 ? "s" : ""));
        }

        Optional<DigestInsight> topRegression = insights.stream()
            .filter(i -> i.getCategory() == InsightCategory.QUERY_PERFORMANCE)
            .findFirst();
        if (topRegression.isPresent()) {
            bullets.add("📉 " + topRegression.get().getHeadline());
        }

        Optional<DigestInsight> costInsight = insights.stream()
            .filter(i -> i.getCategory() == InsightCategory.COST_CAPACITY)
            .findFirst();
        Optional<DigestInsight> growthInsight = insights.stream()
            .filter(i -> i.getCategory() == InsightCategory.GROWTH_ANOMALIES)
            .findFirst();

        if (costInsight.isPresent()) {
            bullets.add("💰 " + costInsight.get().getHeadline());
        } else if (growthInsight.isPresent()) {
            bullets.add("📈 " + growthInsight.get().getHeadline());
        }

        return bullets.stream().limit(3).toList();
    }

    private String generateDecisionAsk(List<DigestInsight> insights) {
        Optional<DigestInsight> topActionable = insights.stream()
            .filter(DigestInsight::isActionable)
            .filter(i -> i.getSeverity() >= 70)
            .findFirst();

        if (topActionable.isPresent()) {
            DigestInsight insight = topActionable.get();
            return insight.getSuggestedAction() != null
                ? insight.getSuggestedAction()
                : "Review: " + insight.getHeadline();
        }

        return null;
    }
}
