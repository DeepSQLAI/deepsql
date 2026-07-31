package com.dbaagent.service;

import com.dbaagent.model.*;
import com.dbaagent.repository.*;
import com.dbaagent.repository.SlowLogSourceConfigRepository;
import com.dbaagent.repository.SlowQueryHistoryRepository;
import com.dbaagent.util.QueryLabeler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Read-side service for the slow-query analytics tables.
 *
 * Assembles the {@code slow_query_run} / {@code slow_query_customer_day}
 * facts and the {@code query_fingerprints} / {@code slow_query_customer}
 * dimensions into the DTOs the REST API, MCP tools, and CLI return. Pure
 * queries — no writes.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SlowQueryAnalyticsService {

    private final SlowQueryRunRepository runRepository;
    private final SlowQueryCustomerDayRepository customerDayRepository;
    private final SlowQueryCustomerRepository customerRepository;
    private final SlowQuerySampleRepository sampleRepository;
    private final QueryFingerprintRepository fingerprintRepository;
    private final QueryLineageRepository queryLineageRepository;
    private final ConnectionAnalyticsConfigRepository configRepository;
    private final SlowQueryHistoryRepository historyRepository;
    private final SlowLogSourceConfigRepository sourceConfigRepository;
    private final ConnectionService connectionService;

    // ── full-text recovery for truncated samples ─────────────────────────────

    /**
     * Prefix length (in chars, post-normalization) used to LIKE-match a
     * sample against {@code query_lineage}. ~150 chars is enough to uniquely
     * identify the query (the SELECT clause alone is usually that long)
     * without straying into the WHERE clause where literal values differ
     * between executions and would break the prefix match.
     */
    private static final int RECOVERY_PREFIX_LENGTH = 150;

    /** Minimum prefix length below which recovery isn't worth attempting. */
    private static final int RECOVERY_MIN_PREFIX_LENGTH = 32;

    /** Pre-compiled — used per-sample inside hot loops. */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern SPACE_AROUND_PUNCT = Pattern.compile("\\s*([.,();])\\s*");

    /**
     * Convert MySQL/Postgres line comments ({@code -- text}) into block
     * comments ({@code /* text *}{@code /}) when the surrounding SQL has no
     * newlines to terminate them. Triggered for samples whose source
     * collapsed multi-line queries to a single line — the slow-log parser
     * used to do this before the {@code "\n"} fix, so historical lineage
     * rows can still have inline comments that swallow the rest of the
     * query when pasted into a client.
     *
     * <p>The match boundary is the next whitespace-prefixed {@code (} or one
     * of the major SQL keywords ({@code OR AND WHERE GROUP ORDER HAVING
     * LIMIT UNION}) so the comment body captures only its own text. The
     * comment content goes inside {@code /* *}{@code /} and the boundary
     * stays where the dev originally put it.
     */
    private static final Pattern LINE_COMMENT_TO_BLOCK = Pattern.compile(
        "--([^\\n]*?)(\\s(?:\\(|OR\\s|AND\\s|WHERE\\s|GROUP\\s|ORDER\\s|HAVING\\s|LIMIT\\s|UNION\\s))",
        Pattern.CASE_INSENSITIVE);

    /**
     * Heuristic: an SQL string almost certainly terminated cleanly is one
     * that ends with a closing-character token. Anything else (trailing
     * comma, unclosed parenthesis, dangling AND/OR/WHERE, bare identifier)
     * suggests the source truncated us mid-clause and there's a longer
     * version somewhere — the UI surfaces a "truncated" tag in that case.
     */
    private static final Pattern LIKELY_COMPLETE_SUFFIX = Pattern.compile(
        ".*[;)]\\s*$|.*\\b(asc|desc|limit\\s+\\d+(\\s*,\\s*\\d+)?)\\s*$",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /**
     * Column-name stems that strongly suggest a tenant/customer key.
     * A tenant column almost always contains one of these.
     */
    private static final List<String> TENANT_STEMS = List.of(
        "tenant", "customer", "account", "organization", "org", "workspace",
        "client", "company", "customer", "property", "merchant", "store",
        "business", "subscriber", "site", "shop");

    /** System schemas to exclude when scanning information_schema (Postgres + MySQL). */
    private static final String SYSTEM_SCHEMAS =
        "'pg_catalog','information_schema','mysql','performance_schema','sys'";

    // ── DTOs ─────────────────────────────────────────────────────────────────

    /** One tracked query with its most recent run's metrics. */
    public record QuerySummary(
        String fingerprint,
        String label,
        String normalizedSql,
        LocalDate latestDay,
        Long callsDelta,
        Double meanExecMs,
        Double maxExecMs,
        Double regressionFactor,
        boolean counterReset
    ) {}

    /** One point in a query's day-by-day timeline. */
    public record TimelinePoint(
        LocalDate day,
        Long callsDelta,
        Long callsCumulative,
        Double meanExecMs,
        Double maxExecMs,
        Double totalExecMsDelta,
        Double regressionFactor,
        boolean counterReset
    ) {}

    /** A query that got slower between two runs. */
    public record QueryRegression(
        String fingerprint,
        String label,
        String normalizedSql,
        LocalDate day,
        Double meanExecMs,
        Double regressionFactor,
        Long callsDelta
    ) {}

    /** One customer's slice of a query on a given day. */
    public record CustomerBreakdown(
        String customerId,
        String customerName,
        Long sampleCount,
        Double meanExecMs,
        Double maxExecMs,
        Double regressionFactor
    ) {}

    /**
     * A real captured execution of a query — literal-bearing SQL you can copy and run.
     *
     * <p>{@code truncated} is true when the returned {@code rawSql} appears to
     * end mid-clause (trailing comma, unclosed parenthesis, etc.) after
     * lineage recovery. The UI uses it to badge the sample with a
     * "Truncated by MySQL Performance Schema" notice so users don't think a
     * dangling {@code ,} is part of the real query.
     */
    public record QuerySample(
        String rawSql,
        String customerId,
        String customerName,
        Double execMs,
        java.time.LocalDateTime capturedAt,
        boolean truncated
    ) {}

    /** One customer's overall slow-query footprint — for the By-Customer list. */
    public record CustomerSummary(
        String customerId,
        String customerName,
        long queryCount,
        long sampleCount,
        Double totalSlowMs,
        Double slowestMeanMs,
        LocalDate latestDay
    ) {}

    /** One slow query attributed to a customer, with that customer's metrics. */
    public record CustomerQueryRow(
        String fingerprint,
        String label,
        long sampleCount,
        Double meanExecMs,
        Double maxExecMs,
        LocalDate latestDay
    ) {}

    /** A recommended tenant-column candidate, ranked by table coverage. */
    public record TenantColumnSuggestion(
        String columnName,
        int tableCount,
        int score,
        String reason
    ) {}

    // ── queries ──────────────────────────────────────────────────────────────

    /** Every tracked query for a connection, as of its most recent analysis run. */
    public List<QuerySummary> listQueries(String connectionId) {
        LocalDate latest = runRepository.findTopByConnectionIdOrderByAnalyzedOnDesc(connectionId)
            .map(SlowQueryRun::getAnalyzedOn)
            .orElse(null);
        if (latest == null) {
            return List.of();
        }
        List<SlowQueryRun> runs = runRepository.findByConnectionIdAndAnalyzedOn(connectionId, latest);
        List<QuerySummary> out = new ArrayList<>(runs.size());
        for (SlowQueryRun r : runs) {
            String sql = normalizedSql(connectionId, r.getFingerprint());
            out.add(new QuerySummary(
                r.getFingerprint(),
                QueryLabeler.label(sql),
                sql,
                r.getAnalyzedOn(),
                r.getCallsDelta(),
                r.getMeanExecMs(),
                r.getMaxExecMs(),
                r.getRegressionFactor(),
                r.isCounterReset()));
        }
        out.sort(Comparator.comparing(
            QuerySummary::meanExecMs, Comparator.nullsLast(Comparator.reverseOrder())));
        return out;
    }

    /** The full retained timeline for one query, oldest day first. */
    public List<TimelinePoint> timeline(String connectionId, String fingerprint) {
        List<SlowQueryRun> runs = runRepository
            .findByConnectionIdAndFingerprintOrderByAnalyzedOnDesc(connectionId, fingerprint);
        List<TimelinePoint> out = new ArrayList<>(runs.size());
        for (SlowQueryRun r : runs) {
            out.add(new TimelinePoint(
                r.getAnalyzedOn(),
                r.getCallsDelta(),
                r.getCallsCumulative(),
                r.getMeanExecMs(),
                r.getMaxExecMs(),
                r.getTotalExecMsDelta(),
                r.getRegressionFactor(),
                r.isCounterReset()));
        }
        Collections.reverse(out); // chronological for charting
        return out;
    }

    /**
     * Queries that regressed on the most recent analyzed day (or a given day).
     *
     * @param minFactor minimum slowdown multiple — 1.5 means "≥50% slower".
     */
    public List<QueryRegression> regressions(String connectionId, LocalDate day, double minFactor) {
        LocalDate target = day != null ? day
            : runRepository.findTopByConnectionIdOrderByAnalyzedOnDesc(connectionId)
                .map(SlowQueryRun::getAnalyzedOn)
                .orElse(null);
        if (target == null) {
            return List.of();
        }
        List<SlowQueryRun> runs = runRepository.findRegressions(connectionId, target, minFactor);
        List<QueryRegression> out = new ArrayList<>(runs.size());
        for (SlowQueryRun r : runs) {
            String sql = normalizedSql(connectionId, r.getFingerprint());
            out.add(new QueryRegression(
                r.getFingerprint(),
                QueryLabeler.label(sql),
                sql,
                r.getAnalyzedOn(),
                r.getMeanExecMs(),
                r.getRegressionFactor(),
                r.getCallsDelta()));
        }
        return out;
    }

    /**
     * Per-customer breakdown of one query. When no day is given, uses the most
     * recent day that actually has rollup rows — so a fresh analysis shows up
     * immediately instead of waiting for "yesterday" to have data.
     */
    public List<CustomerBreakdown> customerBreakdown(String connectionId, String fingerprint, LocalDate day) {
        LocalDate target = day != null ? day
            : customerDayRepository
                .findFirstByConnectionIdAndFingerprintOrderByDayDesc(connectionId, fingerprint)
                .map(SlowQueryCustomerDay::getDay)
                .orElse(LocalDate.now());
        List<SlowQueryCustomerDay> rows = customerDayRepository
            .findByConnectionIdAndFingerprintAndDay(connectionId, fingerprint, target);

        // resolve names once
        Map<String, String> names = new HashMap<>();
        for (SlowQueryCustomer c : customerRepository.findByConnectionIdOrderByLastSeenAtDesc(connectionId)) {
            names.put(c.getCustomerId(), c.getCustomerName());
        }

        List<CustomerBreakdown> out = new ArrayList<>(rows.size());
        for (SlowQueryCustomerDay r : rows) {
            out.add(new CustomerBreakdown(
                r.getCustomerId(),
                names.get(r.getCustomerId()),
                r.getSampleCount(),
                r.getMeanExecMs(),
                r.getMaxExecMs(),
                r.getRegressionFactor()));
        }
        out.sort(Comparator.comparing(
            CustomerBreakdown::meanExecMs, Comparator.nullsLast(Comparator.reverseOrder())));
        return out;
    }

    /**
     * Literal-bearing samples for one (customer, query) pair — what the
     * By-Customer view's "view full query" affordance shows.
     */
    public List<QuerySample> samplesForCustomerQuery(
            String connectionId, String customerId, String fingerprint) {
        List<SlowQuerySample> samples = sampleRepository
            .findTop20ByConnectionIdAndFingerprintAndCustomerIdOrderByExecMsDesc(
                connectionId, fingerprint, customerId);
        if (samples.isEmpty()) {
            return List.of();
        }
        String customerName = customerRepository
            .findByConnectionIdAndCustomerId(connectionId, customerId)
            .map(SlowQueryCustomer::getCustomerName)
            .orElse(null);
        List<QuerySample> out = new ArrayList<>(samples.size());
        for (SlowQuerySample s : samples) {
            String recovered = recoverFullText(connectionId, s.getRawSql());
            out.add(new QuerySample(
                recovered,
                s.getCustomerId(), customerName,
                s.getExecMs(), s.getCapturedAt(),
                looksTruncated(recovered)));
        }
        return out;
    }

    /**
     * Real captured executions of a query — literal-bearing SQL the user can
     * copy and run, slowest first. This is what the detail pane shows instead
     * of the (truncated, internal-only) normalized digest.
     */
    public List<QuerySample> querySamples(String connectionId, String fingerprint) {
        List<SlowQuerySample> samples = sampleRepository
            .findTop10ByConnectionIdAndFingerprintOrderByExecMsDesc(connectionId, fingerprint);
        if (samples.isEmpty()) {
            return List.of();
        }
        Map<String, String> names = new HashMap<>();
        for (SlowQueryCustomer c : customerRepository.findByConnectionIdOrderByLastSeenAtDesc(connectionId)) {
            names.put(c.getCustomerId(), c.getCustomerName());
        }
        List<QuerySample> out = new ArrayList<>(samples.size());
        for (SlowQuerySample s : samples) {
            String recovered = recoverFullText(connectionId, s.getRawSql());
            out.add(new QuerySample(
                recovered,
                s.getCustomerId(),
                s.getCustomerId() == null ? null : names.get(s.getCustomerId()),
                s.getExecMs(),
                s.getCapturedAt(),
                looksTruncated(recovered)));
        }
        return out;
    }

    /**
     * Look in {@code query_lineage} for a longer version of this sample's SQL
     * and return that if found, otherwise return the sample as-is.
     *
     * <p>Three sources end up in {@code query_lineage} for a connection, in
     * order of trustworthiness for full text:
     * <ol>
     *   <li><b>FILE_UPLOAD</b> — parsed from the actual MySQL slow-query log
     *       file (via the CloudWatch ingest path). Up to multiple KB per row,
     *       no source-side truncation.
     *   <li><b>Performance Schema</b> (mislabeled SLOW_QUERY_LOG by the
     *       hardcoded source string in {@code QueryLineageService}) — same
     *       1024-byte cap as the original sample. Useless for recovery; the
     *       {@code LENGTH(query_text) > :minLength} filter excludes it.
     *   <li><b>SQL_RUNNER</b> — user-typed queries from the chat editor. Can
     *       be arbitrarily long; useful when the user manually pasted the
     *       full query while debugging.
     * </ol>
     *
     * <p>Cross-format prefix match: PERF_SCHEMA samples format identifiers
     * with backticks and spaces around dots ({@code SELECT `b` . `id`}),
     * while slow-log file entries don't ({@code SELECT b.id}). Same query,
     * incompatible byte prefixes. We normalize both sides (strip backticks,
     * collapse whitespace around punctuation, lowercase) before LIKE-matching
     * — see {@link QueryLineageRepository#findLongestByConnectionIdAndNormalizedQueryTextPrefix}.
     *
     * <p>No length threshold: we used to skip samples under 1024 bytes
     * assuming they weren't truncated, but PERF_SCHEMA can cap shorter than
     * the configured limit when a query contains specific token shapes (we
     * see 947-char samples for queries the real slow log shows are several
     * KB long). Always attempt — the per-call cost is one connection-scoped
     * indexed LIKE that returns LIMIT 1, and if nothing longer exists we
     * return the original.
     *
     * <p>The prefix is capped at {@value RECOVERY_PREFIX_LENGTH} chars to
     * stay inside the SELECT clause where literal values don't differ
     * between executions.
     */
    private String recoverFullText(String connectionId, String rawSql) {
        if (rawSql == null || rawSql.isBlank()) {
            return rawSql;
        }
        String normalized = normalizeForMatching(rawSql);
        int prefixLen = Math.min(RECOVERY_PREFIX_LENGTH, normalized.length() - 16);
        if (prefixLen < RECOVERY_MIN_PREFIX_LENGTH) {
            return defangLineComments(rawSql);
        }
        String prefix = normalized.substring(0, prefixLen);
        // Escape SQL LIKE wildcards. Escape the backslash FIRST so we don't
        // double-escape the backslashes we add for % and _.
        String escaped = prefix
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_") + "%";
        try {
            QueryLineage match = queryLineageRepository
                .findLongestByConnectionIdAndNormalizedQueryTextPrefix(
                    connectionId, escaped, rawSql.length());
            if (match != null && match.getQueryText() != null
                    && match.getQueryText().length() > rawSql.length()) {
                return defangLineComments(match.getQueryText());
            }
        } catch (Exception e) {
            log.debug("Sample full-text recovery failed for connection {}: {}",
                connectionId, e.getMessage());
        }
        return defangLineComments(rawSql);
    }

    /**
     * If the SQL has line comments ({@code -- text}) but no newlines, convert
     * each comment to a block comment ({@code /* text *}{@code /}) so it
     * doesn't swallow the rest of the query when pasted into a client.
     * Historical slow-log lineage rows were ingested with newlines collapsed
     * to spaces, which broke this; new ingests preserve newlines (the parser
     * was fixed alongside) and this no-ops for them.
     *
     * <p>Pre-checks (no comments, or already has newlines) make this cheap
     * to call on every sample.
     */
    static String defangLineComments(String sql) {
        if (sql == null || sql.indexOf("--") < 0 || sql.indexOf('\n') >= 0) {
            return sql;
        }
        return LINE_COMMENT_TO_BLOCK.matcher(sql).replaceAll("/* $1 */$2");
    }

    /**
     * Apply the same aggressive normalization that
     * {@link QueryLineageRepository#findLongestByConnectionIdAndNormalizedQueryTextPrefix}
     * applies on the lineage side, so the prefix the Java caller sends and
     * the bytes the SQL is comparing line up exactly.
     */
    private static String normalizeForMatching(String sql) {
        String stripped = sql.replace("`", "");
        String collapsed = SPACE_AROUND_PUNCT.matcher(stripped).replaceAll("$1");
        return WHITESPACE.matcher(collapsed).replaceAll(" ").trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Detect "ends mid-clause" by looking at the trailing character. A real
     * query ends with {@code ;}, {@code )}, an {@code ORDER BY ... ASC/DESC},
     * or a {@code LIMIT n[, m]}. Anything else (dangling comma, unclosed
     * paren, bare identifier, trailing operator) means the source cut us off
     * and the caller should warn the user.
     *
     * Returns false for any sample where recovery returned a longer text —
     * that text came from a complete slow-log entry and is trustworthy.
     * Returns true for capped PERF_SCHEMA samples that had no longer match
     * in lineage (the common case for queries that don't appear in the slow
     * log: master-side queries when we're ingesting from a read replica's
     * log group, queries that ran outside the fetched CloudWatch window,
     * etc.).
     */
    private static boolean looksTruncated(String sql) {
        if (sql == null || sql.isBlank()) {
            return false;
        }
        return !LIKELY_COMPLETE_SUFFIX.matcher(sql).matches();
    }

    // ── By-Customer dimension ─────────────────────────────────────────────────

    /**
     * All customers seen on this connection, ranked by total slow time. Backs
     * the "By Customer" sub-tab — the customer-centric axis of the analytics.
     */
    public List<CustomerSummary> listCustomers(String connectionId) {
        List<SlowQueryCustomerDayRepository.CustomerAggregate> aggregates =
            customerDayRepository.aggregateByCustomer(connectionId);
        if (aggregates.isEmpty()) {
            return List.of();
        }
        Map<String, String> names = new HashMap<>();
        for (SlowQueryCustomer c : customerRepository.findByConnectionIdOrderByLastSeenAtDesc(connectionId)) {
            names.put(c.getCustomerId(), c.getCustomerName());
        }
        List<CustomerSummary> out = new ArrayList<>(aggregates.size());
        for (var row : aggregates) {
            out.add(new CustomerSummary(
                row.getCustomerId(),
                names.get(row.getCustomerId()),
                row.getQueryCount() == null ? 0L : row.getQueryCount(),
                row.getSampleCount() == null ? 0L : row.getSampleCount(),
                row.getTotalSlowMs(),
                row.getSlowestMean(),
                row.getLatestDay()));
        }
        out.sort(Comparator.comparing(
            CustomerSummary::totalSlowMs, Comparator.nullsLast(Comparator.reverseOrder())));
        return out;
    }

    /** Every slow query attributed to one customer, ranked by their mean exec time. */
    public List<CustomerQueryRow> queriesForCustomer(String connectionId, String customerId) {
        List<SlowQueryCustomerDayRepository.CustomerQueryAggregate> rows =
            customerDayRepository.aggregateQueriesForCustomer(connectionId, customerId);
        List<CustomerQueryRow> out = new ArrayList<>(rows.size());
        for (var row : rows) {
            long count = row.getSampleCount() == null ? 0L : row.getSampleCount();
            Double total = row.getTotalExecMs();
            Double mean = (total != null && count > 0) ? total / count : null;
            String sql = normalizedSql(connectionId, row.getFingerprint());
            out.add(new CustomerQueryRow(
                row.getFingerprint(),
                QueryLabeler.label(sql),
                count,
                mean,
                row.getMaxExecMs(),
                row.getLatestDay()));
        }
        out.sort(Comparator.comparing(
            CustomerQueryRow::meanExecMs, Comparator.nullsLast(Comparator.reverseOrder())));
        return out;
    }

    private String normalizedSql(String connectionId, String fingerprint) {
        return fingerprintRepository.findByConnectionIdAndFingerprint(connectionId, fingerprint)
            .map(QueryFingerprint::getNormalizedQuery)
            .orElse(null);
    }

    // ── tenant-column recommendations ─────────────────────────────────────────

    /**
     * Recommend candidate tenant/customer columns for a connection by scanning
     * its schema. A real tenant key has two tells: a name that contains a
     * tenant-ish stem ({@code customer_id}, {@code customer_id}, …) and presence
     * across many tables. We rank by (table coverage × id-shape bonus) so the
     * column that scopes the most tables floats to the top.
     *
     * Read-only — a single {@code information_schema.columns} aggregation
     * against the target database.
     */
    public List<TenantColumnSuggestion> suggestTenantColumns(String connectionId) {
        JdbcTemplate jdbc = connectionService.getJdbcTemplateForBackgroundJob(connectionId);
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT column_name AS col, COUNT(DISTINCT table_name) AS tables "
                + "FROM information_schema.columns "
                + "WHERE table_schema NOT IN (" + SYSTEM_SCHEMAS + ") "
                + "GROUP BY column_name");

        List<TenantColumnSuggestion> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Object colObj = row.get("col");
            Object tablesObj = row.get("tables");
            if (colObj == null || !(tablesObj instanceof Number)) {
                continue;
            }
            String column = colObj.toString();
            int tables = ((Number) tablesObj).intValue();
            // A tenant key scopes multiple tables — a column on a single table
            // is almost certainly not one.
            if (tables < 2) {
                continue;
            }
            String lower = column.toLowerCase(Locale.ROOT);
            boolean stemMatch = TENANT_STEMS.stream().anyMatch(lower::contains);
            if (!stemMatch) {
                continue;
            }
            boolean idShaped = lower.endsWith("id") || lower.endsWith("_id")
                || lower.endsWith("_key") || lower.endsWith("code");
            int score = tables * (idShaped ? 3 : 1);
            String reason = "Appears in " + tables + " table" + (tables == 1 ? "" : "s")
                + (idShaped ? "; name looks like an identifier column" : "");
            out.add(new TenantColumnSuggestion(column, tables, score, reason));
        }
        out.sort(Comparator.comparingInt(TenantColumnSuggestion::score).reversed());
        return out.size() > 12 ? out.subList(0, 12) : out;
    }

    private record NameLookup(String table, String idCol, String nameCol) {}

    /** Column names that, on the tenant's own table, are likely the human name. */
    private static final List<String> NAME_COLUMN_CANDIDATES = List.of(
        "name", "display_name", "full_name", "title", "label", "company_name");

    /**
     * Best-effort: given a tenant column like {@code customer_id}, find the table
     * that holds the tenant rows and the columns to resolve an id to a name —
     * so the customer name-lookup config fills itself in. Returns null when no
     * confident match exists (the user can still set it manually).
     */
    private NameLookup autoDetectNameLookup(String connectionId, String tenantColumn) {
        String stem = tenantColumn.toLowerCase(Locale.ROOT)
            .replaceAll("(_?id|_?key|code)$", "");
        if (stem.length() < 3) {
            return null; // too short to discriminate a table name
        }
        JdbcTemplate jdbc = connectionService.getJdbcTemplateForBackgroundJob(connectionId);

        // every column of every table whose name contains the stem
        // (customer -> customer, customers, hotel_details, sf_hotels, ...)
        List<Map<String, Object>> cols = jdbc.queryForList(
            "SELECT table_name, column_name FROM information_schema.columns "
                + "WHERE table_schema NOT IN (" + SYSTEM_SCHEMAS + ") "
                + "AND LOWER(table_name) LIKE ?",
            "%" + stem + "%");
        if (cols.isEmpty()) {
            return null;
        }

        Map<String, Set<String>> byTable = new LinkedHashMap<>();
        for (Map<String, Object> row : cols) {
            String table = String.valueOf(row.get("table_name"));
            String column = String.valueOf(row.get("column_name")).toLowerCase(Locale.ROOT);
            byTable.computeIfAbsent(table, k -> new HashSet<>()).add(column);
        }

        String tenantLc = tenantColumn.toLowerCase(Locale.ROOT);
        List<NameLookup> candidates = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : byTable.entrySet()) {
            Set<String> columns = entry.getValue();
            String idCol = firstPresent(columns,
                List.of("id", tenantLc, stem + "_id", stem + "id"));
            String nameCol = firstPresent(columns, NAME_COLUMN_CANDIDATES);
            if (nameCol == null) {
                nameCol = firstPresent(columns, List.of(stem + "_name", stem + "name"));
            }
            if (idCol == null || nameCol == null) {
                continue;
            }
            candidates.add(new NameLookup(entry.getKey(), idCol, nameCol));
        }
        if (candidates.isEmpty()) {
            return null;
        }

        // Two-tier ranking:
        //   1. Tables that actually have rows beat empty ones.
        //   2. Among non-empty (or all-empty) candidates, prefer the shortest
        //      name — the canonical entity table (`orders`) over a wider child
        //      (`order_line_details`).
        //
        // The row-presence probe is what fixes the case-sensitive duplicates
        // bug: on MySQL with lower_case_table_names=0, schemas can carry both
        // `ORDERS` (the real, populated entity table) and a leftover empty
        // `orders`. The old "shortest first" tiebreaker would pick whichever
        // appeared first in information_schema's iteration order — if it
        // happened to land on the empty one, every customer name lookup
        // returned NULL and the UI fell back to bare numeric IDs.
        candidates.sort(Comparator
            .<NameLookup, Boolean>comparing(c -> hasRows(jdbc, c.table()), Comparator.reverseOrder())
            .thenComparingInt(c -> c.table().length()));
        return candidates.get(0);
    }

    /**
     * Cheap "is this table empty?" probe. Returns true if a single-row LIMIT 1
     * query returns at least one row. Defensive — any failure (missing table,
     * permission denied, syntax error) is treated as "no rows" so a broken
     * candidate sorts below working ones without breaking the whole lookup.
     */
    private static boolean hasRows(JdbcTemplate jdbc, String tableName) {
        if (tableName == null || tableName.isBlank()) {
            return false;
        }
        try {
            Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM (SELECT 1 FROM " + tableName + " LIMIT 1) AS probe",
                Integer.class);
            return count != null && count > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String firstPresent(Set<String> have, List<String> candidates) {
        for (String c : candidates) {
            if (c != null && have.contains(c.toLowerCase(Locale.ROOT))) {
                return c;
            }
        }
        return null;
    }

    /**
     * The config the rest of the system should act on. If the connection has a
     * saved tenant column, return it as-is. Otherwise auto-detect a proposal
     * from the schema (top-ranked tenant column + matching name lookup) and
     * return that with {@code autoDetected = true}. Read-only — the proposal is
     * not persisted here; {@link #ensureAutoConfig} does that.
     */
    public ConnectionAnalyticsConfig effectiveConfig(String connectionId) {
        ConnectionAnalyticsConfig saved = configRepository.findById(connectionId).orElse(null);
        if (saved != null && saved.hasTenantColumn()) {
            saved.setAutoDetected(false);
            return saved;
        }

        ConnectionAnalyticsConfig proposal = ConnectionAnalyticsConfig.builder()
            .connectionId(connectionId)
            .dailyAnalysisEnabled(saved == null || saved.isDailyAnalysisEnabled())
            .build();
        try {
            List<TenantColumnSuggestion> suggestions = suggestTenantColumns(connectionId);
            if (!suggestions.isEmpty()) {
                String tenantColumn = suggestions.get(0).columnName();
                proposal.setTenantColumn(tenantColumn);
                NameLookup lookup = autoDetectNameLookup(connectionId, tenantColumn);
                if (lookup != null) {
                    proposal.setCustomerLookupTable(lookup.table());
                    proposal.setCustomerLookupIdCol(lookup.idCol());
                    proposal.setCustomerLookupNameCol(lookup.nameCol());
                }
                proposal.setAutoDetected(true);
            }
        } catch (Exception e) {
            log.warn("Auto-detect of analytics config failed for {}: {}", connectionId, e.getMessage());
        }
        return proposal;
    }

    // ── full reset ────────────────────────────────────────────────────────────

    /**
     * Wipe every slow-query analytics row for a connection and reset the
     * ingestion cursor so the next run re-reads from the beginning.
     *
     * Cleared tables:
     *   slow_query_run, slow_query_sample, slow_query_customer,
     *   slow_query_customer_day, slow_query_history, query_fingerprints
     *
     * Preserved (user config — intentionally not touched):
     *   slow_log_source_config, connection_analytics_config
     *
     * The slow_log_source_config.last_processed_at is set to null so that
     * a subsequent "SINCE_LAST" ingestion re-reads from the very beginning
     * rather than picking up only new events.
     */

    /**
     * True when the connection has a slow-log source row saved (regardless of
     * whether it's been ingested from yet). Used by the controllers to
     * distinguish "no log source configured" from "no new events" when
     * analyze-now returns nothing.
     */
    public boolean hasLogSource(String connectionId) {
        return sourceConfigRepository.findByConnectionId(connectionId).isPresent();
    }

    @Transactional
    public void resetAnalytics(String connectionId) {
        log.info("Resetting slow-query analytics for connection {}", connectionId);

        int runs     = runRepository.deleteByConnectionId(connectionId);
        int samples  = sampleRepository.deleteByConnectionId(connectionId);
        customerDayRepository.deleteByConnectionId(connectionId);
        customerRepository.deleteByConnectionId(connectionId);
        fingerprintRepository.deleteByConnectionId(connectionId);
        historyRepository.deleteByConnectionId(connectionId);

        // Reset the ingestion cursor so the next "SINCE_LAST" run re-reads
        // from the beginning instead of skipping already-seen events.
        sourceConfigRepository.findByConnectionId(connectionId).ifPresent(cfg -> {
            cfg.setLastProcessedAt(null);
            sourceConfigRepository.save(cfg);
        });

        log.info("Reset complete for {}: deleted {} run rows, {} sample rows", connectionId, runs, samples);
    }

    /**
     * Persist an auto-detected config if the connection doesn't have a tenant
     * column yet. Called from the analysis path so the very first run enriches
     * the connection automatically — no manual setup required.
     */
    @Transactional
    public void ensureAutoConfig(String connectionId) {
        ConnectionAnalyticsConfig saved = configRepository.findById(connectionId).orElse(null);
        if (saved != null && saved.hasTenantColumn()) {
            return;
        }
        ConnectionAnalyticsConfig detected = effectiveConfig(connectionId);
        if (detected.hasTenantColumn()) {
            configRepository.save(detected);
            log.info("Auto-detected tenant column '{}' (lookup {}.{}/{}) for connection {}",
                detected.getTenantColumn(), detected.getCustomerLookupTable(),
                detected.getCustomerLookupIdCol(), detected.getCustomerLookupNameCol(), connectionId);
        }
    }
}
