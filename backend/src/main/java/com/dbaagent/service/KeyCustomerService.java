package com.dbaagent.service;

import com.dbaagent.dto.KeyCustomerInfo;
import com.dbaagent.dto.KeyCustomerResult;
import com.dbaagent.model.KeyColumnAnalysis;
import com.dbaagent.model.SlowQuery;
import com.dbaagent.model.SlowQueryAnalysis;
import com.dbaagent.model.SlowQueryHistory;
import com.dbaagent.model.TableClassification;
import com.dbaagent.repository.KeyColumnAnalysisRepository;
import com.dbaagent.repository.TableClassificationRepository;
import com.dbaagent.service.EnhancedSqlParserService.ColumnLiteralPair;
import com.dbaagent.util.SlowQueryCapPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

@Service
@Slf4j
@RequiredArgsConstructor
public class KeyCustomerService {

    private final SlowQueryHistoryService historyService;
    private final EnhancedSqlParserService sqlParserService;
    private final KeyColumnAnalysisRepository keyColumnAnalysisRepository;
    private final TableClassificationRepository tableClassificationRepository;

    private static final int DEFAULT_LIMIT = 20;
    private static final int PREVIEW_MAX_LEN = 120;
    private static final double MIN_IMPORTANCE_SCORE = 30.0;

    private static final Pattern SENSITIVE_COLUMN_PATTERN = Pattern.compile(
        "email|phone|token|secret|password|ssn|credit.?card|social.?security|api.?key|access.?token|refresh.?token|private.?key",
        Pattern.CASE_INSENSITIVE
    );

    private static final Set<String> COLUMN_LEVEL_MASK_TYPES = Set.of(
        "PII_HIGH", "PII_MEDIUM", "FINANCIAL", "HEALTH", "AUTH"
    );


