package com.dbaagent.service;

import com.dbaagent.model.ConnectionRequest;
import com.dbaagent.model.QueryFingerprint;
import com.dbaagent.model.QueryOptimizationCandidateRun;
import com.dbaagent.provider.DatabaseProviderRegistry;
import com.dbaagent.repository.QueryFingerprintRepository;
import com.dbaagent.util.QueryNormalizer;
import com.dbaagent.util.SqlLiteralSubstitution;
import com.dbaagent.util.SqlStatementSplitter;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class OptimizationBenchmarkService {

    private static final int DEFAULT_RUNS = 3;
    private static final int DEFAULT_TIMEOUT_MS = 30_000;
    private static final int MAX_TIMEOUT_MS = 600_000; // 10 minutes
    /** Threshold (ms) above which we reduce to a single run and skip row count. */
    private static final double SLOW_QUERY_THRESHOLD_MS = 10_000;
    private static final Pattern PG_PARAM_PATTERN = Pattern.compile("\\$\\d+");
    /** Matches date literals in both dd-mm-yyyy and yyyy-mm-dd (ISO) formats. Word boundaries prevent matching inside larger tokens. */
    private static final Pattern DATE_PATTERN = Pattern.compile("\\b(\\d{4}-\\d{2}-\\d{2}|\\d{2}-\\d{2}-\\d{4})\\b");
    private static final DateTimeFormatter DD_MM_YYYY_FMT = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    private final OptimizationCandidateService candidateService;
    private final CredentialService credentialService;
    private final ConnectionService connectionService;
    private final DatabaseProviderRegistry providerRegistry;
    private final SchemaScannerService schemaScannerService;
    private final ObjectMapper objectMapper;
    private final QueryOptimizationService optimizationService;
    private final QueryFingerprintRepository fingerprintRepository;
    /** App's own PostgreSQL DataSource — used for distributed advisory locks. */
    private final DataSource dataSource;

    /**
     * Quick single-run benchmark for a SQL query. Returns execution time in ms.
     * Used by the streaming optimization pipeline to compare original vs rewrite performance.
     * Does NOT use advisory locks — intended for one-off comparison, not full candidate tracking.
     *
     * @param connectionId    target connection
     * @param sql             SQL to benchmark (may include SET preamble)
     * @param timeoutMs       wall-clock timeout in milliseconds
     * @return                execution time in ms, or Double.MAX_VALUE on error
     */
    public double quickBenchmark(String connectionId, String sql, int timeoutMs) {
        try {
            ConnectionRequest connection = credentialService.getDecryptedConnection(connectionId);
            String dbType = providerRegistry.getCanonicalName(connection.getDbType());
            return runBenchmark(connectionId, connection, dbType, sql, timeoutMs);
        } catch (Exception e) {
            log.warn("Quick benchmark failed for connection {}: {}", connectionId, e.getMessage());
            return Double.MAX_VALUE;
        }
    }

    /**
     * Count the number of rows returned by a SQL query.
     * Used by the streaming optimization pipeline to compare result-set sizes between
     * the original query and a rewrite — ensuring functional equivalence before accepting
     * the rewrite.
     *
     * Capped at ROW_COUNT_LIMIT (1,000,000) to avoid unbounded fetches.
     * Returns -1 on any error (caller should treat as "count unavailable").
     *
     * @param connectionId  target connection
     * @param sql           SQL to count (may include SET preamble)
     * @param timeoutMs     wall-clock timeout in milliseconds
     */
    public long quickCountRows(String connectionId, String sql, int timeoutMs) {
        try {
            ConnectionRequest connection = credentialService.getDecryptedConnection(connectionId);
            String dbType = providerRegistry.getCanonicalName(connection.getDbType());
            return countRows(connectionId, connection, dbType, sql, timeoutMs);
        } catch (Exception e) {
            log.warn("Quick row-count failed for connection {}: {}", connectionId, e.getMessage());
            return -1;
        }
    }

    /**
     * Benchmark candidates with distributed mutual exclusion via PostgreSQL advisory lock.
     * NOT @Transactional — avoids pinning an app DB connection/transaction for the entire
     * benchmark duration (which can take minutes). Inner operations (getCandidates, saveAll)
     * manage their own short transactions.
     *
     * A session-level advisory lock on a dedicated connection prevents concurrent benchmarks
     * for the same fingerprint across multiple JVM instances. The lock connection is held
     * (not returned to pool) while the benchmark runs, then explicitly unlocked and returned.
     */
    public List<QueryOptimizationCandidateRun> benchmarkCandidates(
        String connectionId,
        String queryFingerprint,
        Integer runs,
        Integer timeoutMs
    ) {
        String lockKey = connectionId + ":" + queryFingerprint;
        long lockId = advisoryLockId(lockKey);

        Connection lockConn = null;
        boolean lockAcquired = false;
        try {
            lockConn = dataSource.getConnection();
            // Session-level lock on dedicated connection (auto-commit mode, no transaction overhead).
            // The lock persists until explicitly unlocked or the session (connection) ends.
            try (Statement stmt = lockConn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT pg_try_advisory_lock(" + lockId + ")")) {
                lockAcquired = rs.next() && rs.getBoolean(1);
            }
            if (!lockAcquired) {
                log.warn("Benchmark already in progress for {} (advisory lock held), returning current candidates", lockKey);
                return candidateService.getCandidates(connectionId, queryFingerprint);
            }

            return doBenchmarkCandidates(connectionId, queryFingerprint, runs, timeoutMs);
        } catch (java.sql.SQLException e) {
            log.error("Failed to acquire advisory lock for benchmark {}: {}", lockKey, e.getMessage());
            // Fail closed: return existing candidates rather than running unlocked
            return candidateService.getCandidates(connectionId, queryFingerprint);
        } finally {
            releaseLockConnection(lockConn, lockId, lockAcquired);
        }
    }

    /** Explicitly unlock the advisory lock and return the connection to pool. */
    private void releaseLockConnection(Connection conn, long lockId, boolean wasAcquired) {
        if (conn == null) return;
        try {
            if (wasAcquired) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("SELECT pg_advisory_unlock(" + lockId + ")");
                }
            }
        } catch (java.sql.SQLException e) {
            log.warn("Failed to release advisory lock {}: {}", lockId, e.getMessage());
        } finally {
            try { conn.close(); } catch (java.sql.SQLException ignored) { }
        }
    }

    /**
     * Compute a stable 64-bit lock ID from a string key using SHA-256.
     * Uses the bigint variant of pg_try_advisory_lock for 2^64 namespace,
     * virtually eliminating hash collisions between fingerprints.
     */
    private static long advisoryLockId(String key) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(key.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(hash, 0, 8).getLong();
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-256 is mandatory in all Java implementations — can't happen
            return key.hashCode();
        }
    }

    private List<QueryOptimizationCandidateRun> doBenchmarkCandidates(
        String connectionId,
        String queryFingerprint,
        Integer runs,
        Integer timeoutMs
    ) {
        int runCount = runs != null && runs > 0 ? runs : DEFAULT_RUNS;
        int timeout = timeoutMs != null && timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;

        // Tiered timeout: scale based on known query execution time when caller didn't specify.
        // Uses findFingerprintEntry() which resolves non-canonical fingerprints (64-char PS digest,
        // 32-char MD5) to canonical 16-char format for query_fingerprints table lookup.
        if (timeoutMs == null || timeoutMs <= 0) {
            Double avgMs = findFingerprintEntry(connectionId, queryFingerprint)
                .map(QueryFingerprint::getCurrentAvgTimeMs)
                .orElse(null);
            if (avgMs != null && avgMs > 0) {
                int tiered = (int) Math.round(avgMs * 2);
                timeout = Math.min(MAX_TIMEOUT_MS, Math.max(DEFAULT_TIMEOUT_MS, tiered));
                log.info("Tiered timeout for fingerprint {}: avgMs={}, timeout={}ms",
                    queryFingerprint, avgMs, timeout);
            }
        }

        List<QueryOptimizationCandidateRun> candidates = candidateService.getCandidates(connectionId, queryFingerprint);
        if (candidates.isEmpty()) {
            // Ensure candidates are created from cache/fingerprint data before giving up
            optimizationService.ensureOptimizationCandidates(connectionId, queryFingerprint);
            candidates = candidateService.getCandidates(connectionId, queryFingerprint);
        }
        if (candidates.isEmpty()) {
            return Collections.emptyList();
        }

        // Refresh stale ORIGINAL candidate if fingerprint sampleQuery has newer literals
        refreshStaleCandidates(connectionId, queryFingerprint, candidates);

        ConnectionRequest connection = credentialService.getDecryptedConnection(connectionId);
        String dbType = providerRegistry.getCanonicalName(connection.getDbType());
        boolean isMysql = "mysql".equalsIgnoreCase(dbType);
        com.dbaagent.model.SchemaMetadata schemaMetadata = null;
        if (isMysql) {
            try {
                schemaMetadata = schemaScannerService.scanSchema(connectionId);
            } catch (Exception e) {
                log.debug("Could not load schema metadata for case alignment: {}", e.getMessage());
            }
        }

        // Find the ORIGINAL candidate's SQL to use as a literal source for
        // parameterized candidates whose applySampleLiterals failed at creation time.
        String originalSql = findOriginalCandidateSql(candidates);

        List<QueryOptimizationCandidateRun> updated = new ArrayList<>();
        for (QueryOptimizationCandidateRun candidate : candidates) {
            String sql = candidate.getCandidateSql();
            if (isMysql && schemaMetadata != null && sql != null && !sql.isBlank()) {
                String aligned = com.dbaagent.util.SqlIdentifierCaseAligner
                    .alignTableIdentifiers(sql, schemaMetadata);
                if (aligned != null && !aligned.isBlank() && !aligned.equals(sql)) {
                    sql = aligned;
                    // Don't persist aligned casing — keep the original query text in DB.
                    // Alignment is only for the benchmark execution against this specific schema.
                }
            }

            // If candidate has placeholders, try to literalize from ORIGINAL's SQL
            if (!isBenchmarkable(sql) && SqlLiteralSubstitution.hasPlaceholders(sql) && originalSql != null) {
                String literalized = SqlLiteralSubstitution.substituteLiterals(sql, originalSql);
                if (!literalized.equals(sql) && isBenchmarkable(literalized)) {
                    log.info("Literalized {} candidate SQL from ORIGINAL for benchmarking", candidate.getCandidateId());
                    sql = literalized;
                    // Don't persist the literalized version — keep the parameterized form
                    // in the database. Only use literals for the actual benchmark run.
                }
            }

            if (!isBenchmarkable(sql)) {
                candidate.setStatus("SKIPPED");
                candidate.setErrorMessage("Query is not benchmarkable (non-SELECT or contains placeholders)");
                candidate.setUpdatedAt(LocalDateTime.now());
                updated.add(candidate);
                continue;
            }

            List<Double> timings = new ArrayList<>();
            String error = null;

            for (int i = 0; i < runCount; i++) {
                try {
                    double timeMs = runBenchmark(connectionId, connection, dbType, sql, timeout);
                    timings.add(timeMs);
                    // Adaptive run reduction: if first run is slow, skip remaining runs
                    if (i == 0 && timeMs > SLOW_QUERY_THRESHOLD_MS && runCount > 1) {
                        log.info("Adaptive run reduction for {} — first run took {}ms (>{} ms), skipping remaining {} runs",
                            candidate.getCandidateId(), timeMs, SLOW_QUERY_THRESHOLD_MS, runCount - 1);
                        break;
                    }
                } catch (Exception e) {
                    error = e.getMessage();
                    log.debug("Benchmark failed for candidate {}: {}", candidate.getCandidateId(), e.getMessage());
                    break;
                }
            }

            if (!timings.isEmpty()) {
                double median = median(timings);
                candidate.setBenchmarkMs(timings.get(0));
                candidate.setMedianMs(median);
                candidate.setRunCount(timings.size());
                candidate.setStatus("SUCCESS");
                candidate.setErrorMessage(null);

                // Capture actual row count for correctness validation.
                // Skip for slow queries — the extra execution is too expensive.
                if (median <= SLOW_QUERY_THRESHOLD_MS) {
                    try {
                        long rowCount = countRows(connectionId, connection, dbType, sql, timeout);
                        candidate.setRowCountResult(rowCount);
                    } catch (Exception e) {
                        log.debug("Row count capture failed for {}: {}", candidate.getCandidateId(), e.getMessage());
                    }
                } else {
                    log.info("Skipping row count for {} — median {}ms exceeds slow query threshold",
                        candidate.getCandidateId(), median);
                }
            } else {
                candidate.setStatus("FAILED");
                candidate.setErrorMessage(error != null ? error : "Benchmark failed");
            }

            candidate.setUpdatedAt(LocalDateTime.now());
            updated.add(candidate);
        }

        // Proactive date modernization: if ORIGINAL returned 0 rows and has date literals,
        // the dates are likely stale (from historical slow query logs). Shift the date window
        // to a recent range and re-benchmark all candidates so timing reflects real-world data volume.
        modernizeStaleDatesAndRebenchmark(updated, connectionId, connection, dbType, runCount, timeout, queryFingerprint);

        // Auto-retry: if AI_REWRITE failed with a SQL error, re-prompt AI with the error for correction
        retryFailedRewrites(updated, connectionId, queryFingerprint, connection, dbType, runCount, timeout);

        // Compare row counts: flag mismatch between ORIGINAL and AI_REWRITE
        flagRowCountMismatch(updated);

        return candidateService.saveAll(updated);
    }

    /**
     * Find the ORIGINAL candidate's SQL (which uses sampleQuery with real literals).
     */
    private String findOriginalCandidateSql(List<QueryOptimizationCandidateRun> candidates) {
        for (QueryOptimizationCandidateRun c : candidates) {
            if ("ORIGINAL".equals(c.getCandidateId()) && c.getCandidateSql() != null && !c.getCandidateSql().isBlank()) {
                return c.getCandidateSql();
            }
        }
        return null;
    }

    private boolean isBenchmarkable(String sql) {
        if (sql == null || sql.isBlank()) {
            return false;
        }
        String type = QueryNormalizer.detectQueryType(sql);
        if (!"SELECT".equals(type)) {
            return false;
        }
        if (sql.contains("?")) {
            return false;
        }
        return !PG_PARAM_PATTERN.matcher(sql).find();
    }

    /**
     * Split SQL into SET preamble statements and the main SELECT statement.
     * Delegates to SqlStatementSplitter which handles all SET forms
     * (SET @var, SET SESSION, SET @@session, SET NAMES) and respects
     * string literals, comments, and quoted identifiers.
     *
     * @param dbType "mysql" or "postgres" — controls whether # is a comment character
     */
    private String[] splitSetPreamble(String sql, String dbType) {
        boolean hashIsComment = !"postgres".equalsIgnoreCase(dbType);
        return SqlStatementSplitter.splitSetPreamble(sql, hashIsComment);
    }

    private double runBenchmark(String connectionId, ConnectionRequest connection, String dbType, String sql, int timeoutMs) throws Exception {
        try (Connection conn = connectionService.getConnection(connectionId, connection);
             Statement stmt = conn.createStatement()) {
            stmt.setQueryTimeout(Math.max(1, timeoutMs / 1000));

            // Split SET preamble from SELECT and execute SET statements first
            String[] parts = splitSetPreamble(sql, dbType);
            String selectSql = parts[parts.length - 1];

            for (int i = 0; i < parts.length - 1; i++) {
                stmt.execute(parts[i]);
            }

            try {
                if ("postgres".equals(dbType)) {
                    stmt.execute("SET statement_timeout = " + timeoutMs);
                    String explainSql = "EXPLAIN (ANALYZE, FORMAT JSON) " + selectSql;
                    long start = System.nanoTime();
                    try (ResultSet rs = stmt.executeQuery(explainSql)) {
                        if (rs.next()) {
                            String json = rs.getString(1);
                            Double execTime = parsePostgresExecutionTime(json);
                            if (execTime != null) {
                                return execTime;
                            }
                        }
                    }
                    return nanosToMs(System.nanoTime() - start);
                }

                if ("mysql".equals(dbType)) {
                    stmt.execute("SET SESSION max_execution_time = " + timeoutMs);
                    String explainSql = "EXPLAIN ANALYZE " + selectSql;
                    long start = System.nanoTime();
                    try {
                        stmt.executeQuery(explainSql);
                    } catch (Exception e) {
                        // Fallback: run query directly if EXPLAIN ANALYZE unsupported
                        stmt.executeQuery(selectSql);
                    }
                    return nanosToMs(System.nanoTime() - start);
                }

                long start = System.nanoTime();
                stmt.executeQuery(selectSql);
                return nanosToMs(System.nanoTime() - start);
            } finally {
                // Reset session-level timeouts before returning connection to pool
                resetSessionTimeout(stmt, dbType);
            }
        }
    }

    /**
     * Execute the candidate query and return the actual row count.
     * Wraps the query in SELECT COUNT(*) FROM (...) to avoid fetching all rows.
     */
    private static final int ROW_COUNT_LIMIT = 1_000_000;

    private long countRows(String connectionId, ConnectionRequest connection, String dbType, String sql, int timeoutMs) throws Exception {
        try (Connection conn = connectionService.getConnection(connectionId, connection);
             Statement stmt = conn.createStatement()) {
            stmt.setQueryTimeout(Math.max(1, timeoutMs / 1000));
            // Safety cap to avoid fetching unbounded results
            stmt.setMaxRows(ROW_COUNT_LIMIT + 1);

            String[] parts = splitSetPreamble(sql, dbType);
            String selectSql = parts[parts.length - 1];

            for (int i = 0; i < parts.length - 1; i++) {
                stmt.execute(parts[i]);
            }

            try {
                if ("mysql".equals(dbType)) {
                    stmt.execute("SET SESSION max_execution_time = " + timeoutMs);
                } else if ("postgres".equals(dbType)) {
                    stmt.execute("SET statement_timeout = " + timeoutMs);
                }

                // Strip trailing semicolons
                String cleanSelect = selectSql.trim();
                if (cleanSelect.endsWith(";")) {
                    cleanSelect = cleanSelect.substring(0, cleanSelect.length() - 1).trim();
                }

                // Execute the query directly and count rows from ResultSet.
                // Subquery wrapping (SELECT COUNT(*) FROM (...)) fails on MySQL when
                // the original query has duplicate column aliases (e.g., two 'total_count' columns).
                long count = 0;
                try (ResultSet rs = stmt.executeQuery(cleanSelect)) {
                    while (rs.next()) {
                        count++;
                    }
                }
                return count;
            } finally {
                // Reset session-level timeouts before returning connection to pool
                resetSessionTimeout(stmt, dbType);
            }
        }
    }

    /**
     * Reset session-level timeout to default (unlimited) before returning
     * the connection to HikariCP's pool. Without this, a 600s timeout
     * set for a slow benchmark would leak into the next unrelated query.
     */
    private void resetSessionTimeout(Statement stmt, String dbType) {
        try {
            if ("mysql".equals(dbType)) {
                stmt.execute("SET SESSION max_execution_time = 0");
            } else if ("postgres".equals(dbType)) {
                stmt.execute("SET statement_timeout = 0");
            }
        } catch (Exception e) {
            log.warn("Failed to reset session timeout: {}", e.getMessage());
        }
    }

    /**
     * Compare row counts between ORIGINAL and AI_REWRITE candidates.
     * If they differ, append a warning to the AI_REWRITE candidate.
     */
    private void flagRowCountMismatch(List<QueryOptimizationCandidateRun> candidates) {
        Long originalRows = null;
        QueryOptimizationCandidateRun rewriteCandidate = null;

        for (QueryOptimizationCandidateRun c : candidates) {
            if ("ORIGINAL".equals(c.getCandidateId()) && c.getRowCountResult() != null) {
                originalRows = c.getRowCountResult();
            }
            if ("AI_REWRITE".equals(c.getCandidateId())) {
                rewriteCandidate = c;
            }
        }

        if (originalRows == null || rewriteCandidate == null || rewriteCandidate.getRowCountResult() == null) {
            return;
        }

        long rewriteRows = rewriteCandidate.getRowCountResult();
        if (originalRows != rewriteRows) {
            String warning = String.format(
                "ROW_COUNT_MISMATCH: Original returned %d rows, rewrite returned %d rows. The rewritten query may have different semantics.",
                originalRows, rewriteRows
            );
            String existing = rewriteCandidate.getWarnings();
            rewriteCandidate.setWarnings(existing != null && !existing.isBlank()
                ? existing + "\n" + warning
                : warning);
            log.warn("Row count mismatch for {}: original={}, rewrite={}",
                rewriteCandidate.getQueryFingerprint(), originalRows, rewriteRows);
        }
    }

    // ── Proactive Date Modernization ─────────────────────────────────────

    private static final DateTimeFormatter ISO_DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * When the ORIGINAL candidate benchmarks successfully but returns 0 rows AND its SQL
     * contains date literals, the dates are almost certainly stale — captured from historical
     * slow query logs that ran days/weeks/months ago. This makes the benchmark timings
     * unreliable (scanning 0 rows is much faster than scanning real data).
     *
     * <p>Strategy: detect the date range span in ORIGINAL's SQL, shift the entire window
     * so the latest date is yesterday (maximizes chance of data existence), apply the same
     * shift to all candidates, and re-benchmark with modernized dates.</p>
     *
     * <p>Both ISO (yyyy-MM-dd) and European (dd-MM-yyyy) date formats are shifted.
     * The shift preserves the original date range span (e.g., a 7-day window stays 7 days,
     * just moved to recent dates). Modernized SQL is used only for benchmarking — the
     * original candidateSql is not mutated.</p>
     */
    private void modernizeStaleDatesAndRebenchmark(
        List<QueryOptimizationCandidateRun> candidates,
        String connectionId,
        ConnectionRequest connection,
        String dbType,
        int runCount,
        int timeout,
        String queryFingerprint
    ) {
        // Find the ORIGINAL candidate
        QueryOptimizationCandidateRun original = null;
        for (QueryOptimizationCandidateRun c : candidates) {
            if ("ORIGINAL".equals(c.getCandidateId())) {
                original = c;
                break;
            }
        }

        // Only modernize if ORIGINAL succeeded with 0 rows
        if (original == null || !"SUCCESS".equals(original.getStatus())
                || original.getRowCountResult() == null || original.getRowCountResult() > 0) {
            return;
        }

        String originalSql = original.getCandidateSql();
        if (originalSql == null || originalSql.isBlank()) return;

        // Extract and parse ISO date literals from the ORIGINAL SQL
        List<LocalDate> parsedDates = extractParsedDates(originalSql);
        if (parsedDates.isEmpty()) {
            log.debug("No parseable ISO dates in ORIGINAL SQL for {}, skipping modernization", queryFingerprint);
            return;
        }

        // Compute the shift: move the latest date to yesterday
        LocalDate latest = parsedDates.stream().max(LocalDate::compareTo).orElse(null);
        LocalDate yesterday = LocalDate.now().minusDays(1);
        if (latest == null || !latest.isBefore(yesterday)) {
            // Dates are already recent enough (latest date is yesterday or later)
            return;
        }

        long shiftDays = ChronoUnit.DAYS.between(latest, yesterday);
        log.info("Modernizing stale dates for {} — shifting by {} days (latest {} → {})",
            queryFingerprint, shiftDays, latest, yesterday);

        // Apply the date shift to ALL candidates and re-benchmark
        boolean anyModernized = false;
        for (QueryOptimizationCandidateRun candidate : candidates) {
            if (!"SUCCESS".equals(candidate.getStatus())) continue;
            String sql = candidate.getCandidateSql();
            if (sql == null || sql.isBlank()) continue;

            String modernized = shiftDatesInSql(sql, shiftDays);
            if (modernized.equals(sql)) continue;

            // Re-benchmark with modernized dates
            List<Double> timings = new ArrayList<>();
            String error = null;
            for (int i = 0; i < runCount; i++) {
                try {
                    double timeMs = runBenchmark(connectionId, connection, dbType, modernized, timeout);
                    timings.add(timeMs);
                    // Adaptive run reduction for slow queries
                    if (i == 0 && timeMs > SLOW_QUERY_THRESHOLD_MS && runCount > 1) {
                        log.info("Adaptive run reduction (modernized) for {}/{} — first run {}ms, skipping remaining runs",
                            queryFingerprint, candidate.getCandidateId(), timeMs);
                        break;
                    }
                } catch (Exception e) {
                    error = e.getMessage();
                    log.debug("Modernized benchmark failed for {}/{}: {}", queryFingerprint, candidate.getCandidateId(), e.getMessage());
                    break;
                }
            }

            if (!timings.isEmpty()) {
                double median = median(timings);
                candidate.setBenchmarkMs(timings.get(0));
                candidate.setMedianMs(median);
                candidate.setRunCount(timings.size());
                candidate.setStatus("SUCCESS");
                candidate.setErrorMessage(null);

                // Skip row count for slow queries
                if (median <= SLOW_QUERY_THRESHOLD_MS) {
                    try {
                        long rowCount = countRows(connectionId, connection, dbType, modernized, timeout);
                        candidate.setRowCountResult(rowCount);
                    } catch (Exception e) {
                        log.debug("Modernized row count failed for {}/{}: {}", queryFingerprint, candidate.getCandidateId(), e.getMessage());
                    }
                } else {
                    log.info("Skipping modernized row count for {}/{} — median {}ms exceeds threshold",
                        queryFingerprint, candidate.getCandidateId(), median);
                }

                // Don't persist the modernized SQL — keep the original/AI form in the database.
                // The date shift is only for benchmarking; the original SQL is needed for re-analysis.
                String dateWarning = String.format(
                    "DATE_MODERNIZED: Date literals shifted by %d days to match recent data. Original latest date: %s, shifted to: %s.",
                    shiftDays, latest, yesterday
                );
                String existing = candidate.getWarnings();
                candidate.setWarnings(existing != null && !existing.isBlank()
                    ? existing + "\n" + dateWarning
                    : dateWarning);
                candidate.setUpdatedAt(LocalDateTime.now());
                anyModernized = true;

                log.info("Modernized {} for {}: {}ms, {} rows",
                    candidate.getCandidateId(), queryFingerprint, timings.get(0), candidate.getRowCountResult());
            } else {
                // Modernized benchmark also failed — keep original results but note the attempt
                String note = "DATE_MODERNIZE_FAILED: Attempted to shift dates by " + shiftDays
                    + " days but benchmark failed" + (error != null ? ": " + error : "");
                String existing = candidate.getWarnings();
                candidate.setWarnings(existing != null && !existing.isBlank()
                    ? existing + "\n" + note
                    : note);
            }
        }

        if (anyModernized) {
            log.info("Date modernization complete for {} — re-benchmarked with recent dates", queryFingerprint);
        }
    }

    /**
     * Extract parseable ISO dates (yyyy-MM-dd) from SQL text.
     * Only returns dates that are valid calendar dates (rejects regex false positives).
     */
    private List<LocalDate> extractParsedDates(String sql) {
        List<LocalDate> dates = new ArrayList<>();
        Matcher matcher = DATE_PATTERN.matcher(sql);
        while (matcher.find()) {
            String dateStr = matcher.group(1);
            try {
                // Try ISO format first (yyyy-MM-dd)
                LocalDate parsed = LocalDate.parse(dateStr, ISO_DATE_FMT);
                dates.add(parsed);
            } catch (DateTimeParseException e) {
                // Could be dd-MM-yyyy format — try reversed
                try {
                    LocalDate parsed = LocalDate.parse(dateStr, DD_MM_YYYY_FMT);
                    dates.add(parsed);
                } catch (DateTimeParseException ignored) {
                    // Not a valid date, skip
                }
            }
        }
        return dates;
    }

    /**
     * Shift all date literals in SQL by the given number of days.
     * Handles both yyyy-MM-dd and dd-MM-yyyy formats. Uses two-pass replacement
     * (dates → placeholders → shifted dates) to avoid cascading substitutions.
     */
    private String shiftDatesInSql(String sql, long shiftDays) {
        // Collect all unique date matches and their shifted values
        Set<String> seen = new LinkedHashSet<>();
        Matcher matcher = DATE_PATTERN.matcher(sql);
        List<String[]> replacements = new ArrayList<>(); // [original, shifted]

        while (matcher.find()) {
            String dateStr = matcher.group(1);
            if (seen.contains(dateStr)) continue;
            seen.add(dateStr);

            try {
                LocalDate parsed = LocalDate.parse(dateStr, ISO_DATE_FMT);
                LocalDate shifted = parsed.plusDays(shiftDays);
                replacements.add(new String[]{dateStr, shifted.format(ISO_DATE_FMT)});
            } catch (DateTimeParseException e) {
                try {
                    LocalDate parsed = LocalDate.parse(dateStr, DD_MM_YYYY_FMT);
                    LocalDate shifted = parsed.plusDays(shiftDays);
                    replacements.add(new String[]{dateStr, shifted.format(DD_MM_YYYY_FMT)});
                } catch (DateTimeParseException ignored) {
                    // Not a valid date, skip
                }
            }
        }

        if (replacements.isEmpty()) return sql;

        // Two-pass substitution to avoid cascading replacements
        String result = sql;
        String[] placeholders = new String[replacements.size()];
        for (int i = 0; i < replacements.size(); i++) {
            placeholders[i] = "\u0000MDATE_" + i + "\u0000";
            result = result.replace(replacements.get(i)[0], placeholders[i]);
        }
        for (int i = 0; i < replacements.size(); i++) {
            result = result.replace(placeholders[i], replacements.get(i)[1]);
        }

        return result;
    }

    // ── Fingerprint Resolution ────────────────────────────────────────────

    /**
     * Find query_fingerprints entry, resolving non-canonical fingerprints.
     * The query_fingerprints table uses 16-char SHA-256 truncations, but callers may pass
     * 64-char Performance Schema digests or 32-char MD5 hashes (legacy formats).
     *
     * Resolution strategy: if direct lookup fails and fingerprint is non-canonical,
     * get query text from the optimization cache (which accepts any format), normalize it,
     * compute the canonical 16-char fingerprint, and retry the lookup.
     */
    private java.util.Optional<QueryFingerprint> findFingerprintEntry(String connectionId, String fingerprint) {
        // Direct lookup (works when fingerprint is already canonical 16-char)
        java.util.Optional<QueryFingerprint> direct =
            fingerprintRepository.findByConnectionIdAndFingerprint(connectionId, fingerprint);
        if (direct.isPresent()) return direct;

        // Resolve non-canonical formats via optimization cache
        if (fingerprint != null && fingerprint.length() > 16) {
            try {
                var cached = optimizationService.getCachedOptimization(connectionId, fingerprint);
                if (cached != null && cached.getOriginalQuery() != null) {
                    String normalized = QueryNormalizer.normalize(cached.getOriginalQuery());
                    String canonical = QueryFingerprintService.computeCanonicalFingerprint(normalized);
                    log.debug("Resolved non-canonical fingerprint {} to canonical {}", fingerprint, canonical);
                    return fingerprintRepository.findByConnectionIdAndFingerprint(connectionId, canonical);
                }
            } catch (Exception e) {
                log.debug("Could not resolve canonical fingerprint for {}: {}", fingerprint, e.getMessage());
            }
        }
        return java.util.Optional.empty();
    }

    /**
     * Refresh stale candidates whose date literals no longer match the fingerprint's latest
     * sampleQuery. Slow queries repeat daily with different dates; the ORIGINAL candidate
     * may already have fresh dates (ensureOptimizationCandidates uses sampleQuery), but the
     * AI_REWRITE comes from the optimization cache which has old dates.
     *
     * Strategy: get the cache's originalQuery (old dates) and the fingerprint's sampleQuery
     * (fresh dates), build a literal mapping, and apply it to AI_REWRITE candidates.
     */
    private void refreshStaleCandidates(
        String connectionId,
        String queryFingerprint,
        List<QueryOptimizationCandidateRun> candidates
    ) {
        // Use resolution-aware lookup (handles 64-char PS digest → 16-char canonical)
        java.util.Optional<QueryFingerprint> fpOpt = findFingerprintEntry(connectionId, queryFingerprint);
        if (fpOpt.isEmpty()) return;

        String freshSample = fpOpt.get().getSampleQuery();
        if (freshSample == null || freshSample.isBlank()) return;

        freshSample = optimizationService.sanitizeQueryText(freshSample);
        if (freshSample == null || freshSample.isBlank()) return;

        // Update ORIGINAL candidate if it has stale dates
        for (QueryOptimizationCandidateRun candidate : candidates) {
            if (!"ORIGINAL".equalsIgnoreCase(candidate.getCandidateId())) continue;
            String oldSql = candidate.getCandidateSql();
            if (oldSql == null || oldSql.isBlank()) continue;
            String oldNorm = QueryNormalizer.normalize(oldSql);
            String freshNorm = QueryNormalizer.normalize(freshSample);
            if (!oldNorm.equals(freshNorm) || oldSql.equals(freshSample)) continue;

            log.info("Refreshing stale ORIGINAL candidate for {} with updated literals", queryFingerprint);
            candidate.setCandidateSql(freshSample);
            candidate.setBenchmarkMs(null);
            candidate.setMedianMs(null);
            candidate.setRowCountResult(null);
            candidate.setStatus("PENDING");
            candidate.setErrorMessage(null);
            candidate.setUpdatedAt(LocalDateTime.now());
            break;
        }

        // Extract date-like patterns from ORIGINAL candidate to update AI_REWRITE.
        // The ORIGINAL candidate has fresh dates (from sampleQuery), but the AI_REWRITE
        // (from cache) still has old dates. We extract dd-mm-yyyy patterns from both
        // and substitute old→new in the AI_REWRITE.
        String originalSql = candidates.stream()
            .filter(c -> "ORIGINAL".equalsIgnoreCase(c.getCandidateId()))
            .map(QueryOptimizationCandidateRun::getCandidateSql)
            .filter(s -> s != null && !s.isBlank())
            .findFirst().orElse(null);
        if (originalSql == null) return;

        // Extract date patterns (dd-mm-yyyy) from ORIGINAL
        java.util.Set<String> originalDates = new java.util.LinkedHashSet<>();
        java.util.regex.Matcher dateMatcher = DATE_PATTERN.matcher(originalSql);
        while (dateMatcher.find()) {
            originalDates.add(dateMatcher.group(1));
        }
        if (originalDates.isEmpty()) return;

        boolean changed = false;
        for (QueryOptimizationCandidateRun rewrite : candidates) {
            if (!"AI_REWRITE".equalsIgnoreCase(rewrite.getCandidateId())) continue;
            String rewriteSql = rewrite.getCandidateSql();
            if (rewriteSql == null || rewriteSql.isBlank()) continue;

            // Extract ALL unique dates from AI_REWRITE (in order of appearance)
            java.util.Set<String> allRewriteDates = new java.util.LinkedHashSet<>();
            java.util.regex.Matcher rwMatcher = DATE_PATTERN.matcher(rewriteSql);
            while (rwMatcher.find()) {
                allRewriteDates.add(rwMatcher.group(1));
            }
            if (allRewriteDates.isEmpty()) continue;
            // Skip if dates are already identical (same set in same order)
            if (allRewriteDates.equals(originalDates)) continue;

            // Two-pass positional substitution to avoid cascading replacements:
            // Pass 1: replace all rewrite dates with unique placeholders
            // Pass 2: replace placeholders with corresponding fresh dates
            // This prevents "16-02 → 17-02" from creating a 17-02 that then becomes 18-02
            List<String> freshList = new ArrayList<>(originalDates);
            List<String> staleList = new ArrayList<>(allRewriteDates);
            String updated = rewriteSql;
            String[] placeholders = new String[staleList.size()];
            for (int di = 0; di < staleList.size(); di++) {
                placeholders[di] = "\u0000DATE_" + di + "\u0000";
                updated = updated.replace(staleList.get(di), placeholders[di]);
            }
            for (int di = 0; di < staleList.size(); di++) {
                String freshDate = freshList.get(Math.min(di, freshList.size() - 1));
                updated = updated.replace(placeholders[di], freshDate);
            }

            if (!updated.equals(rewriteSql)) {
                log.info("Updated AI_REWRITE date literals for {}: {} → {}",
                    queryFingerprint, allRewriteDates, originalDates);
                rewrite.setCandidateSql(updated);
                rewrite.setBenchmarkMs(null);
                rewrite.setMedianMs(null);
                rewrite.setRowCountResult(null);
                rewrite.setStatus("PENDING");
                rewrite.setErrorMessage(null);
                rewrite.setUpdatedAt(LocalDateTime.now());
                changed = true;
            }
        }

        if (changed) {
            candidateService.saveAll(candidates);
        }
    }

    /**
     * Auto-retry failed AI_REWRITE candidates by re-prompting the LLM with the error message.
     * Only retries once per benchmark call to avoid infinite loops.
     */
    private void retryFailedRewrites(
        List<QueryOptimizationCandidateRun> candidates,
        String connectionId,
        String queryFingerprint,
        ConnectionRequest connection,
        String dbType,
        int runCount,
        int timeout
    ) {
        QueryOptimizationCandidateRun failedRewrite = null;
        String originalSql = null;

        for (QueryOptimizationCandidateRun c : candidates) {
            if ("ORIGINAL".equals(c.getCandidateId()) && c.getCandidateSql() != null) {
                originalSql = c.getCandidateSql();
            }
            if ("AI_REWRITE".equals(c.getCandidateId()) && "FAILED".equals(c.getStatus())
                    && c.getErrorMessage() != null && !c.getErrorMessage().isBlank()) {
                failedRewrite = c;
            }
        }

        if (failedRewrite == null || originalSql == null) {
            return;
        }

        String errorMsg = failedRewrite.getErrorMessage();
        String failedSql = failedRewrite.getCandidateSql();

        log.info("Auto-retrying failed AI_REWRITE for {} with error feedback: {}",
            queryFingerprint, errorMsg);

        try {
            String correctedSql = optimizationService.retryOptimizationWithError(
                connectionId, originalSql, failedSql, errorMsg);

            if (correctedSql == null || correctedSql.isBlank() || correctedSql.equals(failedSql)) {
                log.debug("AI retry returned same or empty SQL, skipping re-benchmark");
                return;
            }

            // Update the candidate with the corrected SQL
            failedRewrite.setCandidateSql(correctedSql);

            // Align case with schema if MySQL (for execution only, not persisted)
            if ("mysql".equalsIgnoreCase(dbType)) {
                try {
                    com.dbaagent.model.SchemaMetadata schemaMetadata = schemaScannerService.scanSchema(connectionId);
                    if (schemaMetadata != null) {
                        String aligned = com.dbaagent.util.SqlIdentifierCaseAligner
                            .alignTableIdentifiers(correctedSql, schemaMetadata);
                        if (aligned != null && !aligned.isBlank()) {
                            correctedSql = aligned;
                            // Don't persist aligned casing — keep AI's corrected SQL in DB
                        }
                    }
                } catch (Exception e) {
                    log.debug("Schema alignment skipped for retry: {}", e.getMessage());
                }
            }

            // Re-benchmark the corrected SQL
            if (isBenchmarkable(correctedSql)) {
                List<Double> timings = new ArrayList<>();
                String error = null;

                for (int i = 0; i < runCount; i++) {
                    try {
                        double timeMs = runBenchmark(connectionId, connection, dbType, correctedSql, timeout);
                        timings.add(timeMs);
                        // Adaptive run reduction for slow queries
                        if (i == 0 && timeMs > SLOW_QUERY_THRESHOLD_MS && runCount > 1) {
                            log.info("Adaptive run reduction (retry) for {} — first run {}ms, skipping remaining runs",
                                queryFingerprint, timeMs);
                            break;
                        }
                    } catch (Exception e) {
                        error = e.getMessage();
                        break;
                    }
                }

                if (!timings.isEmpty()) {
                    double median = median(timings);
                    failedRewrite.setBenchmarkMs(timings.get(0));
                    failedRewrite.setMedianMs(median);
                    failedRewrite.setRunCount(timings.size());
                    failedRewrite.setStatus("SUCCESS");
                    failedRewrite.setErrorMessage(null);
                    failedRewrite.setWarnings("Auto-corrected after initial error: " + errorMsg);

                    // Skip row count for slow queries
                    if (median <= SLOW_QUERY_THRESHOLD_MS) {
                        try {
                            long rowCount = countRows(connectionId, connection, dbType, correctedSql, timeout);
                            failedRewrite.setRowCountResult(rowCount);
                        } catch (Exception e) {
                            log.debug("Row count capture failed on retry: {}", e.getMessage());
                        }
                    } else {
                        log.info("Skipping row count for retry — median {}ms exceeds threshold", median);
                    }

                    log.info("Auto-retry succeeded for AI_REWRITE {}: {}ms", queryFingerprint, timings.get(0));
                } else {
                    failedRewrite.setStatus("FAILED");
                    failedRewrite.setErrorMessage("Retry also failed: " + (error != null ? error : "Unknown error"));
                    log.warn("Auto-retry also failed for {}: {}", queryFingerprint, error);
                }
            }
            failedRewrite.setUpdatedAt(LocalDateTime.now());
        } catch (Exception e) {
            log.warn("Auto-retry error for {}: {}", queryFingerprint, e.getMessage());
        }
    }

    private Double parsePostgresExecutionTime(String json) {
        try {
            List<Map<String, Object>> plan = objectMapper.readValue(json, List.class);
            if (plan == null || plan.isEmpty()) {
                return null;
            }
            Object exec = plan.get(0).get("Execution Time");
            if (exec instanceof Number) {
                return ((Number) exec).doubleValue();
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private double median(List<Double> values) {
        if (values.isEmpty()) return 0.0;
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compareTo);
        int mid = sorted.size() / 2;
        if (sorted.size() % 2 == 0) {
            return (sorted.get(mid - 1) + sorted.get(mid)) / 2.0;
        }
        return sorted.get(mid);
    }

    private double nanosToMs(long nanos) {
        return nanos / 1_000_000.0;
    }
}
