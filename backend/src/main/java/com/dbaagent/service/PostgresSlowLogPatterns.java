package com.dbaagent.service;

import java.util.regex.Pattern;

/**
 * Single source of truth for PostgreSQL slow-query log line classification,
 * shared by SlowQueryLogParserService (extraction) and SlowQueryLogFilter
 * (fetch-time filtering) so the slow-query keyword set cannot drift.
 */
final class PostgresSlowLogPatterns {
    private PostgresSlowLogPatterns() {}

    /** A new log entry begins with a timestamp at the start of the line. */
    static final Pattern NEW_ENTRY =
        Pattern.compile("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");

    /** Duration header with inline statement/execute/parse/bind SQL (group 3 = SQL). */
    static final Pattern LOG_STATEMENT = Pattern.compile(
        "(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?).*?LOG:\\s+duration: ([\\d.]+) ms\\s+(?:statement|execute \\S*|parse \\S*|bind \\S*):\\s*(.*)");

    /** auto_explain header: "duration: N ms  plan:" (SQL on following Query Text line). */
    static final Pattern PLAN_HEADER = Pattern.compile(
        "(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?).*?LOG:\\s+duration: ([\\d.]+) ms\\s+plan:\\s*$");

    /** Duration-only header (statement keyword on the next line). */
    static final Pattern DURATION_ONLY = Pattern.compile(
        "(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?).*?LOG:\\s+duration: ([\\d.]+) ms$");

    /** True if the line starts a new (timestamped) log entry. */
    static boolean isNewEntry(String line) {
        if (line == null) return false;
        return NEW_ENTRY.matcher(line).find();
    }

    /** True if the line is the header of a slow-query entry (any duration form). */
    static boolean isSlowQueryHeader(String line) {
        if (line == null) return false;
        return LOG_STATEMENT.matcher(line).find()
            || PLAN_HEADER.matcher(line).find()
            || DURATION_ONLY.matcher(line).find();
    }
}