    public Optional<KeyCustomerResult> analyze(String connectionId, int limit, String tableNameFilter) {
        // 1. Get latest history
        Optional<SlowQueryHistory> historyOpt = historyService.getLatestHistory(connectionId);
        if (historyOpt.isEmpty()) {
            return Optional.empty();
        }

        SlowQueryHistory history = historyOpt.get();
        SlowQueryAnalysis analysis = historyService.getAnalysisData(history);
        if (analysis == null || analysis.getTopSlowQueries() == null) {
            return Optional.empty();
        }

        // 2. Apply shared cap
        List<SlowQuery> cappedQueries = SlowQueryCapPolicy.capQueries(analysis.getTopSlowQueries());
        boolean wasCapped = analysis.getTopSlowQueries().size() > cappedQueries.size();

        // 3. Load key column analysis data
        List<KeyColumnAnalysis> keyColumns = keyColumnAnalysisRepository
            .findByConnectionIdOrderByImportanceScoreDesc(connectionId);
        Map<String, KeyColumnAnalysis> keyColumnMap = buildKeyColumnMap(keyColumns);
        boolean hasKeyColumnData = !keyColumns.isEmpty();

        // 4. Load table classifications for masking
        List<TableClassification> tableClassifications = tableClassificationRepository
            .findLatestByConnectionIdOrderByTableNameAsc(connectionId);
        Map<String, TableClassification> classificationMap = new HashMap<>();
        for (TableClassification tc : tableClassifications) {
            classificationMap.put(tc.getTableName().toLowerCase(), tc);
        }

        // 5. Extract literals and aggregate
        Map<String, AggregationBucket> buckets = new LinkedHashMap<>();

        for (SlowQuery query : cappedQueries) {
            if (query.getSampleQuery() == null || query.getSampleQuery().isBlank()) {
                continue;
            }

            List<ColumnLiteralPair> literals = sqlParserService
                .extractWhereClauseLiterals(query.getSampleQuery());

            // Deduplicate literals per query to prevent overcounting
            // when the same (table, column, value) appears multiple times in one WHERE clause
            Set<String> seenBucketsForQuery = new HashSet<>();

            for (ColumnLiteralPair pair : literals) {
                if (pair.getValue() == null) continue;

                // Apply table name filter if specified
                if (tableNameFilter != null) {
                    // Skip rows with null table when a table filter is active
                    if (pair.getTableName() == null
                        || !pair.getTableName().equalsIgnoreCase(tableNameFilter)) {
                        continue;
                    }
                }

                // Cross-reference with key column analysis
                if (!shouldIncludeColumn(pair, keyColumnMap, hasKeyColumnData)) {
                    continue;
                }

                String bucketKey = buildBucketKey(pair);

                // Only count each query once per bucket
                if (!seenBucketsForQuery.add(bucketKey)) {
                    continue;
                }

                AggregationBucket bucket = buckets.computeIfAbsent(bucketKey,
                    k -> new AggregationBucket(pair.getTableName(), pair.getColumnName(), pair.getValue()));

                bucket.addQuery(query);
            }
        }

        // 6. Convert to DTOs with masking
        List<KeyCustomerInfo> keyCustomers = new ArrayList<>();
        for (AggregationBucket bucket : buckets.values()) {
            KeyColumnAnalysis kca = keyColumnMap.get(
                keyColumnKey(bucket.tableName, bucket.columnName));

            String displayValue = applyMaskingPolicy(
                bucket.tableName, bucket.columnName, bucket.rawValue, classificationMap);

            String worstPreview = bucket.worstQuery != null && bucket.worstQuery.getNormalizedQuery() != null
                ? truncate(bucket.worstQuery.getNormalizedQuery(), PREVIEW_MAX_LEN)
                : null;

            keyCustomers.add(KeyCustomerInfo.builder()
                .id(UUID.randomUUID().toString())
                .tableName(bucket.tableName)
                .columnName(bucket.columnName)
                .displayValue(displayValue)
                .rawValue(bucket.rawValue)
                .keyType(kca != null ? kca.getKeyType() : null)
                .importanceScore(kca != null ? kca.getImportanceScore().doubleValue() : null)
                .slowQueryCount(bucket.queryCount)
                .totalDbTimeMs(bucket.totalDbTimeMs)
                .avgExecutionTimeMs(bucket.totalDbTimeMs > 0 && bucket.queryCount > 0
                    ? (double) bucket.totalDbTimeMs / bucket.queryCount : 0)
                .worstQueryTimeMs(bucket.worstQueryTimeMs)
                .worstQueryPreview(worstPreview)
                .criticalCount(bucket.criticalCount)
                .highCount(bucket.highCount)
                .matchingQueryIds(new ArrayList<>(bucket.matchingQueryIds))
                .build());
        }

        // 7. Sort by totalDbTimeMs desc, apply limit
        keyCustomers.sort(Comparator.comparingLong(KeyCustomerInfo::getTotalDbTimeMs).reversed());
        int effectiveLimit = limit > 0 ? limit : DEFAULT_LIMIT;
        if (keyCustomers.size() > effectiveLimit) {
            keyCustomers = keyCustomers.subList(0, effectiveLimit);
        }

        return Optional.of(KeyCustomerResult.builder()
            .connectionId(connectionId)
            .analyzedAt(history.getCreatedAt().toInstant(java.time.ZoneOffset.UTC))
            .totalKeyCustomers(keyCustomers.size())
            .totalSlowQueriesAnalyzed(cappedQueries.size())
            .queriesCapped(wasCapped)
            .keyCustomers(keyCustomers)
            .build());
    }

    // ==================== Aggregation ====================

    private static class AggregationBucket {
        final String tableName;
        final String columnName;
        final String rawValue;
        int queryCount;
        long totalDbTimeMs;
        double worstQueryTimeMs;
        SlowQuery worstQuery;
        int criticalCount;
        int highCount;
        final Set<String> matchingQueryIds = new LinkedHashSet<>();

        AggregationBucket(String tableName, String columnName, String rawValue) {
            this.tableName = tableName;
            this.columnName = columnName;
            this.rawValue = rawValue;
        }

