package com.dbaagent.service;

import java.util.regex.Pattern;

/**
 * Single source of truth for MySQL slow-query log line classification.
 * MySQL slow-query logs contain ONLY slow-query entries (no audit/connection
 * noise to filter out), so the filter just needs to detect entry boundaries.
 */
final class MysqlSlowLogPatterns {
    private MysqlSlowLogPatterns() {}

    /**
     * Primary entry-start marker: {@code # Time: <timestamp>}.
     * Present in MySQL 5.7+/Aurora MySQL with log_timestamps configured.
     */
    static final Pattern HEADER_TIME = Pattern.compile("^# Time:");

    /**
     * Fallback entry-start marker: {@code # User@Host: ...}.
     * Used when {@code # Time:} is absent (older MySQL, some RDS parameter groups).
     */
    static final Pattern HEADER_USER = Pattern.compile("^# User@Host:");

    static boolean isTimeHeader(String line) {
        return line != null && HEADER_TIME.matcher(line).find();
    }

    static boolean isUserHeader(String line) {
        return line != null && HEADER_USER.matcher(line).find();
    }
}
