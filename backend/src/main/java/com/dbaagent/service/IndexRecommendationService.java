package com.dbaagent.service;

import com.dbaagent.model.AnalysisHistory;
import com.dbaagent.model.ColumnAntiPattern;
import com.dbaagent.model.CompositeIndexRecommendation;
import com.dbaagent.model.ColumnValueCache;
import com.dbaagent.model.ConnectionRequest;
import com.dbaagent.model.ExplainPlanNode;
import com.dbaagent.model.IndexRecommendationEntity;
import com.dbaagent.model.IndexRecommendationEvidence;
import com.dbaagent.model.InferredTableRelationship;
import com.dbaagent.model.KeyColumnAnalysis;
import com.dbaagent.model.SlowQuery;
import com.dbaagent.model.SlowQueryHistory;
import com.dbaagent.provider.DatabaseProviderRegistry;
import com.dbaagent.repository.AnalysisHistoryRepository;
import com.dbaagent.repository.ColumnAntiPatternRepository;
import com.dbaagent.repository.ColumnValueCacheRepository;
import com.dbaagent.repository.CompositeIndexRecommendationRepository;
import com.dbaagent.repository.IndexRecommendationEvidenceRepository;
import com.dbaagent.repository.IndexRecommendationRepository;
import com.dbaagent.repository.InferredTableRelationshipRepository;
import com.dbaagent.repository.KeyColumnAnalysisRepository;
import com.dbaagent.repository.SlowQueryHistoryRepository;
import com.dbaagent.service.index.ColumnRole;
import com.dbaagent.service.index.CompositeIndexPlanner;
import com.dbaagent.service.index.ExplainPlanEvidenceCollector;
import com.dbaagent.service.index.HypotheticalCostEstimator;
import com.dbaagent.service.index.JoinEdge;
import com.dbaagent.service.index.QueryEvidence;
import com.dbaagent.service.index.QueryEvidenceExtractor;
import com.dbaagent.service.index.TableColumnRef;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Service for generating and managing index recommendations
 * Analyzes query patterns from EXPLAIN plans and slow queries
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IndexRecommendationService {

    private final IndexRecommendationRepository recommendationRepository;
    private final AnalysisHistoryRepository analysisHistoryRepository;
    private final SlowQueryHistoryRepository slowQueryHistoryRepository;
    private final ObjectMapper objectMapper;
    private final QueryExecutorService queryExecutorService;
    private final CredentialService credentialService;
    private final ConnectionService connectionService;
    private final DatabaseProviderRegistry providerRegistry;
    private final PerformanceMonitoringService performanceMonitoringService;
    private final SchemaIntrospectionService schemaIntrospectionService;

    // V2 (DBA-grade) collaborators
    private final QueryEvidenceExtractor evidenceExtractor;
    private final CompositeIndexPlanner compositeIndexPlanner;
    private final ExplainPlanEvidenceCollector explainCollector;
    private final IndexRecommendationEvidenceRepository evidenceRepository;
    private final InferredTableRelationshipRepository inferredTableRelationshipRepository;
    private final CompositeIndexRecommendationRepository compositeIndexRecommendationRepository;
    private final ColumnAntiPatternRepository columnAntiPatternRepository;
    private final KeyColumnAnalysisRepository keyColumnAnalysisRepository;
    private final ColumnValueCacheRepository columnValueCacheRepository;
    /** Optional — Spring will inject {@code null} when no impl is registered (HypoPG, V2 PR). */
    private final java.util.Optional<HypotheticalCostEstimator> hypotheticalCostEstimator;

    private static final int MAX_RECOMMENDATIONS = 50;
    private static final int RECURRENCE_PROMOTION_THRESHOLD = 3;
    public static final int DEFAULT_TOP_LIMIT = 5;
    public static final int MAX_TOP_LIMIT = 50;

    @Value("${analysis.history.max-batch:1000}")
    private int analysisHistoryBatch;

    @Value("${slow-query.history.max-batch:1000}")
    private int slowQueryHistoryBatch;

    /**
     * How many days of slow-query and EXPLAIN history feed each refresh cycle.
     * The scheduler runs every few hours so the lookback × refresh cadence
     * gives the recurrence counter weeks of evidence to draw on.
     */
    @Value("${index-recommendations.lookback-days:30}")
    private int analysisLookbackDays;

    /**
     * PENDING rows not re-observed within this window are dropped at the end of
     * a refresh cycle so the accumulator follows workload drift.
     */
    @Value("${index-recommendations.staleness-days:14}")
    private int stalenessDays;

    /**
     * Run one DBA-grade accumulation cycle for a connection.
     *
     * Pipeline (workload-weighted, AST-based, multi-source):
     *   1. Pull the last {@code analysisLookbackDays} of slow-query history.
     *      For each query, walk the JSQLParser AST and accumulate role-tagged
     *      column refs into a {@code (table, role-tagged column set)} group,
     *      weighted by {@code calls × mean_exec_time}. This is the pganalyze /
     *      Microsoft-DTA "total time" ROI metric.
     *   2. Hand the workload-weighted groups to {@link CompositeIndexPlanner}
     *      which applies industry-standard column-ordering rules (equality
     *      before range, selectivity descending, ORDER BY suffix when prefix
     *      is fully equality, cap at 3 columns).
     *   3. Walk parsed EXPLAIN plan trees ({@link ExplainPlanEvidenceCollector})
     *      for Seq Scan / Full Table Scan / expensive Nested Loop / filesort
     *      findings — replaces the dead regex matcher.
     *   4. Wire in the orphaned signals: {@link InferredTableRelationship},
     *      {@link CompositeIndexRecommendation}, {@link ColumnAntiPattern}.
     *   5. Schema walk (unchanged — proactive missing-index detection).
     *   6. Unused-index probe with {@code pg_stat_database.stats_reset} guard.
     *   7. Partial-index candidates from skew + {@link ColumnValueCache}.
     *   8. Redundant-index DROP candidates from {@code getDuplicateIndexes()}.
     *   9. Apply write-cost penalty from {@code pg_stat_user_tables.n_tup_*}.
     *  10. Merge each candidate into the ledger; persist top-K evidence rows.
     *
     * Ranking is delegated to the database via {@link IndexRecommendationRepository#findTopPending},
     * which orders by priority then net benefit (workload − write cost).
     */
    @Transactional
    public List<IndexRecommendationEntity> generateRecommendations(String connectionId) {
        log.info("Generating index recommendations for connection {} (lookback={}d, workload-weighted)",
            connectionId, analysisLookbackDays);

        LocalDateTime since = LocalDateTime.now().minusDays(analysisLookbackDays);

        // Existing-index coverage map: drives the redundancy guard in
        // mergeRecurrence so we never suggest creating an index that's
        // already present (including primary keys, unique constraints, and
        // leading-column prefixes of composite indexes). Loaded ONCE per
        // refresh cycle and reused — cheap probe but not free, and the
        // map's contents are stable for the duration of a cycle.
        Map<String, List<List<String>>> existingIndexCoverage =
            performanceMonitoringService.getIndexCoverage(connectionId);

        Map<String, RecommendationCandidate> candidates = new HashMap<>();

        // 1+2. Workload-weighted slow-query analysis (AST + composite planning).
        analyzeSlowQueriesWorkloadWeighted(connectionId, since, candidates);

        // 3. EXPLAIN plan evidence (real plan-tree walk, not regex).
        List<AnalysisHistory> explainHistory = analysisHistoryRepository
            .findByConnectionIdSince(connectionId, since, PageRequest.of(0, analysisHistoryBatch));
        analyzeExplainPlans(explainHistory, candidates);

        // 4a. Inferred JOIN patterns — already frequency-weighted by JoinRelationshipInferenceService.
        pullInferredJoinPatterns(connectionId, candidates);
        // 4b. Composite candidates persisted by KeyColumnAnalysisService.
        pullCompositeRecommendations(connectionId, candidates);
        // 4c. Anti-pattern columns with persisted totalExecutionTimeMs.
        pullColumnAntiPatterns(connectionId, candidates);

        // 5. Proactive schema walk (unchanged — solid heuristic for greenfield connections).
        analyzeDatabaseSchema(connectionId, candidates);

        // 6. Unused indexes with reset-time guard.
        analyzeUnusedIndexes(connectionId, candidates);

        // 7. Partial-index hints for skewed columns.
        analyzePartialIndexCandidates(connectionId, candidates);

        // 8. Redundant (prefix-duplicate) DROP candidates.
        analyzeRedundantIndexes(connectionId, candidates);

        // 9. Write-cost penalty for every candidate.
        applyWriteCostPenalty(connectionId, candidates);

        // 10. Merge into the persistent ledger + persist evidence rows.
        //     CREATE_INDEX candidates that are already covered by an existing
        //     index (PK, unique, regular, or leading-column prefix of a
        //     composite) are skipped here — the centralized chokepoint so no
        //     emitter path can leak a redundant suggestion past it.
        int skippedRedundant = 0;
        List<IndexRecommendationEntity> mergedThisCycle = new ArrayList<>();
        int newInserts = 0;
        for (RecommendationCandidate candidate : candidates.values()) {
            if (isAlreadyCoveredByExistingIndex(candidate, existingIndexCoverage)) {
                skippedRedundant++;
                continue;
            }
            IndexRecommendationEntity merged = mergeRecurrence(connectionId, candidate);
            if (merged != null) {
                persistEvidence(merged, candidate);
                mergedThisCycle.add(merged);
                if (merged.getOccurrenceCount() != null && merged.getOccurrenceCount() == 1) {
                    newInserts++;
                    if (newInserts >= MAX_RECOMMENDATIONS) {
                        log.warn("Hit MAX_RECOMMENDATIONS ({}) new inserts cap for connection {}",
                            MAX_RECOMMENDATIONS, connectionId);
                        break;
                    }
                }
            }
        }

        // 11. HypoPG validation (optional, Postgres-only): for each freshly-
        //     merged CREATE_INDEX recommendation, run the contributing query
        //     through the planner with and without a hypothetical version of
        //     the index. Stamps the cost-delta onto the row so the API can
        //     return real "planner agrees this helps" evidence — separate
        //     from the heuristic workload score.
        runHypoPgValidation(connectionId, mergedThisCycle);

        log.info("Index recommendation cycle for {}: {} candidate(s) merged ({} new, {} skipped as redundant)",
            connectionId, mergedThisCycle.size(), newInserts, skippedRedundant);
        return mergedThisCycle;
    }

    /**
     * Whether a candidate's (table, columns) is already served by an existing
     * index in the supplied coverage map.
     *
     * Coverage rules:
     *   - DROP_INDEX is never filtered out — we're recommending removing an
     *     index, not creating one.
     *   - A regular CREATE_INDEX is covered if SOME existing index's leading
     *     columns equal the candidate's columns or extend past them. An index
     *     on {@code (a, b, c)} covers candidates for {@code (a)}, {@code (a, b)},
     *     and {@code (a, b, c)} — that's how B-tree leading-prefix lookups work
     *     in both Postgres and MySQL.
     *   - Column order matters. An index on {@code (a, b)} does NOT cover a
     *     candidate for {@code (b, a)} — different B-tree.
     *   - Partial-index candidates go through the SAME coverage check. The
     *     earlier blanket "partials always allowed" exemption let candidates
     *     like {@code idx_code_scan_source_id_partial} through even though
     *     {@code id} was the primary key; a PK already serves every
     *     {@code id = ?} lookup in O(log n), so a partial on {@code (id)} is
     *     never useful. A genuinely useful partial — one on a column with no
     *     existing index — still passes because the coverage map won't list it.
     *
     * When the coverage map is empty (probe failed or no schema access), the
     * function returns false so we don't drop everything on a transient probe
     * failure. The per-path checks (FK / schema walk) are the safety net for
     * that case.
     */
    private boolean isAlreadyCoveredByExistingIndex(
        RecommendationCandidate candidate,
        Map<String, List<List<String>>> coverage
    ) {
        if (candidate == null || candidate.tableName == null) return false;
        if (candidate.kind != IndexRecommendationEntity.Kind.CREATE_INDEX) return false;
        if (coverage == null || coverage.isEmpty()) return false;

        List<List<String>> tableIndexes = coverage.get(candidate.tableName.toLowerCase(Locale.ROOT));
        if (tableIndexes == null || tableIndexes.isEmpty()) return false;

        List<String> candidateCols = new ArrayList<>(candidate.columns);
        if (candidateCols.isEmpty()) return false;

        for (List<String> existing : tableIndexes) {
            if (isLeadingPrefixOf(candidateCols, existing)) {
                log.debug("Skipping redundant CREATE_INDEX on {}({}): already covered by existing index {}",
                    candidate.tableName, String.join(",", candidateCols), existing);
                return true;
            }
        }
        return false;
    }

    /**
     * True iff {@code candidateColumns} is a leading prefix of {@code existingColumns}
     * (case-insensitive, order-sensitive). Empty candidate → false; a candidate
     * longer than the existing index → false.
     */
    private boolean isLeadingPrefixOf(List<String> candidateColumns, List<String> existingColumns) {
        if (candidateColumns.isEmpty() || existingColumns == null || existingColumns.isEmpty()) return false;
        if (candidateColumns.size() > existingColumns.size()) return false;
        for (int i = 0; i < candidateColumns.size(); i++) {
            String a = candidateColumns.get(i);
            String b = existingColumns.get(i);
            if (a == null || b == null) return false;
            if (!a.equalsIgnoreCase(b)) return false;
        }
        return true;
    }

    /**
     * Stamp each CREATE_INDEX recommendation with its HypoPG planner cost-delta.
     * Best-effort — when the extension isn't installed or no usable sample
     * queries exist, leaves the columns null and the heuristic ranking unchanged.
     */
    private void runHypoPgValidation(String connectionId, List<IndexRecommendationEntity> recs) {
        if (hypotheticalCostEstimator == null || hypotheticalCostEstimator.isEmpty()) return;
        HypotheticalCostEstimator est = hypotheticalCostEstimator.get();
        for (IndexRecommendationEntity rec : recs) {
            if (rec.getKind() != IndexRecommendationEntity.Kind.CREATE_INDEX) continue;
            List<IndexRecommendationEvidence> evRows = evidenceRepository
                .findByRecommendationIdOrderByTotalExecTimeMsDesc(rec.getId(), PageRequest.of(0, 3));
            List<String> samples = evRows.stream()
                .map(IndexRecommendationEvidence::getExampleSql)
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.toList());
            if (samples.isEmpty()) continue;

            HypotheticalCostEstimator.CandidateIndex ci = new HypotheticalCostEstimator.CandidateIndex(
                rec.getTableName(),
                Arrays.asList(rec.getColumnNames().split(",")),
                rec.getCreateStatement() != null && rec.getCreateStatement().contains(" WHERE "),
                extractPartialPredicate(rec.getCreateStatement())
            );

            Optional<HypotheticalCostEstimator.CostDelta> delta = est.estimate(connectionId, ci, samples);
            delta.ifPresent(d -> {
                rec.setHypopgBeforeCost(d.beforeCost());
                rec.setHypopgAfterCost(d.afterCost());
                rec.setHypopgReductionPct(d.reductionPct());
                rec.setHypopgEvaluatedAt(LocalDateTime.now());
                recommendationRepository.save(rec);
            });
        }
    }

    /** Extracts the {@code WHERE …} suffix from a partial-index CREATE statement. */
    private String extractPartialPredicate(String ddl) {
        if (ddl == null) return null;
        int i = ddl.toUpperCase(Locale.ROOT).indexOf(" WHERE ");
        if (i < 0) return null;
        String tail = ddl.substring(i + " WHERE ".length()).trim();
        if (tail.endsWith(";")) tail = tail.substring(0, tail.length() - 1).trim();
        return tail;
    }

    /**
     * Run one refresh cycle and age out PENDING candidates that haven't been
     * re-observed in `stalenessDays`. Applied and dismissed rows are preserved.
     *
     * Unlike the pre-recurrence implementation, this does NOT wipe PENDING up
     * front — that would erase the cross-cycle recurrence signal the top-N
     * ranking depends on.
     */
    @Transactional
    public List<IndexRecommendationEntity> refreshRecommendations(String connectionId) {
        log.info("Refreshing index recommendations for connection {} (staleness={}d)",
            connectionId, stalenessDays);
        List<IndexRecommendationEntity> merged = generateRecommendations(connectionId);
        int aged = recommendationRepository.deleteStalePending(
            connectionId,
            LocalDateTime.now().minusDays(stalenessDays)
        );
        if (aged > 0) {
            log.info("Aged out {} stale PENDING recommendation(s) for {}", aged, connectionId);
        }
        return merged;
    }

    /**
     * Pre-computed top-N pending recommendations for the CLI/MCP surface.
     * Ranking: priority → net benefit (workload − write cost) → recurrence → recency.
     */
    @Transactional(readOnly = true)
    public List<IndexRecommendationEntity> getTopRecommendations(String connectionId, int limit) {
        int bounded = Math.max(1, Math.min(MAX_TOP_LIMIT, limit));
        return recommendationRepository.findTopPending(
            connectionId,
            PageRequest.of(0, bounded)
        );
    }

    /**
     * Top-N + contributing evidence — the "why" payload for the API and the
     * {@code get_index_recommendations} MCP tool. Each entry pairs the
     * recommendation with up to {@link #TOP_EVIDENCE_K} most expensive
     * queries that fed its workload score.
     */
    @Transactional
    public List<TopRecommendationWithEvidence> getTopRecommendationsWithEvidence(String connectionId, int limit) {
        // Read more than `limit` so we still have enough left after filtering
        // out recommendations whose target index already exists on the DB
        // (user created it via raw DDL since the last reconciliation pass).
        // 3× headroom — keeps the query cheap while protecting against the
        // common case where 2-3 of the top 10 were applied out of band.
        int bounded = Math.max(1, Math.min(MAX_TOP_LIMIT, limit));
        int overscan = Math.max(bounded, Math.min(MAX_TOP_LIMIT, bounded * 3));
        List<IndexRecommendationEntity> recs = getTopRecommendations(connectionId, overscan);
        if (recs.isEmpty()) return List.of();

        recs = autoMarkAppliedAgainstLiveSchema(connectionId, recs);
        if (recs.isEmpty()) return List.of();
        if (recs.size() > bounded) recs = recs.subList(0, bounded);

        List<TopRecommendationWithEvidence> out = new ArrayList<>(recs.size());
        for (IndexRecommendationEntity rec : recs) {
            List<IndexRecommendationEvidence> evidence = evidenceRepository
                .findByRecommendationIdOrderByTotalExecTimeMsDesc(rec.getId(), PageRequest.of(0, TOP_EVIDENCE_K));
            out.add(new TopRecommendationWithEvidence(rec, evidence));
        }
        return out;
    }

    /**
     * For each PENDING recommendation, ask the target DB whether an index
     * matching {@code (tableName, columnNames)} already physically exists.
     * If yes — the user created it via raw DDL outside DeepSQL and the
     * recommendation is moot — transition it to APPLIED in the DB so it
     * stops surfacing in digests, the CLI, and the MCP tools.
     *
     * <p>Match criteria: same table, and the recommendation's columns are a
     * <i>prefix</i> (in order) of some existing index's columns. The prefix
     * rule means a 3-column index satisfies a 2-column recommendation that
     * leads with the same prefix — composite indexes serve their prefixes,
     * so the smaller recommendation is genuinely already covered.
     *
     * <p>Scope: only {@link IndexRecommendationEntity.Kind#CREATE_INDEX}
     * candidates. DROP candidates exist for the opposite reason ("this
     * index isn't earning its keep") and shouldn't be auto-applied just
     * because the index exists.
     *
     * <p>Cost: one {@code SchemaIntrospectionService.getTableDetails} call
     * per distinct table among the candidates — typically &le;5 with the
     * 3× overscan above. Cached locally for this method call so repeated
     * candidates on the same table don't re-query.
     */
    private List<IndexRecommendationEntity> autoMarkAppliedAgainstLiveSchema(
        String connectionId,
        List<IndexRecommendationEntity> recs
    ) {
        Map<String, List<com.dbaagent.model.IndexDetail>> indexesByTable = new java.util.HashMap<>();
        List<IndexRecommendationEntity> kept = new ArrayList<>(recs.size());
        int autoApplied = 0;
        for (IndexRecommendationEntity rec : recs) {
            if (rec.getKind() != IndexRecommendationEntity.Kind.CREATE_INDEX
                || rec.getTableName() == null
                || rec.getColumnNames() == null
                || rec.getColumnNames().isBlank()) {
                kept.add(rec);
                continue;
            }
            List<com.dbaagent.model.IndexDetail> existing;
            try {
                existing = indexesByTable.computeIfAbsent(
                    rec.getTableName(),
                    t -> {
                        var details = schemaIntrospectionService.getTableDetails(connectionId, t);
                        return details != null && details.getIndexes() != null
                            ? details.getIndexes()
                            : List.of();
                    }
                );
            } catch (Exception e) {
                // Live-schema check unavailable (target DB unreachable,
                // permission denied, etc.) — fall through to surfacing the
                // recommendation. Better to occasionally show a stale
                // suggestion than to silently hide active ones.
                log.debug("Index reconciliation skipped for {}.{}: {}",
                    connectionId, rec.getTableName(), e.getMessage());
                kept.add(rec);
                continue;
            }
            String[] wanted = rec.getColumnNames().split(",");
            if (existsAsPrefix(existing, wanted)) {
                rec.markAsApplied();
                recommendationRepository.save(rec);
                autoApplied++;
                log.debug("Auto-marked APPLIED: {}({}) — matching index detected on {}",
                    rec.getTableName(), rec.getColumnNames(), connectionId);
                continue;
            }
            kept.add(rec);
        }
        if (autoApplied > 0) {
            log.info("Index reconciliation for connection {}: {} recommendation(s) "
                + "auto-marked APPLIED against live schema (matching index existed)",
                connectionId, autoApplied);
        }
        return kept;
    }

    /** Does any index on {@code existing} have {@code wanted} as a leading prefix? */
    private static boolean existsAsPrefix(List<com.dbaagent.model.IndexDetail> existing, String[] wanted) {
        if (wanted == null || wanted.length == 0) return false;
        for (com.dbaagent.model.IndexDetail idx : existing) {
            List<String> idxCols = idx.getColumns();
            if (idxCols == null || idxCols.size() < wanted.length) continue;
            boolean match = true;
            for (int i = 0; i < wanted.length; i++) {
                if (!normalizeCol(wanted[i]).equals(normalizeCol(idxCols.get(i)))) {
                    match = false;
                    break;
                }
            }
            if (match) return true;
        }
        return false;
    }

    /** Lowercased, trimmed, backtick/quote-stripped column name for matching. */
    private static String normalizeCol(String s) {
        if (s == null) return "";
        return s.trim()
            .replace("`", "")
            .replace("\"", "")
            .toLowerCase(java.util.Locale.ROOT);
    }


    /** Public-API container for {@link #getTopRecommendationsWithEvidence}. */
    public record TopRecommendationWithEvidence(
        IndexRecommendationEntity recommendation,
        List<IndexRecommendationEvidence> topEvidence
    ) {}

    /**
     * Walk parsed EXPLAIN plan trees in {@link AnalysisHistory} and emit
     * index-worthy findings via {@link ExplainPlanEvidenceCollector}.
     *
     * Replaces the legacy regex {@code "table scan on (\\w+)"} which never
     * matched real plan output (Postgres emits {@code Seq Scan}, MySQL emits
     * {@code Full Table Scan}). The collector walks the parsed
     * {@link ExplainPlanNode} tree the providers already produce.
     */
    private void analyzeExplainPlans(List<AnalysisHistory> history, Map<String, RecommendationCandidate> candidates) {
        for (AnalysisHistory analysis : history) {
            if (analysis.getPerformanceScore() == null || analysis.getPerformanceScore() >= 70) continue;
            try {
                JsonNode planData = objectMapper.readTree(analysis.getAnalysisData());
                ExplainPlanNode root = extractPlanTree(planData);
                if (root == null) continue;

                List<ExplainPlanEvidenceCollector.Finding> findings = explainCollector.collect(root);
                for (ExplainPlanEvidenceCollector.Finding f : findings) {
                    // EXPLAIN gives us the *table* that has a problem; deriving the
                    // exact columns needs the SQL text. Re-extract via JSQLParser
                    // for the same statement to know which WHERE/ORDER BY columns
                    // to suggest.
                    QueryEvidence ev = evidenceExtractor.extract(analysis.getQuery());
                    Set<TableColumnRef> tableRefs = ev.refsForTable(f.tableName());
                    if (tableRefs.isEmpty()) continue;

                    String columnsKey = tableRefs.stream()
                        .map(TableColumnRef::getColumn)
                        .sorted()
                        .collect(Collectors.joining(","));
                    if (columnsKey.isEmpty()) continue;

                    String key = candidateKey(f.tableName(), columnsKey, IndexRecommendationEntity.Kind.CREATE_INDEX);
                    RecommendationCandidate c = candidates.computeIfAbsent(key, k -> new RecommendationCandidate());
                    c.tableName = f.tableName();
                    c.columns.addAll(tableRefs.stream().map(TableColumnRef::getColumn).collect(Collectors.toCollection(LinkedHashSet::new)));
                    c.workloadScoreMs += Math.max((long) f.actualTotalTimeMs(), f.actualRows() / 100L);
                    c.affectedQueries++;
                    c.priority = upgrade(c.priority, IndexRecommendationEntity.Priority.HIGH);
                    c.reason = f.description() + " — index on candidate columns can eliminate the scan";
                    c.evidence.put(ev.getQueryFingerprint(), new EvidenceRow(
                        ev.getQueryFingerprint(),
                        analysis.getQuery(),
                        1L,
                        f.actualTotalTimeMs(),
                        f.actualTotalTimeMs(),
                        null,
                        roleLabelForFinding(f)
                    ));
                }
            } catch (Exception e) {
                log.warn("Failed to parse plan data for analysis {}: {}", analysis.getId(), e.getMessage());
            }
        }
    }

    /**
     * Workload-weighted slow-query analysis.
     *
     * For every {@link SlowQuery} captured in the lookback window:
     *   - Parse with JSQLParser (no more regex column extraction).
     *   - For each touched (table, role, column), add
     *     {@code calls × mean_exec_time_ms} to a {@link CompositeIndexPlanner}-
     *     compatible group keyed by the role-tagged column set.
     *   - Stash a top-K evidence trail per group so the API can show *why*.
     *
     * Then ask {@link CompositeIndexPlanner} to apply column-ordering rules and
     * emit ordered composite candidates per group. Single-column hot paths
     * with no co-occurrence still surface as 1-column candidates from the
     * planner output.
     */
    private void analyzeSlowQueriesWorkloadWeighted(
        String connectionId,
        LocalDateTime since,
        Map<String, RecommendationCandidate> candidates
    ) {
        List<SlowQueryHistory> slowQueryHistory = slowQueryHistoryRepository
            .findByConnectionIdSince(connectionId, since, PageRequest.of(0, slowQueryHistoryBatch));
        if (slowQueryHistory.isEmpty()) return;

        // Aggregate workload signal per (table, role-tagged column set).
        // Key shape: table + "|" + sorted-roleEq-cols + "|" + sorted-roleRange-cols + "|" + sorted-orderBy-cols
        // — distinguishes (orders WHERE customer_id=?) from (orders WHERE status=?).
        Map<String, CompositeIndexPlanner.CandidateGroup> groups = new HashMap<>();
        Map<String, Map<String, EvidenceRow>> evidenceByGroup = new HashMap<>();

        for (SlowQueryHistory slowQueryRow : slowQueryHistory) {
            List<SlowQuery> queries = parseSlowQueries(slowQueryRow);
            for (SlowQuery sq : queries) {
                if (sq.getQueryText() == null) continue;
                long calls = sq.getCallCount() == null ? 0L : sq.getCallCount();
                double meanMs = sq.getAvgExecutionTimeMs() == null ? 0.0 : sq.getAvgExecutionTimeMs();
                double totalMs = sq.getTotalExecutionTimeMs() == null ? calls * meanMs : sq.getTotalExecutionTimeMs();
                if (calls <= 0 && totalMs <= 0) continue;

                QueryEvidence ev = evidenceExtractor.extract(sq.getQueryText());
                if (ev.isParseFallback()) {
                    // Parse failed; no reliable column-level data. Skip this query.
                    continue;
                }

                long thisQueryScore = (long) totalMs;
                if (thisQueryScore <= 0) thisQueryScore = (long) (Math.max(meanMs, 1.0) * Math.max(calls, 1));

                // Bucket every touched table separately so a JOIN of 3 tables
                // contributes to 3 candidate groups.
                Set<String> touchedTables = new LinkedHashSet<>(ev.getTables());
                if (touchedTables.isEmpty() && !ev.allPredicateRefs().isEmpty()) {
                    ev.allPredicateRefs().forEach(r -> touchedTables.add(r.getTable()));
                }

                for (String table : touchedTables) {
                    if (table == null) continue;
                    Set<TableColumnRef> eq = filterRefs(ev.getWhereEquality(), table);
                    Set<TableColumnRef> in = filterRefs(ev.getWhereIn(), table);
                    Set<TableColumnRef> like = filterRefs(ev.getWhereLikePrefix(), table);
                    Set<TableColumnRef> range = filterRefs(ev.getWhereRange(), table);
                    Set<TableColumnRef> order = filterRefs(ev.getOrderBy(), table);
                    Set<TableColumnRef> groupBy = filterRefs(ev.getGroupBy(), table);
                    Set<TableColumnRef> joinSide = ev.getJoinEdges() == null ? Set.of() :
                        ev.getJoinEdges().stream()
                            .flatMap(j -> java.util.stream.Stream.of(j.getLeft(), j.getRight()))
                            .filter(r -> r != null && r.isResolved() && table.equals(r.getTable()))
                            .collect(Collectors.toCollection(LinkedHashSet::new));

                    // Merge IN + equality (both anchor a B-tree prefix the same way),
                    // and merge JOIN-side columns into equality (they're equi-join columns).
                    Set<TableColumnRef> equalityish = new LinkedHashSet<>(eq);
                    equalityish.addAll(in);
                    equalityish.addAll(like);
                    equalityish.addAll(joinSide);

                    if (equalityish.isEmpty() && range.isEmpty() && order.isEmpty() && groupBy.isEmpty()) {
                        continue; // nothing on this table to index
                    }

                    String groupKey = table + "|"
                        + columnsKey(equalityish) + "|"
                        + columnsKey(range) + "|"
                        + columnsKey(order) + "|"
                        + columnsKey(groupBy);

                    CompositeIndexPlanner.CandidateGroup g =
                        groups.computeIfAbsent(groupKey, k -> new CompositeIndexPlanner.CandidateGroup(table));
                    g.equalityColumns.addAll(equalityish);
                    g.rangeColumns.addAll(range);
                    g.orderByColumns.addAll(order);
                    g.groupByColumns.addAll(groupBy);
                    g.workloadScoreMs += thisQueryScore;
                    g.evidenceCount++;

                    String predominantRole = predominantRole(equalityish, range, order, groupBy, joinSide);
                    EvidenceRow row = new EvidenceRow(
                        ev.getQueryFingerprint(),
                        truncate(sq.getQueryText(), 2000),
                        calls,
                        meanMs,
                        totalMs,
                        sq.getRowsExamined(),
                        predominantRole
                    );
                    evidenceByGroup
                        .computeIfAbsent(groupKey, k -> new LinkedHashMap<>())
                        .merge(ev.getQueryFingerprint(), row, EvidenceRow::merge);
                }
            }
        }

        // Hand the aggregated groups to the planner; emit one CREATE_INDEX
        // candidate per ordered plan.
        List<CompositeIndexPlanner.Plan> plans = compositeIndexPlanner.plan(connectionId, new ArrayList<>(groups.values()));
        for (CompositeIndexPlanner.Plan plan : plans) {
            String columnsKey = String.join(",", plan.columnsInOrder());
            String key = candidateKey(plan.table(), columnsKey, IndexRecommendationEntity.Kind.CREATE_INDEX);
            RecommendationCandidate c = candidates.computeIfAbsent(key, k -> new RecommendationCandidate());
            c.tableName = plan.table();
            c.columns.clear();
            c.columns.addAll(plan.columnsInOrder());
            c.workloadScoreMs = Math.max(c.workloadScoreMs, plan.workloadScoreMs());
            c.affectedQueries = plan.evidenceCount();
            c.priority = upgrade(c.priority, plan.workloadScoreMs() > 600_000L
                ? IndexRecommendationEntity.Priority.HIGH
                : IndexRecommendationEntity.Priority.MEDIUM);
            c.reason = plan.reason();
            c.kind = IndexRecommendationEntity.Kind.CREATE_INDEX;

            // Attach the matching evidence rows for this group, capped at TOP_EVIDENCE_K.
            String groupKey = findGroupKeyForPlan(groups, plan);
            if (groupKey != null) {
                Map<String, EvidenceRow> ev = evidenceByGroup.getOrDefault(groupKey, Map.of());
                ev.values().stream()
                    .sorted(Comparator.comparingDouble((EvidenceRow r) -> r.totalExecTimeMs()).reversed())
                    .limit(TOP_EVIDENCE_K)
                    .forEach(r -> c.evidence.put(r.fingerprint(), r));
            }
        }
    }

    private String findGroupKeyForPlan(Map<String, CompositeIndexPlanner.CandidateGroup> groups, CompositeIndexPlanner.Plan plan) {
        // The plan came from one of the groups — match on table + workload-score equality.
        for (Map.Entry<String, CompositeIndexPlanner.CandidateGroup> e : groups.entrySet()) {
            if (plan.table().equalsIgnoreCase(e.getValue().table)
                && e.getValue().workloadScoreMs == plan.workloadScoreMs()) {
                return e.getKey();
            }
        }
        return null;
    }

    /** Filter a set of refs down to those on a specific (lower-cased) table. */
    private Set<TableColumnRef> filterRefs(Set<TableColumnRef> refs, String table) {
        if (refs == null || refs.isEmpty()) return Set.of();
        Set<TableColumnRef> out = new LinkedHashSet<>();
        for (TableColumnRef r : refs) {
            if (r != null && r.isResolved() && table.equals(r.getTable())) out.add(r);
        }
        return out;
    }

    private String columnsKey(Set<TableColumnRef> refs) {
        if (refs == null || refs.isEmpty()) return "";
        return refs.stream().map(TableColumnRef::getColumn).sorted().collect(Collectors.joining(","));
    }

    private String predominantRole(
        Set<TableColumnRef> equalityish,
        Set<TableColumnRef> range,
        Set<TableColumnRef> order,
        Set<TableColumnRef> groupBy,
        Set<TableColumnRef> joinSide
    ) {
        if (!joinSide.isEmpty()) return ColumnRole.JOIN.name();
        if (!equalityish.isEmpty()) return ColumnRole.WHERE_EQ.name();
        if (!range.isEmpty()) return ColumnRole.WHERE_RANGE.name();
        if (!order.isEmpty()) return ColumnRole.ORDER_BY.name();
        if (!groupBy.isEmpty()) return ColumnRole.GROUP_BY.name();
        return ColumnRole.WHERE_EQ.name();
    }

    private String roleLabelForFinding(ExplainPlanEvidenceCollector.Finding f) {
        return switch (f.kind()) {
            case USING_FILESORT -> ColumnRole.ORDER_BY.name();
            case USING_TEMPORARY -> ColumnRole.GROUP_BY.name();
            case EXPENSIVE_NESTED_LOOP -> ColumnRole.JOIN.name();
            default -> ColumnRole.WHERE_EQ.name();
        };
    }

    private IndexRecommendationEntity.Priority upgrade(
        IndexRecommendationEntity.Priority current,
        IndexRecommendationEntity.Priority candidate
    ) {
        // HIGH < MEDIUM < LOW alphabetically; we want HIGH to win.
        if (current == null) return candidate;
        if (current == IndexRecommendationEntity.Priority.HIGH) return current;
        if (candidate == IndexRecommendationEntity.Priority.HIGH) return candidate;
        if (current == IndexRecommendationEntity.Priority.MEDIUM) return current;
        return candidate;
    }

    private List<SlowQuery> parseSlowQueries(SlowQueryHistory row) {
        List<SlowQuery> out = new ArrayList<>();
        if (row == null || row.getAnalysisData() == null) return out;
        try {
            JsonNode root = objectMapper.readTree(row.getAnalysisData());
            // SlowQueryAnalysis serialises the list as "topSlowQueries"; older
            // rows may use "slowQueries". Accept both so we don't silently
            // drop the entire workload signal when the key drifts.
            JsonNode arr = root.has("topSlowQueries") ? root.get("topSlowQueries") : root.get("slowQueries");
            if (arr != null && arr.isArray()) {
                for (JsonNode node : arr) {
                    out.add(objectMapper.treeToValue(node, SlowQuery.class));
                }
            }
        } catch (Exception e) {
            log.debug("Could not parse slow query history row {}: {}", row.getId(), e.getMessage());
        }
        return out;
    }

    private ExplainPlanNode extractPlanTree(JsonNode planData) {
        if (planData == null) return null;
        JsonNode tree = planData.has("planTree") ? planData.get("planTree") : planData;
        try {
            return objectMapper.treeToValue(tree, ExplainPlanNode.class);
        } catch (Exception e) {
            log.debug("Could not deserialize plan tree: {}", e.getMessage());
            return null;
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private String candidateKey(String table, String columns, IndexRecommendationEntity.Kind kind) {
        return table + ":" + kind + ":" + columns;
    }

    /**
     * Merge a fresh candidate into the persistent recommendation set.
     *
     *   - If a PENDING row already exists for the same (table, columns):
     *     bump occurrence_count, refresh last_seen_at, re-evaluate priority,
     *     and refresh the human-readable reason so the latest evidence wins.
     *   - If an APPLIED row exists: the user already created the index;
     *     skip (return null) — we don't want to re-surface a solved problem.
     *   - If a DISMISSED row exists: also skip — the user said no.
     *   - Otherwise insert a new PENDING row with occurrence_count = 1.
     *
     * Returns the persisted entity (or null if intentionally skipped).
     */
    private IndexRecommendationEntity mergeRecurrence(String connectionId, RecommendationCandidate candidate) {
        if (candidate.tableName == null || candidate.columns.isEmpty()) {
            return null;
        }
        String columnNames = String.join(",", candidate.columns);

        List<IndexRecommendationEntity> existing = recommendationRepository
            .findByConnectionIdAndTableNameAndColumnNamesAndKind(
                connectionId, candidate.tableName, columnNames, candidate.kind);

        IndexRecommendationEntity pending = null;
        for (IndexRecommendationEntity rec : existing) {
            switch (rec.getStatus()) {
                case APPLIED:
                case DISMISSED:
                    // User has resolved this candidate. Don't re-surface it; the user can
                    // un-dismiss or re-generate explicitly if the situation changes.
                    return null;
                case PENDING:
                    if (pending == null || (rec.getLastSeenAt() != null && pending.getLastSeenAt() != null
                            && rec.getLastSeenAt().isAfter(pending.getLastSeenAt()))) {
                        pending = rec;
                    }
                    break;
            }
        }

        if (pending != null) {
            pending.recordRecurrence();
            // Promote priority once a candidate has resurfaced repeatedly — the
            // recurrence itself is evidence the workload keeps hitting this gap.
            if (pending.getOccurrenceCount() != null
                && pending.getOccurrenceCount() >= RECURRENCE_PROMOTION_THRESHOLD
                && pending.getPriority() != IndexRecommendationEntity.Priority.HIGH) {
                pending.setPriority(IndexRecommendationEntity.Priority.HIGH);
            }
            // Refresh the surfaced reason with the latest cycle's explanation.
            if (candidate.reason != null) {
                pending.setReason(candidate.reason);
            }
            if (candidate.affectedQueries > 0) {
                pending.setAffectedQueries(
                    (pending.getAffectedQueries() == null ? 0 : pending.getAffectedQueries())
                        + candidate.affectedQueries
                );
            }
            // Workload score is replaced (not incremented) — the candidate
            // carries this cycle's aggregated workload; the database column
            // becomes the authoritative current view.
            pending.setWorkloadScoreMs(candidate.workloadScoreMs);
            pending.setWriteCostScore(candidate.writeCostScore);
            pending.setEvidenceCount(candidate.evidence.size());
            // estimatedImpact is now a cosmetic normalisation; recompute from
            // net benefit so the UI shows something sane (capped 0..100).
            long net = Math.max(0, candidate.workloadScoreMs - candidate.writeCostScore);
            int impact = net == 0 ? Math.min(100, candidate.estimatedImpact)
                : (int) Math.min(100, 20 + Math.log10(1.0 + net) * 10);
            pending.setEstimatedImpact(impact);
            return recommendationRepository.save(pending);
        }

        IndexRecommendationEntity fresh = buildRecommendation(connectionId, candidate);
        LocalDateTime now = LocalDateTime.now();
        fresh.setOccurrenceCount(1);
        fresh.setFirstSeenAt(now);
        fresh.setLastSeenAt(now);
        fresh.setWorkloadScoreMs(candidate.workloadScoreMs);
        fresh.setWriteCostScore(candidate.writeCostScore);
        fresh.setEvidenceCount(candidate.evidence.size());
        return recommendationRepository.save(fresh);
    }

    /**
     * Persist top-K evidence rows for a recommendation. Wipes the existing
     * trail and re-populates each cycle so stale fingerprints don't linger
     * after the workload pivots.
     */
    private void persistEvidence(IndexRecommendationEntity rec, RecommendationCandidate candidate) {
        if (rec == null || candidate.evidence.isEmpty()) return;
        try {
            evidenceRepository.deleteAllByRecommendationId(rec.getId());
            List<EvidenceRow> top = candidate.evidence.values().stream()
                .sorted(Comparator.comparingDouble((EvidenceRow r) -> r.totalExecTimeMs()).reversed())
                .limit(TOP_EVIDENCE_K)
                .collect(Collectors.toList());
            for (EvidenceRow r : top) {
                IndexRecommendationEvidence row = IndexRecommendationEvidence.builder()
                    .recommendationId(rec.getId())
                    .queryFingerprint(r.fingerprint() == null ? "" : r.fingerprint())
                    .exampleSql(r.exampleSql())
                    .calls(r.calls())
                    .meanExecTimeMs(r.meanExecTimeMs())
                    .totalExecTimeMs(r.totalExecTimeMs())
                    .rowsExamined(r.rowsExamined())
                    .role(r.role() == null ? "WHERE_EQ" : r.role())
                    .build();
                evidenceRepository.save(row);
            }
        } catch (Exception e) {
            log.warn("Could not persist evidence for recommendation {}: {}", rec.getId(), e.getMessage());
        }
    }

    /**
     * Build IndexRecommendation from candidate. Handles three DDL shapes:
     *   - CREATE_INDEX with a planner-computed column order
     *   - DROP_INDEX (unused / redundant) using {@code candidate.existingIndexName}
     *   - Partial / redundant overrides via {@code candidate.createStatementOverride}
     */
    private IndexRecommendationEntity buildRecommendation(String connectionId, RecommendationCandidate candidate) {
        String columnNames = String.join(",", candidate.columns);
        String indexName;
        String statement;
        if (candidate.createStatementOverride != null && !candidate.createStatementOverride.isBlank()) {
            statement = candidate.createStatementOverride;
            indexName = candidate.existingIndexName != null
                ? candidate.existingIndexName
                : "idx_" + candidate.tableName + "_" + String.join("_", candidate.columns);
        } else if (candidate.kind == IndexRecommendationEntity.Kind.DROP_INDEX) {
            indexName = candidate.existingIndexName != null
                ? candidate.existingIndexName
                : ("idx_" + candidate.tableName);
            statement = String.format("DROP INDEX %s;", indexName);
        } else {
            indexName = "idx_" + candidate.tableName + "_" + String.join("_", candidate.columns);
            statement = String.format(
                "CREATE INDEX %s ON %s (%s);",
                indexName,
                candidate.tableName,
                columnNames
            );
        }

        // estimatedImpact is now a 0–100 cosmetic — net benefit drives ranking.
        long net = Math.max(0, candidate.workloadScoreMs - candidate.writeCostScore);
        int estimatedImpact = net == 0
            ? Math.min(100, candidate.estimatedImpact)
            : (int) Math.min(100, 20 + Math.log10(1.0 + net) * 10);

        return IndexRecommendationEntity.builder()
            .connectionId(connectionId)
            .tableName(candidate.tableName)
            .columnNames(columnNames)
            .indexName(indexName)
            .createStatement(statement)
            .priority(candidate.priority)
            .status(IndexRecommendationEntity.Status.PENDING)
            .kind(candidate.kind)
            .estimatedImpact(estimatedImpact)
            .reason(candidate.reason)
            .affectedQueries(candidate.affectedQueries)
            .workloadScoreMs(candidate.workloadScoreMs)
            .writeCostScore(candidate.writeCostScore)
            .evidenceCount(candidate.evidence.size())
            .build();
    }

    /**
     * Get all recommendations for a connection
     */
    @Transactional(readOnly = true)
    public List<IndexRecommendationEntity> getRecommendations(String connectionId) {
        return recommendationRepository.findByConnectionIdOrderByPriorityAscCreatedAtDesc(connectionId);
    }

    /**
     * Get pending recommendations
     */
    @Transactional(readOnly = true)
    public List<IndexRecommendationEntity> getPendingRecommendations(String connectionId) {
        return recommendationRepository.findByConnectionIdAndStatusOrderByPriorityAscCreatedAtDesc(
            connectionId,
            IndexRecommendationEntity.Status.PENDING
        );
    }

    /**
     * Mark recommendation as applied
     */
    @Transactional
    public IndexRecommendationEntity markAsApplied(String id) {
        IndexRecommendationEntity recommendation = recommendationRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Recommendation not found: " + id));

        recommendation.markAsApplied();
        return recommendationRepository.save(recommendation);
    }

    /**
     * Dismiss recommendation
     */
    @Transactional
    public IndexRecommendationEntity dismissRecommendation(String id) {
        IndexRecommendationEntity recommendation = recommendationRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Recommendation not found: " + id));

        recommendation.dismiss();
        return recommendationRepository.save(recommendation);
    }

    /**
     * Delete recommendation
     */
    @Transactional
    public void deleteRecommendation(String id) {
        recommendationRepository.deleteById(id);
    }

    /**
     * PROACTIVE: Analyze database schema for missing indexes
     * This runs even without query history and recommends indexes based on:
     * - Tables with no indexes at all
     * - Foreign key columns without indexes
     * - Common filterable columns (email, username, etc.)
     */
    private void analyzeDatabaseSchema(String connectionId, Map<String, RecommendationCandidate> candidates) {
        try {
            log.info("Performing proactive schema analysis for connection: {}", connectionId);

            ConnectionRequest connRequest = credentialService.getDecryptedConnection(connectionId);

            try (Connection connection = connectionService.getConnection(connectionId, connRequest)) {
                String dbType = providerRegistry.getCanonicalName(connRequest.getDbType());

                if ("mysql".equals(dbType)) {
                    analyzeMySQLSchema(connection, connRequest.getDatabase(), candidates);
                } else if ("postgres".equals(dbType)) {
                    analyzePostgreSQLSchema(connection, candidates);
                }
            }

            log.info("Schema analysis completed, found {} potential index candidates", candidates.size());
        } catch (Exception e) {
            log.error("Error analyzing database schema: {}", e.getMessage(), e);
        }
    }

    /**
     * Analyze MySQL schema for missing indexes
     */
    private void analyzeMySQLSchema(Connection connection, String database, Map<String, RecommendationCandidate> candidates) throws SQLException {
        // Get all tables with their sizes
        String tablesQuery = "SELECT TABLE_NAME, TABLE_ROWS, DATA_LENGTH " +
            "FROM INFORMATION_SCHEMA.TABLES " +
            "WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'BASE TABLE' " +
            "ORDER BY DATA_LENGTH DESC";

        List<TableInfo> tables = new ArrayList<>();
        try (PreparedStatement stmt = connection.prepareStatement(tablesQuery)) {
            stmt.setString(1, database);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    TableInfo table = new TableInfo();
                    table.name = rs.getString("TABLE_NAME");
                    table.rowCount = rs.getLong("TABLE_ROWS");
                    table.sizeBytes = rs.getLong("DATA_LENGTH");
                    tables.add(table);
                }
            }
        }

        // Analyze each table
        for (TableInfo table : tables) {
            // Get existing indexes
            Set<String> indexedColumns = getMySQLIndexedColumns(connection, database, table.name);

            // Get foreign keys
            List<String> foreignKeyColumns = getMySQLForeignKeyColumns(connection, database, table.name);

            // Get all columns
            List<ColumnInfo> columns = getMySQLTableColumns(connection, database, table.name);

            // Check for tables with NO indexes at all (except PRIMARY KEY)
            if (indexedColumns.isEmpty() && table.rowCount > 100) {
                recommendIndexForTableWithoutIndexes(table, columns, candidates);
            }

            // Check for foreign keys without indexes
            for (String fkColumn : foreignKeyColumns) {
                if (!indexedColumns.contains(fkColumn)) {
                    recommendIndexForForeignKey(table, fkColumn, candidates);
                }
            }

            // Check for common filterable columns without indexes
            for (ColumnInfo column : columns) {
                if (!indexedColumns.contains(column.name) && shouldBeIndexed(column, table)) {
                    recommendIndexForCommonColumn(table, column, candidates);
                }
            }
        }
    }

    /**
     * Analyze PostgreSQL schema for missing indexes
     */
    private void analyzePostgreSQLSchema(Connection connection, Map<String, RecommendationCandidate> candidates) throws SQLException {
        // Get all tables with their sizes
        String tablesQuery = "SELECT schemaname, tablename, " +
            "pg_total_relation_size(schemaname||'.'||tablename) as total_bytes " +
            "FROM pg_tables " +
            "WHERE schemaname NOT IN ('pg_catalog', 'information_schema') " +
            "ORDER BY total_bytes DESC";

        List<TableInfo> tables = new ArrayList<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(tablesQuery)) {
            while (rs.next()) {
                TableInfo table = new TableInfo();
                table.schema = rs.getString("schemaname");
                table.name = rs.getString("tablename");
                table.sizeBytes = rs.getLong("total_bytes");
                tables.add(table);
            }
        }

        // Analyze each table
        for (TableInfo table : tables) {
            String fullTableName = table.schema + "." + table.name;

            // Get existing indexes
            Set<String> indexedColumns = getPostgreSQLIndexedColumns(connection, table.schema, table.name);

            // Get foreign keys
            List<String> foreignKeyColumns = getPostgreSQLForeignKeyColumns(connection, table.schema, table.name);

            // Get all columns
            List<ColumnInfo> columns = getPostgreSQLTableColumns(connection, table.schema, table.name);

            // Check for tables with NO indexes at all (except PRIMARY KEY)
            if (indexedColumns.isEmpty() && table.sizeBytes > 100000) {
                recommendIndexForTableWithoutIndexes(table, columns, candidates);
            }

            // Check for foreign keys without indexes
            for (String fkColumn : foreignKeyColumns) {
                if (!indexedColumns.contains(fkColumn)) {
                    recommendIndexForForeignKey(table, fkColumn, candidates);
                }
            }

            // Check for common filterable columns without indexes
            for (ColumnInfo column : columns) {
                if (!indexedColumns.contains(column.name) && shouldBeIndexed(column, table)) {
                    recommendIndexForCommonColumn(table, column, candidates);
                }
            }
        }
    }

    /**
     * Get indexed columns for MySQL table. Includes PRIMARY KEY, UNIQUE,
     * and regular index columns.
     *
     * The pre-fix version excluded PRIMARY KEY (`INDEX_NAME != 'PRIMARY'`),
     * which made the schema walker recommend redundant secondary indexes on
     * primary-key columns. See {@link com.dbaagent.service.PerformanceMonitoringService#getIndexCoverage}
     * for the broader leading-prefix-aware coverage check used at the merge layer.
     */
    private Set<String> getMySQLIndexedColumns(Connection connection, String database, String tableName) throws SQLException {
        Set<String> indexed = new HashSet<>();
        String query = "SELECT DISTINCT COLUMN_NAME " +
            "FROM INFORMATION_SCHEMA.STATISTICS " +
            "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?";

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, database);
            stmt.setString(2, tableName);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    indexed.add(rs.getString("COLUMN_NAME"));
                }
            }
        }
        return indexed;
    }

    /**
     * Get indexed columns for PostgreSQL table. Includes PRIMARY KEY, UNIQUE,
     * and regular index columns. (Pre-fix this excluded {@code indisprimary},
     * causing recommendations like {@code ERECEIPT_BOOKING(booking_id)} when
     * {@code booking_id} was the primary key.)
     */
    private Set<String> getPostgreSQLIndexedColumns(Connection connection, String schema, String tableName) throws SQLException {
        Set<String> indexed = new HashSet<>();
        String query = "SELECT a.attname " +
            "FROM pg_index i " +
            "JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey) " +
            "WHERE i.indrelid = (? || '.' || ?)::regclass";

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, schema);
            stmt.setString(2, tableName);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    indexed.add(rs.getString("attname"));
                }
            }
        }
        return indexed;
    }

    /**
     * Get foreign key columns for MySQL table
     */
    private List<String> getMySQLForeignKeyColumns(Connection connection, String database, String tableName) throws SQLException {
        List<String> fkColumns = new ArrayList<>();
        String query = "SELECT COLUMN_NAME " +
            "FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE " +
            "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND REFERENCED_TABLE_NAME IS NOT NULL";

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, database);
            stmt.setString(2, tableName);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    fkColumns.add(rs.getString("COLUMN_NAME"));
                }
            }
        }
        return fkColumns;
    }

    /**
     * Get foreign key columns for PostgreSQL table
     */
    private List<String> getPostgreSQLForeignKeyColumns(Connection connection, String schema, String tableName) throws SQLException {
        List<String> fkColumns = new ArrayList<>();
        String query = "SELECT a.attname " +
            "FROM pg_constraint c " +
            "JOIN pg_attribute a ON a.attnum = ANY(c.conkey) AND a.attrelid = c.conrelid " +
            "WHERE c.contype = 'f' AND c.conrelid = (? || '.' || ?)::regclass";

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, schema);
            stmt.setString(2, tableName);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    fkColumns.add(rs.getString("attname"));
                }
            }
        }
        return fkColumns;
    }

    /**
     * Get all columns for MySQL table
     */
    private List<ColumnInfo> getMySQLTableColumns(Connection connection, String database, String tableName) throws SQLException {
        List<ColumnInfo> columns = new ArrayList<>();
        String query = "SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE " +
            "FROM INFORMATION_SCHEMA.COLUMNS " +
            "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? " +
            "ORDER BY ORDINAL_POSITION";

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, database);
            stmt.setString(2, tableName);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    ColumnInfo col = new ColumnInfo();
                    col.name = rs.getString("COLUMN_NAME");
                    col.dataType = rs.getString("DATA_TYPE");
                    col.isNullable = "YES".equals(rs.getString("IS_NULLABLE"));
                    columns.add(col);
                }
            }
        }
        return columns;
    }

    /**
     * Get all columns for PostgreSQL table
     */
    private List<ColumnInfo> getPostgreSQLTableColumns(Connection connection, String schema, String tableName) throws SQLException {
        List<ColumnInfo> columns = new ArrayList<>();
        String query = "SELECT column_name, data_type, is_nullable " +
            "FROM information_schema.columns " +
            "WHERE table_schema = ? AND table_name = ? " +
            "ORDER BY ordinal_position";

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, schema);
            stmt.setString(2, tableName);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    ColumnInfo col = new ColumnInfo();
                    col.name = rs.getString("column_name");
                    col.dataType = rs.getString("data_type");
                    col.isNullable = "YES".equals(rs.getString("is_nullable"));
                    columns.add(col);
                }
            }
        }
        return columns;
    }

    /**
     * Check if a column should be indexed based on name and type
     */
    private boolean shouldBeIndexed(ColumnInfo column, TableInfo table) {
        String colName = column.name.toLowerCase();
        String dataType = column.dataType.toLowerCase();

        // Common filterable column patterns
        Set<String> commonFilterColumns = Set.of(
            "email", "username", "user_id", "account_id", "customer_id",
            "order_id", "product_id", "status", "type", "category",
            "created_at", "updated_at", "deleted_at", "published_at",
            "slug", "code", "reference", "external_id", "uuid"
        );

        // Check if column name matches common patterns
        if (commonFilterColumns.contains(colName)) {
            return true;
        }

        // Check if column ends with common patterns
        if (colName.endsWith("_id") || colName.endsWith("_at") || colName.endsWith("_status")) {
            return true;
        }

        // Only recommend indexes for indexable data types
        if (dataType.contains("text") || dataType.contains("blob") || dataType.contains("json")) {
            return false;
        }

        // For larger tables (>1000 rows or >1MB), be more aggressive with recommendations
        if (table.rowCount > 1000 || table.sizeBytes > 1048576) {
            return colName.contains("name") || colName.contains("date") || colName.contains("time");
        }

        return false;
    }

    /**
     * Recommend index for table with no indexes
     */
    private void recommendIndexForTableWithoutIndexes(TableInfo table, List<ColumnInfo> columns, Map<String, RecommendationCandidate> candidates) {
        // Find the most likely column to filter on
        ColumnInfo bestColumn = columns.stream()
            .filter(col -> shouldBeIndexed(col, table))
            .findFirst()
            .orElse(null);

        if (bestColumn != null) {
            String key = table.name + ":no_index:" + bestColumn.name;
            RecommendationCandidate candidate = candidates.computeIfAbsent(key, k -> new RecommendationCandidate());
            candidate.tableName = table.name;
            candidate.columns.add(bestColumn.name);
            candidate.priority = IndexRecommendationEntity.Priority.HIGH;
            candidate.estimatedImpact = 40;
            candidate.reason = String.format(
                "Table '%s' has NO secondary indexes. Adding index on '%s' column can significantly improve query performance.",
                table.name, bestColumn.name
            );
            candidate.affectedQueries = 0; // Schema-based recommendation
        }
    }

    /**
     * Recommend index for foreign key without index
     */
    private void recommendIndexForForeignKey(TableInfo table, String fkColumn, Map<String, RecommendationCandidate> candidates) {
        String key = table.name + ":fk:" + fkColumn;
        RecommendationCandidate candidate = candidates.computeIfAbsent(key, k -> new RecommendationCandidate());
        candidate.tableName = table.name;
        candidate.columns.add(fkColumn);
        candidate.priority = IndexRecommendationEntity.Priority.HIGH;
        candidate.estimatedImpact = 35;
        candidate.reason = String.format(
            "Foreign key column '%s' in table '%s' is not indexed. This can cause slow JOIN operations and foreign key constraint checks.",
            fkColumn, table.name
        );
        candidate.affectedQueries = 0; // Schema-based recommendation
    }

    /**
     * Recommend index for common filterable column
     */
    private void recommendIndexForCommonColumn(TableInfo table, ColumnInfo column, Map<String, RecommendationCandidate> candidates) {
        String key = table.name + ":common:" + column.name;
        RecommendationCandidate candidate = candidates.computeIfAbsent(key, k -> new RecommendationCandidate());
        candidate.tableName = table.name;
        candidate.columns.add(column.name);
        candidate.priority = IndexRecommendationEntity.Priority.MEDIUM;
        candidate.estimatedImpact = 25;
        candidate.reason = String.format(
            "Column '%s' in table '%s' is commonly used for filtering/searching but lacks an index.",
            column.name, table.name
        );
        candidate.affectedQueries = 0; // Schema-based recommendation
    }

    /**
     * Helper class for table information
     */
    private static class TableInfo {
        String schema;
        String name;
        long rowCount;
        long sizeBytes;
    }

    /**
     * Helper class for column information
     */
    private static class ColumnInfo {
        String name;
        String dataType;
        boolean isNullable;
    }

    /**
     * Maximum contributing queries persisted per recommendation. pganalyze
     * uses 5 — the same default keeps the evidence payload skimmable.
     */
    private static final int TOP_EVIDENCE_K = 5;

    /** Internal class to hold recommendation candidates during analysis. */
    private static class RecommendationCandidate {
        String tableName;
        Set<String> columns = new LinkedHashSet<>();
        int affectedQueries = 0;
        int estimatedImpact = 0;
        long workloadScoreMs = 0L;
        long writeCostScore = 0L;
        String reason;
        IndexRecommendationEntity.Priority priority = IndexRecommendationEntity.Priority.LOW;
        IndexRecommendationEntity.Kind kind = IndexRecommendationEntity.Kind.CREATE_INDEX;
        /** For DROP candidates: the real existing index name we want to drop. */
        String existingIndexName;
        /** Top-K contributing queries by total time. Persisted to V96 table on merge. */
        Map<String, EvidenceRow> evidence = new LinkedHashMap<>();
        /**
         * Optional CREATE INDEX statement override — used by partial-index and
         * redundant-DROP candidates which need a custom DDL beyond the default
         * {@code CREATE INDEX idx_t_a_b ON t (a, b);}.
         */
        String createStatementOverride;
    }

    /** One row's worth of evidence, used to upsert {@link IndexRecommendationEvidence}. */
    private record EvidenceRow(
        String fingerprint,
        String exampleSql,
        long calls,
        double meanExecTimeMs,
        double totalExecTimeMs,
        Long rowsExamined,
        String role
    ) {
        /** Merge two evidence rows for the same fingerprint — keep max stats so a later cycle that saw a worse instance wins. */
        EvidenceRow merge(EvidenceRow other) {
            if (other == null) return this;
            return new EvidenceRow(
                this.fingerprint,
                this.exampleSql != null ? this.exampleSql : other.exampleSql,
                Math.max(this.calls, other.calls),
                Math.max(this.meanExecTimeMs, other.meanExecTimeMs),
                Math.max(this.totalExecTimeMs, other.totalExecTimeMs),
                Optional.ofNullable(this.rowsExamined).orElse(other.rowsExamined),
                this.role != null ? this.role : other.role
            );
        }
    }

    /**
     * Surface long-idle indexes as DROP_INDEX candidates. We reuse
     * PerformanceMonitoringService.getUnusedIndexes which already does the
     * dialect-specific probe (pg_stat_user_indexes / sys.schema_index_statistics).
     *
     * The recurrence accumulator does the heavy lifting: an index that shows up
     * as unused across many refresh cycles becomes a HIGH-priority drop, while a
     * one-off "I haven't queried it today" stays LOW.
     */
    private void analyzeUnusedIndexes(String connectionId, Map<String, RecommendationCandidate> candidates) {
        try {
            List<Map<String, Object>> unused = performanceMonitoringService.getUnusedIndexes(connectionId);
            if (unused == null || unused.isEmpty()) {
                return;
            }

            // Reset-time guard: if pg_stat_statements_reset (or MySQL equivalent)
            // fired within UNUSED_INDEX_MIN_OBSERVATION_DAYS, the "unused"
            // verdict is meaningless. Demote everything to LOW and prefix the
            // reason so the user knows the signal is provisional.
            Optional<Long> resetAgeSec = performanceMonitoringService.getStatsResetAgeSeconds(connectionId);
            boolean recentReset = resetAgeSec.isPresent()
                && resetAgeSec.get() < UNUSED_INDEX_MIN_OBSERVATION_DAYS * 86_400L;
            String resetWarning = recentReset
                ? String.format("[STATS RESET %dh AGO — provisional] ", resetAgeSec.orElse(0L) / 3_600)
                : "";

            for (Map<String, Object> row : unused) {
                String tableName = asString(row.get("tableName"));
                String indexName = asString(row.get("indexName"));
                if (tableName == null || indexName == null) {
                    continue;
                }

                Long sizeBytes = asLong(row.get("indexSizeBytes"));
                String sizeLabel = asString(row.get("indexSize"));

                // Dedup key shape mirrors CREATE candidates so the merge accumulator
                // routes through the same code path. Index name uniquely identifies a
                // DROP candidate; we stash it in columnNames to keep the key stable
                // and surface it via existingIndexName for the DDL.
                String key = tableName + ":drop:" + indexName;
                RecommendationCandidate candidate = candidates.computeIfAbsent(key, k -> new RecommendationCandidate());
                candidate.tableName = tableName;
                candidate.kind = IndexRecommendationEntity.Kind.DROP_INDEX;
                candidate.existingIndexName = indexName;
                candidate.columns.clear();
                candidate.columns.add(indexName); // serves as dedup discriminator
                candidate.affectedQueries = 0;
                candidate.reason = String.format(
                    "%sIndex '%s' on '%s' has not been used since last reset%s. Dropping it reclaims storage and removes per-write maintenance overhead.",
                    resetWarning,
                    indexName,
                    tableName,
                    sizeLabel != null ? " (size " + sizeLabel + ")" : ""
                );

                // Bigger indexes hurt more (storage + write amplification), so weight impact by size.
                int impact = 10;
                if (sizeBytes != null) {
                    if (sizeBytes >= 1_073_741_824L) {        // ≥ 1 GiB
                        impact = 60;
                        candidate.priority = IndexRecommendationEntity.Priority.HIGH;
                    } else if (sizeBytes >= 104_857_600L) {   // ≥ 100 MiB
                        impact = 35;
                        candidate.priority = IndexRecommendationEntity.Priority.MEDIUM;
                    } else if (sizeBytes >= 10_485_760L) {    // ≥ 10 MiB
                        impact = 20;
                        candidate.priority = IndexRecommendationEntity.Priority.MEDIUM;
                    } else {
                        candidate.priority = IndexRecommendationEntity.Priority.LOW;
                    }
                } else {
                    candidate.priority = IndexRecommendationEntity.Priority.LOW;
                }
                // Stats reset within the window → demote everything to LOW.
                // The size-derived priority is still the cap when the stats
                // window is long enough.
                if (recentReset) {
                    candidate.priority = IndexRecommendationEntity.Priority.LOW;
                }
                candidate.estimatedImpact = impact;
                // Encode size as workload score so the ranking still
                // surfaces big-and-unused indexes ahead of small-and-unused.
                if (sizeBytes != null) candidate.workloadScoreMs = sizeBytes / 1_000_000L;
            }
            log.info("Unused-index analysis added {} DROP candidate(s) for connection {}",
                unused.size(), connectionId);
        } catch (Exception e) {
            // Probe is best-effort. A missing pg_stat_statements or sys.* should not
            // sink the whole refresh cycle.
            log.warn("Could not analyze unused indexes for {}: {}", connectionId, e.getMessage());
        }
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Long asLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // V2 (DBA-grade) helpers: pulling orphaned signals + penalties + guards.
    // -----------------------------------------------------------------------

    /** Min confidence on an inferred JOIN before we'll emit an index candidate. */
    private static final BigDecimal MIN_JOIN_CONFIDENCE = new BigDecimal("50");

    /** Skew threshold for partial-index suggestions (matches {@code KeyColumnAnalysis.isHeavilySkewed}). */
    private static final double PARTIAL_INDEX_SKEW_THRESHOLD = 0.7;

    /** Minimum days since {@code stats_reset} before a DROP_INDEX claim is trusted. */
    private static final long UNUSED_INDEX_MIN_OBSERVATION_DAYS = 7L;

    /**
     * Pull inferred JOIN relationships {@link JoinRelationshipInferenceService}
     * already produced. Each row is per-(source.col, target.col) with frequency
     * counters — proper workload signal we'd otherwise re-derive from regex.
     */
    private void pullInferredJoinPatterns(String connectionId, Map<String, RecommendationCandidate> candidates) {
        try {
            List<InferredTableRelationship> rels = inferredTableRelationshipRepository
                .findHighConfidenceRelationships(connectionId, MIN_JOIN_CONFIDENCE);
            for (InferredTableRelationship r : rels) {
                if (r.getSourceTable() == null || r.getSourceColumn() == null) continue;
                String key = candidateKey(r.getSourceTable(), r.getSourceColumn(),
                    IndexRecommendationEntity.Kind.CREATE_INDEX);
                RecommendationCandidate c = candidates.computeIfAbsent(key, k -> new RecommendationCandidate());
                c.tableName = r.getSourceTable().toLowerCase(Locale.ROOT);
                c.columns.add(r.getSourceColumn().toLowerCase(Locale.ROOT));
                int joinCount = r.getJoinCount() == null ? 0 : r.getJoinCount();
                int distinct  = r.getDistinctQueryCount() == null ? 0 : r.getDistinctQueryCount();
                // No raw timing — approximate with joinCount × 50ms as a
                // representative penalty per missing FK-side index.
                c.workloadScoreMs += (long) joinCount * 50L;
                c.affectedQueries = Math.max(c.affectedQueries, distinct);
                c.priority = upgrade(c.priority,
                    joinCount >= 100 ? IndexRecommendationEntity.Priority.HIGH
                    : IndexRecommendationEntity.Priority.MEDIUM);
                c.reason = String.format(
                    "Inferred JOIN '%s.%s ↔ %s.%s' observed %d times across %d distinct queries (confidence %.0f). FK-side index speeds up nested-loop joins.",
                    r.getSourceTable(), r.getSourceColumn(),
                    r.getTargetTable(), r.getTargetColumn(),
                    joinCount, distinct,
                    r.getConfidenceScore() == null ? 0.0 : r.getConfidenceScore().doubleValue()
                );
            }
        } catch (Exception e) {
            log.warn("Could not pull inferred JOIN relationships for {}: {}", connectionId, e.getMessage());
        }
    }

    /**
     * Read pre-computed composite candidates from
     * {@link KeyColumnAnalysisService#detectCompositeIndexes}. These are
     * cross-query co-occurrence patterns scored by selectivity product; they
     * were orphaned — populated but never surfaced via top-N.
     */
    private void pullCompositeRecommendations(String connectionId, Map<String, RecommendationCandidate> candidates) {
        try {
            List<CompositeIndexRecommendation> rows = compositeIndexRecommendationRepository
                .findByConnectionIdAndStatusOrderByPriorityAsc(connectionId, CompositeIndexRecommendation.Status.PENDING);
            for (CompositeIndexRecommendation ci : rows) {
                if (ci.getTableName() == null || ci.getColumnNames() == null) continue;
                List<String> cols = parseColumnList(ci.getColumnNames());
                if (cols.isEmpty()) continue;
                String columnsKey = String.join(",", cols);
                String key = candidateKey(ci.getTableName(), columnsKey, IndexRecommendationEntity.Kind.CREATE_INDEX);
                RecommendationCandidate c = candidates.computeIfAbsent(key, k -> new RecommendationCandidate());
                c.tableName = ci.getTableName().toLowerCase(Locale.ROOT);
                if (c.columns.isEmpty()) c.columns.addAll(cols);
                // Selectivity product → score boost. A composite where every
                // column is highly selective (product ≈ 1.0) is a big win.
                double selProduct = ci.getSelectivityProduct() == null ? 0.5 : ci.getSelectivityProduct().doubleValue();
                int coOccurrence = ci.getCoOccurrenceCount() == null ? 0 : ci.getCoOccurrenceCount();
                c.workloadScoreMs += (long) (coOccurrence * 100L * (1.0 + selProduct));
                c.affectedQueries = Math.max(c.affectedQueries, coOccurrence);
                c.priority = upgrade(c.priority, mapCompositePriority(ci.getPriority()));
                if (c.reason == null) {
                    c.reason = String.format(
                        "Composite candidate from co-occurrence analysis: %d shared queries, selectivity product %.3f.",
                        coOccurrence, selProduct
                    );
                }
            }
        } catch (Exception e) {
            log.warn("Could not pull composite recommendations for {}: {}", connectionId, e.getMessage());
        }
    }

    private List<String> parseColumnList(String raw) {
        if (raw == null) return List.of();
        String s = raw.trim();
        // KeyColumnAnalysisService persists JSON arrays like ["col1","col2"]; tolerate plain CSV too.
        if (s.startsWith("[")) {
            try {
                List<String> parsed = objectMapper.readValue(s, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
                return parsed.stream().map(c -> c.toLowerCase(Locale.ROOT)).collect(Collectors.toList());
            } catch (Exception e) {
                // Fall through to CSV
            }
        }
        return Arrays.stream(s.split(",")).map(String::trim).filter(p -> !p.isEmpty())
            .map(p -> p.toLowerCase(Locale.ROOT)).collect(Collectors.toList());
    }

    private IndexRecommendationEntity.Priority mapCompositePriority(CompositeIndexRecommendation.Priority p) {
        if (p == null) return IndexRecommendationEntity.Priority.LOW;
        return switch (p) {
            case CRITICAL, HIGH -> IndexRecommendationEntity.Priority.HIGH;
            case MEDIUM -> IndexRecommendationEntity.Priority.MEDIUM;
            case LOW -> IndexRecommendationEntity.Priority.LOW;
        };
    }

    /**
     * Read {@link ColumnAntiPattern} rows of type {@code UNINDEXED_FILTER} /
     * {@code UNINDEXED_JOIN}. These carry persisted {@code totalExecutionTimeMs}
     * and {@code affectedQueriesCount} — proper workload signal already paid
     * for by {@code KeyColumnAnalysisService}.
     */
    private void pullColumnAntiPatterns(String connectionId, Map<String, RecommendationCandidate> candidates) {
        try {
            List<ColumnAntiPattern> rows = columnAntiPatternRepository
                .findByConnectionIdAndStatusOrderBySeverityDescDetectedAtDesc(
                    connectionId, ColumnAntiPattern.Status.ACTIVE);
            for (ColumnAntiPattern ap : rows) {
                if (ap.getPatternType() == null) continue;
                String pt = ap.getPatternType().toUpperCase(Locale.ROOT);
                if (!pt.startsWith("UNINDEXED")) continue;
                if (ap.getTableName() == null || ap.getColumnName() == null) continue;

                String colKey = ap.getColumnName().toLowerCase(Locale.ROOT);
                String key = candidateKey(ap.getTableName(), colKey, IndexRecommendationEntity.Kind.CREATE_INDEX);
                RecommendationCandidate c = candidates.computeIfAbsent(key, k -> new RecommendationCandidate());
                c.tableName = ap.getTableName().toLowerCase(Locale.ROOT);
                c.columns.add(colKey);
                long totalMs = ap.getTotalExecutionTimeMs() == null ? 0L : ap.getTotalExecutionTimeMs();
                c.workloadScoreMs = Math.max(c.workloadScoreMs, totalMs);
                int affected = ap.getAffectedQueriesCount() == null ? 0 : ap.getAffectedQueriesCount();
                c.affectedQueries = Math.max(c.affectedQueries, affected);
                c.priority = upgrade(c.priority, mapAntiPatternSeverity(ap.getSeverity()));
                if (c.reason == null || c.reason.isBlank()) {
                    c.reason = String.format(
                        "%s anti-pattern: %s (%d queries, %.1fs total time).",
                        pt, ap.getColumnName(), affected, totalMs / 1000.0
                    );
                }
            }
        } catch (Exception e) {
            log.warn("Could not pull column anti-patterns for {}: {}", connectionId, e.getMessage());
        }
    }

    private IndexRecommendationEntity.Priority mapAntiPatternSeverity(ColumnAntiPattern.Severity s) {
        if (s == null) return IndexRecommendationEntity.Priority.LOW;
        return switch (s) {
            case CRITICAL, HIGH -> IndexRecommendationEntity.Priority.HIGH;
            case MEDIUM -> IndexRecommendationEntity.Priority.MEDIUM;
            case LOW -> IndexRecommendationEntity.Priority.LOW;
        };
    }

    /**
     * Subtract per-write maintenance cost from each candidate's net benefit.
     * Approximation: {@code (inserts + updates + deletes) × shared_column_factor}.
     * Cap at the candidate's own workload score so a high-write table can drop
     * to net-zero (kept in the ledger but ranks below positive-net candidates).
     */
    private void applyWriteCostPenalty(String connectionId, Map<String, RecommendationCandidate> candidates) {
        Map<String, Map<String, Object>> writeStats = performanceMonitoringService.getTableWriteStats(connectionId);
        if (writeStats == null || writeStats.isEmpty()) return;
        for (RecommendationCandidate c : candidates.values()) {
            if (c.tableName == null || c.kind != IndexRecommendationEntity.Kind.CREATE_INDEX) continue;
            Map<String, Object> stats = writeStats.get(c.tableName.toLowerCase(Locale.ROOT));
            if (stats == null) continue;
            long writes = asLong(stats.get("totalWrites")) == null ? 0L : asLong(stats.get("totalWrites"));
            // Each write costs roughly ms-per-column-of-the-index in maintenance.
            // The constant is tunable — 0.001 ms/column/write is a conservative
            // RDS-class estimate.
            long penalty = (long) (writes * Math.max(1, c.columns.size()) * 0.001);
            c.writeCostScore = penalty;
        }
    }

    /**
     * Partial-index hints: when a column is heavily skewed (one value dominates)
     * AND we know the dominant value via {@link ColumnValueCache}, emit a
     * partial CREATE INDEX with a WHERE clause.
     *
     * Industry pattern: {@code CREATE INDEX idx_orders_status_active ON orders (status) WHERE status = 'active';}
     */
    private void analyzePartialIndexCandidates(String connectionId, Map<String, RecommendationCandidate> candidates) {
        try {
            List<KeyColumnAnalysis> rows = keyColumnAnalysisRepository
                .findByConnectionIdOrderByImportanceScoreDesc(connectionId);
            for (KeyColumnAnalysis k : rows) {
                if (k.getSkewCoefficient() == null || k.getSkewCoefficient() < PARTIAL_INDEX_SKEW_THRESHOLD) continue;
                if (Boolean.TRUE.equals(k.getHasUnusedIndex())) continue;
                if (k.getIndexName() != null) continue; // Already indexed
                String dominant = lookupDominantValue(connectionId, k.getTableName(), k.getColumnName());
                if (dominant == null) continue;

                String table = k.getTableName().toLowerCase(Locale.ROOT);
                String col = k.getColumnName().toLowerCase(Locale.ROOT);
                String discriminator = col + "_partial_" + sanitizeForIdent(dominant);
                String key = candidateKey(table, discriminator, IndexRecommendationEntity.Kind.CREATE_INDEX);
                RecommendationCandidate c = candidates.computeIfAbsent(key, k2 -> new RecommendationCandidate());
                c.tableName = table;
                c.columns.add(col);
                c.priority = upgrade(c.priority, IndexRecommendationEntity.Priority.MEDIUM);
                c.workloadScoreMs = Math.max(c.workloadScoreMs, 5_000L);
                c.reason = String.format(
                    "Skew %.2f on '%s.%s' — one value dominates. Partial index on the dominant value is faster and smaller than a full B-tree.",
                    k.getSkewCoefficient(), table, col
                );
                c.createStatementOverride = String.format(
                    "CREATE INDEX idx_%s_%s_%s ON %s (%s) WHERE %s = %s;",
                    table, col, sanitizeForIdent(dominant), table, col, col, quoteSqlValue(dominant)
                );
            }
        } catch (Exception e) {
            log.warn("Could not analyze partial-index candidates for {}: {}", connectionId, e.getMessage());
        }
    }

    private String lookupDominantValue(String connectionId, String tableName, String columnName) {
        try {
            Optional<ColumnValueCache> cache = columnValueCacheRepository
                .findByConnectionIdAndTableNameAndColumnName(connectionId, tableName, columnName);
            if (cache.isEmpty() || cache.get().getSampleValues() == null) return null;
            JsonNode arr = objectMapper.readTree(cache.get().getSampleValues());
            if (arr.isArray() && arr.size() > 0) {
                return arr.get(0).asText();
            }
        } catch (Exception e) {
            log.debug("dominant-value lookup failed for {}.{}: {}", tableName, columnName, e.getMessage());
        }
        return null;
    }

    private String sanitizeForIdent(String s) {
        return s.replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase(Locale.ROOT);
    }

    private String quoteSqlValue(String s) {
        // Naive; partial-index DDL is a hint, not auto-executed.
        return "'" + s.replace("'", "''") + "'";
    }

    /**
     * Surface prefix-redundant indexes as DROP_INDEX candidates, distinct from
     * fully-unused ones. {@code getDuplicateIndexes()} already does the
     * detection; we just wire it into the same recommendation pipeline.
     */
    private void analyzeRedundantIndexes(String connectionId, Map<String, RecommendationCandidate> candidates) {
        try {
            List<Map<String, Object>> dups = performanceMonitoringService.getDuplicateIndexes(connectionId);
            if (dups == null) return;
            for (Map<String, Object> row : dups) {
                String table = asString(row.get("tableName"));
                String redundantIndex = asString(row.get("redundantIndexName"));
                String dominantIndex = asString(row.get("dominantIndexName"));
                String dropStmt = asString(row.get("dropStatement"));
                if (table == null || redundantIndex == null) continue;
                table = table.toLowerCase(Locale.ROOT);

                String key = candidateKey(table, redundantIndex, IndexRecommendationEntity.Kind.DROP_INDEX);
                RecommendationCandidate c = candidates.computeIfAbsent(key, k -> new RecommendationCandidate());
                c.tableName = table;
                c.kind = IndexRecommendationEntity.Kind.DROP_INDEX;
                c.existingIndexName = redundantIndex;
                c.columns.clear();
                c.columns.add(redundantIndex);
                c.priority = IndexRecommendationEntity.Priority.MEDIUM;
                c.reason = String.format(
                    "Index '%s' is a redundant prefix of '%s' on table '%s'. Drop to reclaim storage and remove duplicate write cost.",
                    redundantIndex, dominantIndex == null ? "another index" : dominantIndex, table
                );
                if (dropStmt != null && !dropStmt.isBlank()) {
                    c.createStatementOverride = dropStmt;
                }
            }
        } catch (Exception e) {
            log.warn("Could not analyze redundant indexes for {}: {}", connectionId, e.getMessage());
        }
    }
}
