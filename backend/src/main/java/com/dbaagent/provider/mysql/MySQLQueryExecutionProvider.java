package com.dbaagent.provider.mysql;

import com.dbaagent.provider.api.QueryExecutionProvider;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.List;
import java.util.regex.Pattern;

/**
 * MySQL implementation of QueryExecutionProvider.
 * Handles MySQL-specific query execution features.
 */
@Component
public class MySQLQueryExecutionProvider implements QueryExecutionProvider {

    private static final String DATABASE_TYPE = "mysql";
    private static final Pattern LITERAL_PATTERN = Pattern.compile(
        "'[^']*'|\"[^\"]*\"|\\d+\\.?\\d*"
    );
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

    @Override
    public String getDatabaseType() {
        return DATABASE_TYPE;
    }

    @Override
    public String applyLimit(String query, int limit) {
        if (query == null || limit <= 0) {
            return query;
        }

        String trimmedQuery = query.trim();
        String lowerQuery = trimmedQuery.toLowerCase();

        // Only add LIMIT to SELECT queries without existing LIMIT
        if (!lowerQuery.startsWith("select") || lowerQuery.contains("limit")) {
            return query;
        }

        // Remove trailing semicolon if present
        if (trimmedQuery.endsWith(";")) {
            trimmedQuery = trimmedQuery.substring(0, trimmedQuery.length() - 1);
        }

        return trimmedQuery + " LIMIT " + limit;
    }

    @Override
    public boolean isReadOnlyQuery(String query) {
        if (query == null || query.trim().isEmpty()) {
            return true;
        }

        String normalized = stripLeadingComments(query.trim()).toUpperCase();

        return normalized.isEmpty() ||
               normalized.startsWith("SELECT") ||
               normalized.startsWith("SHOW") ||
               normalized.startsWith("DESCRIBE") ||
               normalized.startsWith("DESC") ||
               normalized.startsWith("EXPLAIN") ||
               normalized.startsWith("WITH");
    }

    private static String stripLeadingComments(String sql) {
        String remaining = sql.trim();
        while (!remaining.isEmpty()) {
            if (remaining.startsWith("--") || remaining.startsWith("#")) {
                int newline = remaining.indexOf('\n');
                remaining = newline >= 0 ? remaining.substring(newline + 1).trim() : "";
                continue;
            }
            if (remaining.startsWith("/*")) {
                int end = remaining.indexOf("*/");
                remaining = end >= 0 ? remaining.substring(end + 2).trim() : "";
                continue;
            }
            break;
        }
        return remaining;
    }

    @Override
    public String getConnectionTestQuery() {
        return "SELECT 1";
    }

    @Override
    public String normalizeQuery(String query) {
        if (query == null) {
            return null;
        }

        // Replace literals with placeholders
        String normalized = LITERAL_PATTERN.matcher(query).replaceAll("?");

        // Normalize whitespace
        normalized = WHITESPACE_PATTERN.matcher(normalized).replaceAll(" ");

        // Trim and lowercase
        return normalized.trim().toLowerCase();
    }

    @Override
    public String generateQuerySignature(String query) {
        String normalized = normalizeQuery(query);
        if (normalized == null) {
            return null;
        }

        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(normalized.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // Fallback to hashCode
            return String.valueOf(normalized.hashCode());
        }
    }

    @Override
    public String getSessionPidQuery() {
        return "SELECT CONNECTION_ID()";
    }

    // ---- production guardrails -------------------------------------------------
    //
    // Added after a production incident: an ALTER TABLE ... MODIFY
    // ... NOT NULL from the Editor queued every write on the table for four minutes.
    // MySQL's default lock_wait_timeout is one year, so nothing on the server side
    // stopped it; the Editor's 240s client timeout did.

    /** Seconds any statement may wait for a metadata or row lock before giving up. */
    private static final int LOCK_WAIT_SECONDS = 5;
    /** Read time limit on a writable server (the primary). Replicas get the client timeout. */
    private static final int PRIMARY_READ_LIMIT_MS = 30_000;

    private static final String LOCK_GUARD =
        "lock_wait_timeout = " + LOCK_WAIT_SECONDS + ", innodb_lock_wait_timeout = " + LOCK_WAIT_SECONDS;