        void addQuery(SlowQuery query) {
            queryCount++;
            double avgMs = query.getAvgExecutionTimeMs() != null ? query.getAvgExecutionTimeMs() : 0;
            long count = query.getCallCount() != null ? query.getCallCount() : 1;
            totalDbTimeMs += (long) (avgMs * count);

            if (avgMs > worstQueryTimeMs) {
                worstQueryTimeMs = avgMs;
                worstQuery = query;
            }

            if (query.getQueryId() != null) {
                matchingQueryIds.add(query.getQueryId());
            }

            String sev = query.getSeverity() != null ? query.getSeverity().name() : "";
            if ("CRITICAL".equals(sev)) criticalCount++;
            else if ("HIGH".equals(sev)) highCount++;
        }
    }

    private String buildBucketKey(ColumnLiteralPair pair) {
        return (pair.getTableName() != null ? pair.getTableName().toLowerCase() : "")
            + "|" + pair.getColumnName().toLowerCase()
            + "|" + pair.getValue();
    }

    // ==================== Column Filtering ====================

    private boolean shouldIncludeColumn(ColumnLiteralPair pair,
                                         Map<String, KeyColumnAnalysis> keyColumnMap,
                                         boolean hasKeyColumnData) {
        if (!hasKeyColumnData) {
            // No key column data — include all columns
            return true;
        }

        String key = keyColumnKey(pair.getTableName(), pair.getColumnName());
        KeyColumnAnalysis kca = keyColumnMap.get(key);

        if (kca == null) {
            return false;
        }

        // Include TRUE_KEY/SURROGATE_KEY or columns with importance >= threshold
        String keyType = kca.getKeyType();
        if ("TRUE_KEY".equals(keyType) || "SURROGATE_KEY".equals(keyType)) {
            return true;
        }

        return kca.getImportanceScore() != null
            && kca.getImportanceScore().doubleValue() >= MIN_IMPORTANCE_SCORE;
    }

    private Map<String, KeyColumnAnalysis> buildKeyColumnMap(List<KeyColumnAnalysis> keyColumns) {
        Map<String, KeyColumnAnalysis> map = new HashMap<>();
        for (KeyColumnAnalysis kca : keyColumns) {
            map.put(keyColumnKey(kca.getTableName(), kca.getColumnName()), kca);
        }
        return map;
    }

    private String keyColumnKey(String tableName, String columnName) {
        return (tableName != null ? tableName.toLowerCase() : "") + "." +
               (columnName != null ? columnName.toLowerCase() : "");
    }

    // ==================== Masking Policy ====================

    private String applyMaskingPolicy(String tableName, String columnName, String rawValue,
                                       Map<String, TableClassification> classificationMap) {
        if (rawValue == null) return null;

        TableClassification classification = tableName != null
            ? classificationMap.get(tableName.toLowerCase(Locale.ROOT))
            : null;

        // Rule 1+2: Column-level sensitivity from sensitiveColumns JSON.
        // We check if this specific column is flagged as sensitive, rather than
        // masking all columns from a sensitive table (which produces false
        // positives for operational columns like hotel_id, booking_status).
        if (classification != null && classification.getSensitiveColumns() != null) {
            for (Map<String, Object> sc : classification.getSensitiveColumns()) {
                String scColumn = (String) sc.get("column");
                String scType = (String) sc.get("type");
                if (columnName.equalsIgnoreCase(scColumn) && scType != null
                    && COLUMN_LEVEL_MASK_TYPES.contains(scType.toUpperCase(Locale.ROOT))) {
                    return maskValue(rawValue);
                }
            }
        }

        // Rule 3: Name pattern matching
        if (SENSITIVE_COLUMN_PATTERN.matcher(columnName).find()) {
            return maskValue(rawValue);
        }

        // Rule 4: Truncate long values
        if (rawValue.length() > 100) {
            return rawValue.substring(0, 50) + "... (" + rawValue.length() + " chars total)";
        }

        // Rule 5: Pass through
        return rawValue;
    }

    private String maskValue(String rawValue) {
        return "***(" + rawValue.length() + " chars)";
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return null;
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }
}
