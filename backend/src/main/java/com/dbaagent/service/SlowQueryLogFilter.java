package com.dbaagent.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;

/**
 * Continuation-aware filter that keeps slow-query entries for both PostgreSQL
 * and MySQL log formats, dropping non-slow-query noise (pgaudit AUDIT,
 * connection, checkpoint, autovacuum lines in Postgres).
 *
 * <p>MySQL slow-query logs contain only slow queries by definition, so every
 * line is kept once an entry header is detected. Postgres logs require
 * explicit matching against {@link PostgresSlowLogPatterns}.
 *
 * <p>One instance per file/stream; not reentrant (carries entry state across lines).
 */
final class SlowQueryLogFilter {
    private enum Format { UNKNOWN, MYSQL, POSTGRES }

    private Format format = Format.UNKNOWN;
    private boolean keeping = false;

    /**
     * Feed one log line. Writes it to {@code out} iff it belongs to a
     * slow-query entry. Returns true iff this line started a new kept entry.
     */
    boolean acceptLine(String line, Writer out) throws IOException {
        if (line == null) return false;
        boolean startedEntry = false;

        if (MysqlSlowLogPatterns.isTimeHeader(line)) {
            // MySQL primary entry header: # Time: <timestamp>
            format = Format.MYSQL;
            keeping = true;
            startedEntry = true;
        } else if (format != Format.MYSQL && MysqlSlowLogPatterns.isUserHeader(line)) {
            // MySQL fallback entry header (no # Time: present): # User@Host: ...
            format = Format.MYSQL;
            keeping = true;
            startedEntry = true;
        } else if (PostgresSlowLogPatterns.isNewEntry(line)) {
            format = Format.POSTGRES;
            keeping = PostgresSlowLogPatterns.isSlowQueryHeader(line);
            startedEntry = keeping;
        }
        // else: continuation line — preserve current keeping state

        if (keeping) {
            out.write(line);
            out.write('\n');
        }
        return startedEntry;
    }

    /** True if the most recently accepted line was written (belongs to a kept slow-query entry). */
    boolean isKeeping() {
        return keeping;
    }

    /** Filter an entire line stream. Returns the number of slow-query entries kept. */
    long filter(BufferedReader in, Writer out) throws IOException {
        long kept = 0;
        String line;
        while ((line = in.readLine()) != null) {
            if (acceptLine(line, out)) kept++;
        }
        out.flush();
        return kept;
    }
}