    private static final Pattern ALTER_TABLE = Pattern.compile(
        "^ALTER\\s+(?:ONLINE\\s+|IGNORE\\s+)?TABLE\\b", Pattern.CASE_INSENSITIVE);
    // Value-anchored so a column named `algorithm` or `lock` does not read as a clause.
    // Possessive: `\s*=?\s*` lets the engine split a long run of spaces between the two
    // `\s*` every possible way before failing — quadratic (CodeQL java/polynomial-redos).
    private static final Pattern ALGORITHM_CLAUSE = Pattern.compile(
        "\\bALGORITHM\\s*+(?:=\\s*+)?(DEFAULT|INSTANT|INPLACE|COPY)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern LOCK_CLAUSE = Pattern.compile(
        "\\bLOCK\\s*+(?:=\\s*+)?(DEFAULT|NONE|SHARED|EXCLUSIVE)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern PARTITION_CLAUSE = Pattern.compile("\\bPARTITION(?:S|ING)?\\b", Pattern.CASE_INSENSITIVE);
    // One left-to-right pass over literals, identifiers and comments: at each position the
    // first alternative that starts there wins, so a quote inside a comment, or a comment
    // marker inside a string, cannot desynchronise the two. A clause inside a comment is not
    // a clause — `-- , ALGORITHM=INPLACE, LOCK=NONE` used to read as a real one. MySQL's
    // `--` comment needs whitespace after it (`--1` is arithmetic).
    // Every alternative also accepts end-of-input as its close, with possessive repetition:
    // an unterminated quote or comment is consumed once, to the end, instead of being
    // rescanned from every opening marker (a lazy /\*.*?\*/ is quadratic on "a/*" x 32k).
    private static final Pattern QUOTED = Pattern.compile(
        "'(?:[^'\\\\]|\\\\.|'')*+(?:'|$)"
            + "|\"(?:[^\"\\\\]|\\\\.)*+(?:\"|$)"
            + "|`[^`]*+(?:`|$)"
            + "|/\\*(?:[^*]|\\*(?!/))*+(?:\\*/|$)"
            + "|--(?=\\s|$)[^\\n]*+"
            + "|#[^\\n]*+");

    private static final int ER_LOCK_WAIT_TIMEOUT = 1205;
    private static final int ER_UPDATE_WITHOUT_KEY_IN_SAFE_MODE = 1175;
    private static final int ER_QUERY_TIMEOUT = 3024;
    private static final int ER_ALTER_OPERATION_NOT_SUPPORTED = 1845;
    private static final int ER_ALTER_OPERATION_NOT_SUPPORTED_REASON = 1846;
    private static final int ER_PARSE_ERROR = 1064;

    @Override
    public String connectionGuardSql() {
        return "SET SESSION " + LOCK_GUARD;
    }

    /**
     * Reads get the client timeout on a replica but at most 30s on a writable server, so
     * heavy exploration lands on the replica instead of the primary bookings write to.
     * {@code @@global.read_only} decides which one this is, so no per-connection setting
     * can drift out of date. {@code max_execution_time} applies to SELECT only; writes
     * and DDL are bounded by the lock waits and the JDBC timeout.
     */
    @Override
    public String statementGuardSql(int timeoutSeconds, String serverVersion) {
        String guard = "SET SESSION " + LOCK_GUARD + ", sql_safe_updates = 1";
        if (isMariaDb(serverVersion)) {
            // MariaDB has no max_execution_time (the SET would fail and, fail-closed, block
            // every query). Its max_statement_time also stops writes and DDL mid-flight, so
            // reads there stay bounded by the JDBC timeout alone.
            return guard;
        }
        int clientMs = timeoutSeconds > 0 ? timeoutSeconds * 1000 : PRIMARY_READ_LIMIT_MS;
        // Aurora readers report read_only=0 but innodb_read_only=1; either marks a replica.
        return guard + ", max_execution_time = IF(@@global.read_only OR @@global.innodb_read_only, "
            + clientMs + ", LEAST(" + clientMs + ", " + PRIMARY_READ_LIMIT_MS + "))";
    }

    /**
     * Restores the guard variables plus the session settings a user's preamble can use to
     * weaken the next borrower's writes (FK checks, strict mode). ponytail: an explicit list,
     * not COM_RESET_CONNECTION — MySQL has no SQL form of it, and the driver-level reset
     * re-authenticates on every run. Other session variables still carry over, as before.
     */
    @Override
    public String statementGuardResetSql(String serverVersion) {
        // `= @@global.x`, not `= DEFAULT`: measured on MySQL 8.4, `SET SESSION
        // foreign_key_checks = DEFAULT` (and unique_checks) leaves the session at 0 while
        // the global is 1 — the reset silently failed for exactly the integrity switches.
        return "SET SESSION " + LOCK_GUARD
            + restore("sql_safe_updates", "sql_mode", "foreign_key_checks", "unique_checks", "time_zone")
            + (isMariaDb(serverVersion) ? "" : restore("max_execution_time"));
    }

    private static String restore(String... variables) {
        StringBuilder sql = new StringBuilder();
        for (String v : variables) {
            sql.append(", ").append(v).append(" = @@global.").append(v);
        }
        return sql.toString();
    }

    private static boolean isMariaDb(String serverVersion) {
        return serverVersion != null && serverVersion.toLowerCase(java.util.Locale.ROOT).contains("mariadb");
    }

    @Override
    public List<String> onlineDdlAttempts(String sql) {
        if (sql == null || !ALTER_TABLE.matcher(stripLeadingComments(sql)).find()) {
            return List.of(sql);
        }
        String base = sql.strip();
        while (base.endsWith(";")) {
            base = base.substring(0, base.length() - 1).strip();
        }
        String clauses = QUOTED.matcher(base).replaceAll(" ");
        String algorithm = clauseValue(ALGORITHM_CLAUSE, clauses);
        String lock = clauseValue(LOCK_CLAUSE, clauses);

        if ("COPY".equals(algorithm) || "DEFAULT".equals(algorithm)
            || "SHARED".equals(lock) || "EXCLUSIVE".equals(lock) || "DEFAULT".equals(lock)) {
            throw new IllegalArgumentException(
                "DeepSQL only runs schema changes online (ALGORITHM=INSTANT, or INPLACE with LOCK=NONE). "
                    + "This one asks for a mode that blocks writes to the table while it runs. "
                    + "Make large rebuilds off-peak with gh-ost or pt-online-schema-change instead.");
        }
        // The appended clause starts on a new line: a trailing "-- comment" would
        // otherwise swallow it, and the ALTER would run with MySQL's default algorithm.
        if (algorithm == null && lock == null && PARTITION_CLAUSE.matcher(clauses).find()) {
            // Partition options must come last in an ALTER, so an appended clause is a
            // syntax error. Ask for the clause up front instead of guessing where it goes.
            throw new IllegalArgumentException(
                "DeepSQL cannot check a partition change for online safety by itself. State it explicitly at the "
                    + "start, e.g. ALTER TABLE t ALGORITHM=INPLACE, LOCK=NONE, ADD PARTITION (...), or run it off-peak "
                    + "with gh-ost or pt-online-schema-change.");
        }
        if (algorithm == null && lock == null) {
            return List.of(base + "\n, ALGORITHM=INSTANT", base + "\n, ALGORITHM=INPLACE, LOCK=NONE");
        }
        if ("INPLACE".equals(algorithm) && lock == null) {
            return List.of(base + "\n, LOCK=NONE");
        }
        return List.of(sql);
    }

    private static String clauseValue(Pattern clause, String sql) {
        java.util.regex.Matcher m = clause.matcher(sql);
        return m.find() ? m.group(1).toUpperCase(java.util.Locale.ROOT) : null;
    }

    @Override
    public boolean isOnlineDdlUnsupported(SQLException e) {
        int code = e.getErrorCode();
        String message = e.getMessage() == null ? "" : e.getMessage();
        return code == ER_ALTER_OPERATION_NOT_SUPPORTED
            || code == ER_ALTER_OPERATION_NOT_SUPPORTED_REASON
            // Later InnoDB limits (row versions, max row size) word it the same way.
            || message.contains("Try ALGORITHM=")
            // MySQL 5.7 / Aurora MySQL 2 / older MariaDB do not know INSTANT at all and
            // reject it as a syntax error pointing at the word we appended.
            || (code == ER_PARSE_ERROR && message.contains("near 'INSTANT"));
    }

    @Override
    public String guardrailMessage(SQLException e) {
        if (isOnlineDdlUnsupported(e)) {
            return "MySQL cannot make this change without blocking writes to the table (" + e.getMessage() + "). "
                + "Run it off-peak with gh-ost or pt-online-schema-change.";
        }
        return switch (e.getErrorCode()) {
            case ER_LOCK_WAIT_TIMEOUT -> "Gave up after waiting " + LOCK_WAIT_SECONDS + " seconds for a lock: another "
                + "session is using this table or these rows. DeepSQL does not queue behind locks, because a "
                + "waiting statement makes every later write to the table wait too. Try again when it is quieter.";
            case ER_UPDATE_WITHOUT_KEY_IN_SAFE_MODE -> "MySQL safe-updates mode refused this: the WHERE clause must "
                + "filter on an indexed (key) column, or the statement must have a LIMIT.";
            // Could be the 30s primary cap or the query's own timeout; the error does not say which.
            case ER_QUERY_TIMEOUT -> "Stopped at the time limit for reads: your query timeout, and at most "
                + PRIMARY_READ_LIMIT_MS / 1000 + " seconds on a primary database. Narrow the query, or run heavy "
                + "reads on a replica connection.";
            default -> null;
        };
    }
}
