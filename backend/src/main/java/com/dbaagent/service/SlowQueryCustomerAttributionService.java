package com.dbaagent.service;

import com.dbaagent.model.*;
import com.dbaagent.repository.ConnectionAnalyticsConfigRepository;
import com.dbaagent.repository.SlowQueryCustomerDayRepository;
import com.dbaagent.repository.SlowQueryCustomerRepository;
import com.dbaagent.repository.SlowQuerySampleRepository;
import com.dbaagent.util.QueryNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Attributes slow queries to a customer/tenant and rolls the result up into a
 * per-query × per-customer × per-day series.
 *
 * <p>pg_stat_statements strips literals, so a query's customer can only be
 * recovered from a source that keeps them — here, the slow query log. Each
 * literal-bearing query is written to {@code slow_query_sample} with the
 * tenant id pulled from the connection's configured tenant column (e.g.
 * {@code customer_id}). The tenant id is later resolved to a human-readable name
 * by a cheap lookup against the customer's own database.
 *
 * <p><b>v1 coverage caveat.</b> The slow-log parser aggregates by query digest
 * before this service sees the data, so one ingestion yields roughly one
 * literal-bearing example per distinct query — best-effort per-customer
 * coverage rather than a full per-execution census. Capturing every physical
 * slow-log line is a follow-up that taps the parser's per-line path.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SlowQueryCustomerAttributionService {

    /** SQL identifier guard — config-supplied table/column names are interpolated, not bound. */
    private static final Pattern SAFE_IDENTIFIER =
        Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?$");

    private static final int MAX_RAW_SQL = 10_000;

    private final ConnectionAnalyticsConfigRepository configRepository;
    private final SlowQueryCustomerRepository customerRepository;
    private final SlowQuerySampleRepository sampleRepository;
    private final SlowQueryCustomerDayRepository customerDayRepository;
    private final EnhancedSqlParserService sqlParserService;
    private final ConnectionService connectionService;

    // ── 1. attribution: literal-bearing queries → slow_query_sample ──────────

    /**
     * Write one {@code slow_query_sample} per literal-bearing slow query, with
     * the tenant id extracted from the configured tenant column.
     *
     * Called from two paths: slow-log ingestion ({@code SLOW_LOG}) and the
     * daily/on-demand analysis ({@code PERF_SCHEMA_SAMPLE}) — the latter relies
     * on the {@code sampleQuery} field, which MySQL's performance_schema
     * populates with a real literal-bearing example per digest.
     */
    public void attributeSamples(String connectionId, SlowQueryAnalysis analysis,
                                 SlowQuerySample.Source source) {
        if (analysis == null || analysis.getTopSlowQueries() == null) {
            return;
        }
        // A literal-bearing sample is worth storing for reproduction even when
        // no tenant column is configured: `slow-queries samples` is a general
        // "show me a runnable execution" feature, not a customer-only one.
        // Customer attribution (customerId + per-customer rollup) is layered on
        // top ONLY when a tenant column exists. Gating sample writes on the
        // tenant column is what left `samples` empty for connections without
        // one, even though literals were available.
        ConnectionAnalyticsConfig cfg = configRepository.findById(connectionId).orElse(null);
        String tenantColumn = (cfg != null && cfg.hasTenantColumn()) ? cfg.getTenantColumn() : null;

        int written = 0;
        int attributed = 0;
        for (SlowQuery q : analysis.getTopSlowQueries()) {
            // Store only rows that actually carry literals. sampleQuery is set by
            // the literal-bearing sources (slow-log substitution, perf_schema
            // sample); a null/blank sample (e.g. a live pg_stat_statements read
            // with only $N placeholders) has nothing reproducible to offer.
            String rawSql = q.getSampleQuery();
            if (rawSql == null || rawSql.isBlank()) {
                continue;
            }
            try {
                // The fingerprint MUST match slow_query_run, which keys on the
                // canonical 16-char fingerprint of the normalized queryText
                // (via QueryFingerprintService). Computing it the same way here
                // is what lets `samples`/timeline join the right query.
                String fpSource = q.getQueryText() != null && !q.getQueryText().isBlank()
                    ? q.getQueryText() : rawSql;
                String fingerprint = QueryFingerprintService.computeCanonicalFingerprint(
                    QueryNormalizer.normalize(fpSource));
                if (fingerprint == null) {
                    continue;
                }
                String customerId = tenantColumn != null
                    ? extractTenantValue(rawSql, tenantColumn) : null;

                SlowQuerySample sample = SlowQuerySample.builder()
                    .connectionId(connectionId)
                    .fingerprint(fingerprint)
                    .customerId(customerId)
                    .rawSql(truncate(rawSql, MAX_RAW_SQL))
                    .execMs(q.getAvgExecutionTimeMs())
                    .rowsExamined(q.getRowsExamined())
                    .rowsSent(q.getRowsSent())
                    .capturedAt(q.getLastSeen() != null ? q.getLastSeen() : LocalDateTime.now())
                    .source(source)
                    .build();
                sampleRepository.save(sample);
                written++;

                if (customerId != null) {
                    attributed++;
                    touchCustomer(connectionId, customerId, tenantColumn);
                }
            } catch (Exception e) {
                log.debug("Sample capture skipped for one query on {}: {}",
                    connectionId, e.getMessage());
            }
        }
        if (written > 0) {
            log.info("Slow-query samples for {}: {} stored, {} with a tenant id",
                connectionId, written, attributed);
        }
    }

    /**
     * Pull the tenant column's literal out of a query's WHERE clause. Returns
     * null when the column isn't filtered, the value is parameterized, or the
     * query filters more than one distinct tenant (a cross-tenant query can't
     * be attributed to a single customer).
     */
    private String extractTenantValue(String rawSql, String tenantColumn) {
        List<EnhancedSqlParserService.ColumnLiteralPair> literals =
            sqlParserService.extractWhereClauseLiterals(rawSql);
        Set<String> distinct = new HashSet<>();
        for (EnhancedSqlParserService.ColumnLiteralPair pair : literals) {
            if (pair.getColumnName() != null
                && tenantColumn.equalsIgnoreCase(pair.getColumnName())
                && pair.getValue() != null
                && !pair.getValue().isBlank()) {
                distinct.add(pair.getValue().trim());
            }
        }
        return distinct.size() == 1 ? distinct.iterator().next() : null;
    }

    /** Find-or-create the customer dimension row and bump its last-seen timestamp. */
    @Transactional
    public void touchCustomer(String connectionId, String customerId, String tenantColumn) {
        SlowQueryCustomer customer = customerRepository
            .findByConnectionIdAndCustomerId(connectionId, customerId)
            .orElseGet(() -> SlowQueryCustomer.builder()
                .connectionId(connectionId)
                .customerId(customerId)
                .tenantColumn(tenantColumn)
                .build());
        customer.setLastSeenAt(LocalDateTime.now());
        customerRepository.save(customer);
    }

    // ── 2. name resolution: tenant id → customer name (target-DB lookup) ─────

    /**
     * Resolve names for every customer on a connection that doesn't have one
     * yet, by running {@code SELECT <nameCol> FROM <table> WHERE <idCol> = ?}
     * against the customer's own database. Each row is marked resolved even if
     * the lookup found nothing, so we don't re-query indefinitely.
     */
    public void resolvePendingCustomerNames(String connectionId) {
        ConnectionAnalyticsConfig cfg = configRepository.findById(connectionId).orElse(null);
        if (cfg == null || !cfg.hasNameLookup()) {
            return;
        }
        String table = cfg.getCustomerLookupTable();
        String idCol = cfg.getCustomerLookupIdCol();
        String nameCol = cfg.getCustomerLookupNameCol();
        if (!isSafeIdentifier(table) || !isSafeIdentifier(idCol) || !isSafeIdentifier(nameCol)) {
            log.warn("Skipping customer-name lookup for {}: config has an unsafe identifier "
                + "(table={}, idCol={}, nameCol={})", connectionId, table, idCol, nameCol);
            return;
        }

        List<SlowQueryCustomer> pending =
            customerRepository.findByConnectionIdAndNameResolvedAtIsNull(connectionId);
        if (pending.isEmpty()) {
            return;
        }

        JdbcTemplate jdbc;
        try {
            jdbc = connectionService.getJdbcTemplateForBackgroundJob(connectionId);
        } catch (Exception e) {
            log.warn("Customer-name lookup unavailable for {}: {}", connectionId, e.getMessage());
            return;
        }

        // Identifiers are validated above; the id VALUE is always bound as a parameter.
        String sql = "SELECT " + nameCol + " FROM " + table + " WHERE " + idCol + " = ?";
        int resolved = 0;
        for (SlowQueryCustomer customer : pending) {
            String name = null;
            try {
                name = jdbc.queryForObject(sql, String.class, customer.getCustomerId());
            } catch (org.springframework.dao.EmptyResultDataAccessException notFound) {
                // No matching row — leave name null but still mark resolved.
            } catch (Exception e) {
                log.debug("Name lookup failed for customer {} on {}: {}",
                    customer.getCustomerId(), connectionId, e.getMessage());
                continue; // transient — retry on the next cycle
            }
            customer.setCustomerName(name);
            customer.setNameResolvedAt(LocalDateTime.now());
            customerRepository.save(customer);
            resolved++;
        }
        if (resolved > 0) {
            log.info("Resolved {} customer name(s) for connection {}", resolved, connectionId);
        }
    }

    // ── 3. rollup: slow_query_sample → slow_query_customer_day ───────────────

    /**
     * Aggregate one day's samples into {@code slow_query_customer_day}, one row
     * per (query, customer), with a regression factor versus the previous day.
     * Upserts on the natural key so re-running the rollup is idempotent.
     */
    @Transactional
    public void rollupCustomerDays(String connectionId, LocalDate day) {
        LocalDateTime from = day.atStartOfDay();
        LocalDateTime to = day.plusDays(1).atStartOfDay();
        List<SlowQuerySample> samples =
            sampleRepository.findByConnectionIdAndCapturedAtBetween(connectionId, from, to);
        if (samples.isEmpty()) {
            return;
        }

        // group by (fingerprint, customerId); samples without a customer are skipped
        Map<String, List<SlowQuerySample>> groups = new HashMap<>();
        for (SlowQuerySample s : samples) {
            if (s.getCustomerId() == null) {
                continue;
            }
            groups.computeIfAbsent(s.getFingerprint() + " " + s.getCustomerId(),
                k -> new ArrayList<>()).add(s);
        }

        int rows = 0;
        for (List<SlowQuerySample> group : groups.values()) {
            SlowQuerySample first = group.get(0);
            String fingerprint = first.getFingerprint();
            String customerId = first.getCustomerId();

            DoubleSummaryStatistics stats = group.stream()
                .map(SlowQuerySample::getExecMs)
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .summaryStatistics();

            Double meanMs = stats.getCount() > 0 ? stats.getAverage() : null;
            Double maxMs = stats.getCount() > 0 ? stats.getMax() : null;
            Double totalMs = stats.getCount() > 0 ? stats.getSum() : null;

            Double prevMean = customerDayRepository
                .findFirstByConnectionIdAndFingerprintAndCustomerIdAndDayLessThanOrderByDayDesc(
                    connectionId, fingerprint, customerId, day)
                .map(SlowQueryCustomerDay::getMeanExecMs)
                .orElse(null);
            Double regression = (meanMs != null && prevMean != null && prevMean > 0)
                ? meanMs / prevMean
                : null;

            SlowQueryCustomerDay row = customerDayRepository
                .findByConnectionIdAndFingerprintAndCustomerIdAndDay(
                    connectionId, fingerprint, customerId, day)
                .orElseGet(SlowQueryCustomerDay::new);
            row.setConnectionId(connectionId);
            row.setFingerprint(fingerprint);
            row.setCustomerId(customerId);
            row.setDay(day);
            row.setSampleCount(group.size());
            row.setMeanExecMs(meanMs);
            row.setMaxExecMs(maxMs);
            row.setTotalExecMs(totalMs);
            row.setPrevDayMeanMs(prevMean);
            row.setRegressionFactor(regression);
            customerDayRepository.save(row);
            rows++;
        }
        if (rows > 0) {
            log.info("Rolled up {} customer-day rows for connection {} on {}", rows, connectionId, day);
        }
    }

    private static boolean isSafeIdentifier(String identifier) {
        return identifier != null && SAFE_IDENTIFIER.matcher(identifier).matches();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
