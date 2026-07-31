package com.dbaagent.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ChatQuestionRoutingService {

    public enum RouteType {
        BRAIN_METADATA,
        BI_QUERY,
        GENERAL
    }

    public enum BrainTopic {
        SCHEMA,
        PERFORMANCE,
        KEY_COLUMNS,
        RELATIONSHIPS,
        GROWTH,
        CLASSIFICATION,
        WORKLOAD,
        TUNING,
        GENERAL
    }

    public record QuestionRoute(RouteType type, BrainTopic brainTopic) {
        public boolean isBrainMetadata() {
            return type == RouteType.BRAIN_METADATA;
        }

        public boolean isBiQuery() {
            return type == RouteType.BI_QUERY;
        }
    }

    private static final Pattern KEY_COLUMN_PATTERN = Pattern.compile(
        ".*(inferred keys?|key columns?|key fields?|important columns?|most queried columns?|frequently queried columns?|primary keys?|foreign keys?|join columns?|filter columns?|\\bkeys?\\b.*\\btable\\b|\\btable\\b.*\\bkeys?\\b).*",
        Pattern.CASE_INSENSITIVE);

    private static final Pattern RELATIONSHIP_PATTERN = Pattern.compile(
        ".*(relationships?|join path|how .* related|related tables|er diagram|connect(ed)? to|link(ed)? to|referential|foreign key relationship"
        + "|frequently joined|tables?.*joined|joined.*with|join frequency|most joined|commonly joined|often joined"
        + "|how should .* join(ed)?|how do i join|how to join).*",
        Pattern.CASE_INSENSITIVE);

    private static final Pattern GROWTH_PATTERN = Pattern.compile(
        ".*(grow(th|ing)|fastest growing|growth anomalies?|table growth|storage trend|capacity|bloat|run out of space).*",
        Pattern.CASE_INSENSITIVE);

    private static final Pattern CLASSIFICATION_PATTERN = Pattern.compile(
        ".*(fact tables?|dimension tables?|bridge tables?|lookup tables?|orphaned tables?|schema pattern|star schema|snowflake|business tables?|business domains?|table roles?).*",
        Pattern.CASE_INSENSITIVE);

    private static final Pattern PERFORMANCE_PATTERN = Pattern.compile(
        // "usage" is deliberately qualified. On its own it also matches ordinary business
        // language — "customers churning because usage dropped" is a BI question about a
        // product metric, not a request for index-usage statistics, and routing it to
        // brain metadata answers the wrong question entirely.
        ".*(slow quer|performance|latency|bottleneck|regress|regression|missing index|unused index|index recommendation|execution plan|query health|critical performance|top \\d+ slow|active quer|wait event|waiting|pressure|cardinality|statistics|plan quality|hot tables?|hottest|index usage|table usage|usage stats?|roi|cost benefit|fix suggestions?|performance actions?|top actions?|query plans?).*",
        Pattern.CASE_INSENSITIVE);

    private static final Pattern WORKLOAD_PATTERN = Pattern.compile(
        ".*(workload|oltp|olap|read heavy|write heavy|throughput|qps|connection pool|memory settings?|buffer pool).*",
        Pattern.CASE_INSENSITIVE);

    private static final Pattern TUNING_PATTERN = Pattern.compile(
        ".*(tun(e|ing)|knobs?|settings?|configuration parameters?|cache settings?|buffer settings?).*",
        Pattern.CASE_INSENSITIVE);

    private static final Pattern SIMPLE_SCHEMA_PATTERN = Pattern.compile(
        "(.*(how many|count|number of).*(tables?|views?|indexes?|columns?).*)|(^(list|show|what are).*tables?$)|(.*(database|total).*(size|big|large).*)|(.*(schema|database).*(summary|overview|stats|statistics).*)",
        Pattern.CASE_INSENSITIVE);

    private static final Pattern SNAPSHOT_METADATA_PATTERN = Pattern.compile(
        ".*\\b(schema\\s+snapshots?|snapshot\\s+history|brain\\s+init\\s+history|init\\s+history)\\b.*",
        Pattern.CASE_INSENSITIVE);

    private static final Pattern SCHEMA_DELTA_PATTERN = Pattern.compile(
        ".*\\b(schema|table|tables|column|columns|field|fields|index|indexes|indices|constraint|constraints|foreign key|foreign keys)\\b.*"
            + "\\b(change|changes|changed|added|new|created|removed|dropped|modified|updated|altered|delta|drift)\\b.*"
            + "|.*\\b(change|changes|changed|added|new|created|removed|dropped|modified|updated|altered|delta|drift)\\b.*"
            + "\\b(schema|table|tables|column|columns|field|fields|index|indexes|indices|constraint|constraints|foreign key|foreign keys)\\b.*",
        Pattern.CASE_INSENSITIVE);

    private static final Pattern BI_METRIC_PATTERN = Pattern.compile(
        ".*\\b(revenue|sales|gmv|arr|mrr|bookings?|orders?|customers?|payments?|transactions?|retention|churn|ltv|aov|inventory|pipeline|funnel|conversion)\\b.*",
        Pattern.CASE_INSENSITIVE);

    private static final Pattern DATA_REQUEST_PATTERN = Pattern.compile(
        ".*\\b(show|list|give|get|find|fetch|retrieve|count|how many|top|bottom|compare|trend|breakdown|summarize|analyse|analyze)\\b.*",
        Pattern.CASE_INSENSITIVE);

    private static final Pattern SQL_REFERENCE_PATTERN = Pattern.compile("(?i)\\b(?:from|join)\\s+([`\"a-zA-Z0-9_.]+)");

    public QuestionRoute classify(String question) {
        if (question == null || question.isBlank()) {
            return new QuestionRoute(RouteType.GENERAL, BrainTopic.GENERAL);
        }

        String normalized = question.toLowerCase(Locale.ROOT).trim();
        if (normalized.isEmpty()) {
            return new QuestionRoute(RouteType.GENERAL, BrainTopic.GENERAL);
        }

        if (KEY_COLUMN_PATTERN.matcher(normalized).matches()) {
            return new QuestionRoute(RouteType.BRAIN_METADATA, BrainTopic.KEY_COLUMNS);
        }
        if (RELATIONSHIP_PATTERN.matcher(normalized).matches()) {
            return new QuestionRoute(RouteType.BRAIN_METADATA, BrainTopic.RELATIONSHIPS);
        }
        if (GROWTH_PATTERN.matcher(normalized).matches()) {
            return new QuestionRoute(RouteType.BRAIN_METADATA, BrainTopic.GROWTH);
        }
        if (CLASSIFICATION_PATTERN.matcher(normalized).matches()) {
            return new QuestionRoute(RouteType.BRAIN_METADATA, BrainTopic.CLASSIFICATION);
        }
        boolean looksLikeBiMetric = BI_METRIC_PATTERN.matcher(normalized).matches();
        boolean looksLikeDataRequest = DATA_REQUEST_PATTERN.matcher(normalized).matches();
        if (looksLikeBusinessPerformancePrompt(normalized) && (looksLikeBiMetric || looksLikeDataRequest)) {
            return new QuestionRoute(RouteType.BI_QUERY, BrainTopic.GENERAL);
        }

        if (PERFORMANCE_PATTERN.matcher(normalized).matches()) {
            return new QuestionRoute(RouteType.BRAIN_METADATA, BrainTopic.PERFORMANCE);
        }
        if (WORKLOAD_PATTERN.matcher(normalized).matches()) {
            return new QuestionRoute(RouteType.BRAIN_METADATA, BrainTopic.WORKLOAD);
        }
        if (TUNING_PATTERN.matcher(normalized).matches()) {
            return new QuestionRoute(RouteType.BRAIN_METADATA, BrainTopic.TUNING);
        }
        if (SNAPSHOT_METADATA_PATTERN.matcher(normalized).matches()) {
            return new QuestionRoute(RouteType.BRAIN_METADATA, BrainTopic.SCHEMA);
        }
        if (SCHEMA_DELTA_PATTERN.matcher(normalized).matches()) {
            return new QuestionRoute(RouteType.BRAIN_METADATA, BrainTopic.SCHEMA);
        }
        if (looksLikeExactSchemaQuestion(normalized)) {
            return new QuestionRoute(RouteType.BRAIN_METADATA, BrainTopic.SCHEMA);
        }
        if (SIMPLE_SCHEMA_PATTERN.matcher(normalized).matches()) {
            return new QuestionRoute(RouteType.BRAIN_METADATA, BrainTopic.SCHEMA);
        }

        if (looksLikeBiMetric || looksLikeDataRequest) {
            return new QuestionRoute(RouteType.BI_QUERY, BrainTopic.GENERAL);
        }

        return new QuestionRoute(RouteType.GENERAL, BrainTopic.GENERAL);
    }

    public boolean isMetadataOnlySql(String sql, String dbType) {
        if (sql == null || sql.isBlank()) {
            return false;
        }

        String normalized = stripComments(sql).trim();
        if (normalized.isEmpty()) {
            return false;
        }

        String upper = normalized.toUpperCase(Locale.ROOT);
        if (upper.startsWith("SHOW ") || upper.startsWith("DESCRIBE ") || upper.startsWith("DESC ")) {
            return true;
        }

        List<String> referencedRelations = extractReferencedRelations(normalized);
        if (referencedRelations.isEmpty()) {
            return upper.startsWith("SELECT") || upper.startsWith("WITH") || upper.startsWith("EXPLAIN");
        }

        String normalizedDbType = dbType == null ? "" : dbType.toLowerCase(Locale.ROOT);
        for (String relation : referencedRelations) {
            String candidate = relation.replace("`", "").replace("\"", "").toLowerCase(Locale.ROOT);
            if (!isMetadataRelation(candidate, normalizedDbType)) {
                return false;
            }
        }
        return true;
    }

    private List<String> extractReferencedRelations(String sql) {
        Matcher matcher = SQL_REFERENCE_PATTERN.matcher(sql);
        List<String> relations = new ArrayList<>();
        while (matcher.find()) {
            String relation = matcher.group(1);
            if (relation != null && !relation.isBlank() && !relation.startsWith("(")) {
                relations.add(relation);
            }
        }
        return relations;
    }

    private boolean isMetadataRelation(String relation, String dbType) {
        if (relation.startsWith("information_schema.")) {
            return true;
        }

        if ("mysql".equals(dbType)) {
            return relation.startsWith("performance_schema.")
                || relation.startsWith("sys.")
                || relation.startsWith("mysql.");
        }

        if ("postgres".equals(dbType) || "postgresql".equals(dbType)) {
            return relation.startsWith("pg_catalog.")
                || relation.startsWith("pg_");
        }

        return false;
    }

    private String stripComments(String sql) {
        return sql
            .replaceAll("(?s)/\\*.*?\\*/", " ")
            .replaceAll("(?m)--.*$", " ");
    }

    private boolean looksLikeBusinessPerformancePrompt(String normalized) {
        if (normalized == null || normalized.isBlank() || !normalized.contains("performance")) {
            return false;
        }
        return normalized.matches(".*\\b(payment|payments|gateway|refund|refunds|revenue|booking|bookings|customer|customers|customer|customers|account|accounts|billing|transaction|transactions)\\b.*");
    }

    private boolean looksLikeExactSchemaQuestion(String normalized) {
        if (normalized == null || normalized.isBlank()) {
            return false;
        }
        if (SchemaQuestionUtil.looksLikeExactTableRowCountQuestion(normalized)
            || SchemaQuestionUtil.looksLikeExactTableIndexQuestion(normalized)
            || SchemaQuestionUtil.looksLikeExactTableColumnQuestion(normalized)) {
            return true;
        }
        if (!normalized.matches(".*\\b(table|tables|view|views|column|columns|fields|schema|structure|describe|definition)\\b.*")) {
            return false;
        }
        // Superlatives and rankings are not exact-schema lookups; neither is design advice.
        // "Which tables should I use to build an accounts module?" names tables but wants
        // reasoning over the schema, not a listing of it — answering it from cached
        // metadata drops exactly the part the user asked for.
        if (normalized.matches(".*\\b(least|most|best|worst|top|bottom|largest|smallest|used|unused|slow|growth|performance|fact|dimension|pattern|relationship|join)\\b.*")
            || normalized.matches(".*\\b(should|build|design|model|recommend|suggest|architect)\\b.*")) {
            return false;
        }
        return normalized.matches(".*\\b(what|which|show|list|display|describe|structure|schema)\\b.*\\b(columns?|fields?|tables?|views?)\\b.*")
            || normalized.matches(".*\\b(columns?|fields?)\\b.*\\b(in|for|of|on)\\b.*")
            || normalized.matches(".*\\bdescribe\\b.*\\b(table|view)\\b.*")
            || normalized.matches(".*\\b(schema|structure|definition)\\b.*\\b(of|for)\\b.*\\b(table|view)\\b.*");
    }
}
