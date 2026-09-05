package com.dbaagent.provider.postgres;

import com.dbaagent.dto.MigrationRiskReport;
import com.dbaagent.dto.MigrationRiskReport.LockRef;
import com.dbaagent.dto.TableFacts;
import com.dbaagent.provider.api.MigrationRiskProvider;
import com.dbaagent.service.migration.DdlFacts;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
public class PostgresMigrationRiskProvider implements MigrationRiskProvider {

    private static final long LARGE_ROWS = 1_000_000L;
    private static final String ALTER_DOCS = "https://www.postgresql.org/docs/18/sql-altertable.html";
    private static final String INDEX_DOCS = "https://www.postgresql.org/docs/18/sql-createindex.html";
    private static final Set<String> VOLATILE_FALLBACK =
            Set.of("random", "gen_random_uuid", "clock_timestamp", "uuid_generate_v4");
    private static final Set<String> STABLE_FALLBACK =
            Set.of("now", "current_timestamp", "current_date", "current_time", "transaction_timestamp");

    private static final List<String> RW = List.of("read", "write");
    private static final List<String> W = List.of("write");

    @Override
    public MigrationRiskReport classify(DdlFacts f, TableFacts t) {
        return switch (f.operation()) {
            case ADD_COLUMN -> addColumn(f, t);
            case DROP_COLUMN -> metadataOnly(f, t, "Dropping a column is metadata-only; the data is reclaimed later by VACUUM.");
            case RENAME_COLUMN -> metadataOnly(f, t, "Renaming is metadata-only, but it will break application code referencing the old name.");
            case ALTER_COLUMN_TYPE -> alterType(f, t);
            case SET_NOT_NULL -> setNotNull(f, t);
            case ADD_CHECK -> addCheck(f, t);
            case ADD_FOREIGN_KEY -> addForeignKey(f, t);
            case VALIDATE_CONSTRAINT -> validateConstraint(f, t);
            case CREATE_INDEX -> createIndex(f, t);
            case UNKNOWN -> unknown(f, t);
        };
    }

    private boolean isVolatile(String fn) {
        return fn != null && VOLATILE_FALLBACK.contains(fn.toLowerCase());
    }

    private boolean isStable(String fn) {
        return fn != null && STABLE_FALLBACK.contains(fn.toLowerCase());
    }

    private String bucket(TableFacts t) {
        if (t.rowEstimate() >= 10_000_000L) return "minutes-to-tens-of-minutes";
        if (t.rowEstimate() >= LARGE_ROWS) return "minutes";
        return "seconds";
    }

    private MigrationRiskReport addColumn(DdlFacts f, TableFacts t) {
        var locks = List.of(new LockRef(f.table(), "AccessExclusiveLock", RW));
        if (f.notNull() && f.defaultFunction() == null && f.defaultExpression() == null && !t.empty()) {
            return report("FAILS", false, f, t, locks, false, "milliseconds",
                    "ADD COLUMN ... NOT NULL without a default fails on a non-empty table: "
                    + "the existing rows contain null values.",
                    "Add the column nullable, backfill in batches, then SET NOT NULL.", ALTER_DOCS);
        }
        if (isVolatile(f.defaultFunction())) {
            boolean big = t.rowEstimate() >= LARGE_ROWS;
            return report(big ? "DANGER" : "CAUTION", false, f, t, locks, true, bucket(t),
                    "Default expression " + f.defaultFunction()
                    + "() is VOLATILE, so every existing row must be written — a full table rewrite "
                    + "under ACCESS EXCLUSIVE, blocking reads and writes for the whole operation.",
                    "Add the column without a default, backfill in batches, then set the default "
                    + "for future rows.", ALTER_DOCS);
        }
        if (f.defaultFunction() != null && !isStable(f.defaultFunction())) {
            return report("CAUTION", false, f, t, locks, false, "unknown",
                    "DeepSQL cannot verify the volatility of " + f.defaultFunction()
                    + "(). A VOLATILE default would force a full table rewrite under ACCESS "
                    + "EXCLUSIVE; a STABLE or IMMUTABLE one would not. Check "
                    + "SELECT provolatile FROM pg_proc WHERE proname = '" + f.defaultFunction()
                    + "' before running this.",
                    "If the function is VOLATILE, add the column without a default, backfill in "
                    + "batches, then set the default for future rows.", ALTER_DOCS);
        }
        return report("SAFE", true, f, t, locks, false, "milliseconds",
                "Adding a column with no default, a constant default, or a STABLE default "
                + "(such as now()) is metadata-only in PostgreSQL 11+. The ACCESS EXCLUSIVE lock "
                + "is held only briefly.", null, ALTER_DOCS);
    }

    private MigrationRiskReport metadataOnly(DdlFacts f, TableFacts t, String why) {
        return report("SAFE", true, f, t,
                List.of(new LockRef(f.table(), "AccessExclusiveLock", RW)),
                false, "milliseconds", why, null, ALTER_DOCS);
    }

    private MigrationRiskReport alterType(DdlFacts f, TableFacts t) {
        boolean big = t.rowEstimate() >= LARGE_ROWS;
        return report(big ? "DANGER" : "CAUTION", false, f, t,
                List.of(new LockRef(f.table(), "AccessExclusiveLock", RW)),
                true, bucket(t),
                "Changing a column type generally rewrites the whole table under ACCESS EXCLUSIVE. "
                + "(Some conversions, such as widening within the same type family, are known "
                + "exceptions that PostgreSQL can skip rewriting for — but DeepSQL cannot confirm "
                + "which case this is, so the conservative rewrite verdict is reported here.)",
                "Add a new column, backfill in batches, swap the names, then drop the old column.",
                ALTER_DOCS);
    }

