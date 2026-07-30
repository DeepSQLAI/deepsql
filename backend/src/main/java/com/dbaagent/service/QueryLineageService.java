package com.dbaagent.service;

import com.dbaagent.model.QueryLineage;
import com.dbaagent.model.SlowQuery;
import com.dbaagent.model.SqlUsage;
import com.dbaagent.repository.ColumnDisambiguationRepository;
import com.dbaagent.repository.QueryLineageRepository;
import com.dbaagent.util.QueryNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class QueryLineageService {
    private final QueryLineageRepository queryLineageRepository;
    private final ColumnDisambiguationRepository columnDisambiguationRepository;
    private final SqlUsageService sqlUsageService;

    @Transactional
    public void recordLineage(String connectionId, String queryText, String source) {
        if (connectionId == null || queryText == null || queryText.isBlank()) {
            return;
        }

        Map<String, String> disambiguationMap = columnDisambiguationRepository.findByConnectionId(connectionId)
            .stream()
            .collect(Collectors.toMap(
                entry -> entry.getColumnName().toLowerCase(Locale.ROOT),
                entry -> entry.getPreferredTable().toLowerCase(Locale.ROOT),
                (a, b) -> a
            ));

        SqlUsage usage = sqlUsageService.parseUsage(connectionId, queryText, disambiguationMap);
        if ((usage.getTables() == null || usage.getTables().isEmpty()) &&
            (usage.getColumns() == null || usage.getColumns().isEmpty())) {
            return;
        }

        String tablesUsed = usage.getTables() != null
            ? String.join(",", usage.getTables())
            : null;
        String columnsUsed = usage.getColumns() != null
            ? String.join(",", usage.getColumns())
            : null;

        String normalizedQuery = QueryNormalizer.normalize(queryText);
        String queryHash = QueryNormalizer.generateMD5Hash(
            normalizedQuery.isBlank() ? queryText : normalizedQuery
        );

        QueryLineage lineage = QueryLineage.builder()
            .connectionId(connectionId)
            .source(source != null ? source : "UNKNOWN")
            .queryText(queryText)
            .queryHash(queryHash)
            .normalizedQuery(normalizedQuery.isBlank() ? null : normalizedQuery)
            .tablesUsed(tablesUsed)
            .columnsUsed(columnsUsed)
            .build();

        queryLineageRepository.save(lineage);
    }

    @Transactional
    public void recordSlowQueryLineage(
        String connectionId,
        SlowQuery slowQuery,
        String analysisId,
        String analysisTimeRange,
        LocalDateTime analysisDate
    ) {
        if (connectionId == null || slowQuery == null) {
            return;
        }

        Optional<QueryLineage> lineageOpt = buildSlowQueryLineage(
            connectionId,
            slowQuery,
            analysisId,
            analysisTimeRange,
            analysisDate
        );

        if (lineageOpt.isEmpty()) {
            return;
        }

        QueryLineage lineage = lineageOpt.get();
        if (analysisId != null && lineage.getQueryHash() != null &&
            queryLineageRepository.existsByConnectionIdAndAnalysisIdAndQueryHash(
                connectionId,
                analysisId,
                lineage.getQueryHash()
            )) {
            return;
        }

        queryLineageRepository.save(lineage);
    }

    public Optional<QueryLineage> buildSlowQueryLineage(
        String connectionId,
        SlowQuery slowQuery,
        String analysisId,
        String analysisTimeRange,
        LocalDateTime analysisDate
    ) {
        if (connectionId == null || slowQuery == null) {
            return Optional.empty();
        }

        String queryText = slowQuery.getQueryText();
        if (queryText == null || queryText.isBlank()) {
            queryText = slowQuery.getNormalizedQuery();
        }
        if (queryText == null || queryText.isBlank()) {
            return Optional.empty();
        }

        String normalizedQuery = slowQuery.getNormalizedQuery();
        if (normalizedQuery == null || normalizedQuery.isBlank()) {
            normalizedQuery = QueryNormalizer.normalize(queryText);
        }

        String queryHash = slowQuery.getQueryId();
        if (queryHash == null || queryHash.isBlank()) {
            queryHash = QueryNormalizer.generateMD5Hash(
                normalizedQuery != null && !normalizedQuery.isBlank() ? normalizedQuery : queryText
            );
        }

        Map<String, String> disambiguationMap = columnDisambiguationRepository.findByConnectionId(connectionId)
            .stream()
            .collect(Collectors.toMap(
                entry -> entry.getColumnName().toLowerCase(Locale.ROOT),
                entry -> entry.getPreferredTable().toLowerCase(Locale.ROOT),
                (a, b) -> a
            ));

        SqlUsage usage = sqlUsageService.parseUsage(connectionId, queryText, disambiguationMap);
        if ((usage.getTables() == null || usage.getTables().isEmpty()) &&
            (usage.getColumns() == null || usage.getColumns().isEmpty())) {
            return Optional.empty();
        }

        String tablesUsed = usage.getTables() != null
            ? String.join(",", usage.getTables())
            : null;
        String columnsUsed = usage.getColumns() != null
            ? String.join(",", usage.getColumns())
            : null;

        LocalDateTime createdAt = slowQuery.getLastSeen() != null
            ? slowQuery.getLastSeen()
            : (analysisDate != null ? analysisDate : LocalDateTime.now());

        QueryLineage lineage = QueryLineage.builder()
            .connectionId(connectionId)
            .source("SLOW_QUERY_LOG")
            .sourceDetails(slowQuery.getSource())
            .queryText(queryText)
            .queryHash(queryHash)
            .normalizedQuery(normalizedQuery != null && !normalizedQuery.isBlank() ? normalizedQuery : null)
            .tablesUsed(tablesUsed)
            .columnsUsed(columnsUsed)
            .avgExecutionTimeMs(slowQuery.getAvgExecutionTimeMs())
            .maxExecutionTimeMs(slowQuery.getMaxExecutionTimeMs())
            .minExecutionTimeMs(slowQuery.getMinExecutionTimeMs())
            .totalExecutionTimeMs(slowQuery.getTotalExecutionTimeMs())
            .callCount(slowQuery.getCallCount())
            .rowsExamined(slowQuery.getRowsExamined())
            .rowsSent(slowQuery.getRowsSent())
            .severity(slowQuery.getSeverity() != null ? slowQuery.getSeverity().name() : null)
            .performanceImpact(slowQuery.getPerformanceImpact())
            .firstSeenAt(slowQuery.getFirstSeen())
            .lastSeenAt(slowQuery.getLastSeen())
            .analysisId(analysisId)
            .analysisTimeRange(analysisTimeRange)
            .createdAt(createdAt)
            .build();

        return Optional.of(lineage);
    }
}