    private MigrationRiskReport setNotNull(DdlFacts f, TableFacts t) {
        return report(t.rowEstimate() >= LARGE_ROWS ? "CAUTION" : "SAFE",
                t.rowEstimate() < LARGE_ROWS, f, t,
                List.of(new LockRef(f.table(), "AccessExclusiveLock", RW)),
                false, bucket(t),
                "SET NOT NULL does not rewrite the table, but it scans every row to validate, "
                + "holding ACCESS EXCLUSIVE for the duration of the scan.",
                "Add a NOT VALID CHECK (col IS NOT NULL) constraint, VALIDATE it under a weaker "
                + "lock, then SET NOT NULL.", ALTER_DOCS);
    }

    private MigrationRiskReport addCheck(DdlFacts f, TableFacts t) {
        var locks = List.of(new LockRef(f.table(), "AccessExclusiveLock", RW));
        if (f.notValid()) {
            return report("SAFE", true, f, t, locks, false, "milliseconds",
                    "ADD CONSTRAINT ... NOT VALID skips the validating scan, so the ACCESS "
                    + "EXCLUSIVE lock is held only briefly.",
                    "Follow with VALIDATE CONSTRAINT, which runs under a weaker lock that allows "
                    + "concurrent writes.", ALTER_DOCS);
        }
        return report(t.rowEstimate() >= LARGE_ROWS ? "DANGER" : "CAUTION", false, f, t, locks,
                false, bucket(t),
                "A validating CHECK constraint scans every row while holding ACCESS EXCLUSIVE, "
                + "blocking reads and writes.",
                "Add it NOT VALID first, then VALIDATE CONSTRAINT separately.", ALTER_DOCS);
    }

    private MigrationRiskReport addForeignKey(DdlFacts f, TableFacts t) {
        var locks = f.referencedTable() == null
                ? List.of(new LockRef(f.table(), "ShareRowExclusiveLock", W))
                : List.of(new LockRef(f.table(), "ShareRowExclusiveLock", W),
                          new LockRef(f.referencedTable(), "ShareRowExclusiveLock", W));
        String bothTables = "This locks the referenced table as well as the one being altered — "
                + "writes are blocked on a table the statement does not name.";
        if (f.notValid()) {
            return report("CAUTION", false, f, t, locks, false, "seconds",
                    "NOT VALID skips the validating scan, but ShareRowExclusiveLock is still taken "
                    + "on both tables. " + bothTables,
                    "Follow with VALIDATE CONSTRAINT under ShareUpdateExclusiveLock.", ALTER_DOCS);
        }
        return report("DANGER", false, f, t, locks, false, bucket(t),
                "Adding a validating foreign key scans the child table and blocks writes on both "
                + "tables under ShareRowExclusiveLock. " + bothTables,
                "Add the constraint NOT VALID, then VALIDATE CONSTRAINT separately.", ALTER_DOCS);
    }

    private MigrationRiskReport validateConstraint(DdlFacts f, TableFacts t) {
        return report("SAFE", true, f, t,
                List.of(new LockRef(f.table(), "ShareUpdateExclusiveLock", List.of())),
                false, bucket(t),
                "VALIDATE CONSTRAINT scans the table under ShareUpdateExclusiveLock, which allows "
                + "concurrent reads and writes. This is the cheap second half of the NOT VALID pattern.",
                null, ALTER_DOCS);
    }

    private MigrationRiskReport createIndex(DdlFacts f, TableFacts t) {
        if (f.concurrently()) {
            return report("SAFE", true, f, t,
                    List.of(new LockRef(f.table(), "ShareUpdateExclusiveLock", List.of())),
                    false, bucket(t),
                    "CREATE INDEX CONCURRENTLY does not block reads or writes. It takes longer and "
                    + "cannot run inside a transaction block.",
                    "If it fails it leaves an INVALID index behind — check with \\d and drop it "
                    + "before retrying.", INDEX_DOCS);
        }
        return report(t.rowEstimate() >= LARGE_ROWS ? "DANGER" : "CAUTION", false, f, t,
                List.of(new LockRef(f.table(), "ShareLock", W)),
                false, bucket(t),
                "A plain CREATE INDEX blocks all writes to the table for the entire build.",
                "Use CREATE INDEX CONCURRENTLY, which allows concurrent writes.", INDEX_DOCS);
    }

    private MigrationRiskReport unknown(DdlFacts f, TableFacts t) {
        return report("UNKNOWN", false, f, t, List.of(), false, "unknown",
                "This statement is not one DeepSQL has a verified rule for. Treat it as unsafe "
                + "until reviewed by hand.", null, ALTER_DOCS);
    }

    private MigrationRiskReport report(String verdict, boolean safe, DdlFacts f, TableFacts t,
                                       List<LockRef> locks, boolean rewrites, String duration,
                                       String reason, String safer, String docs) {
        return new MigrationRiskReport("postgres", verdict, safe, true, f.operation().name(),
                f.table(), locks, rewrites, t.rowEstimate(), t.sizeBytes(), duration,
                reason, safer, docs, "verified");
    }
}
