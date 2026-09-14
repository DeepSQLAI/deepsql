package com.dbaagent.provider.postgres;

import com.dbaagent.dto.MigrationRiskReport;
import com.dbaagent.dto.TableFacts;
import com.dbaagent.service.migration.DdlFacts;
import com.dbaagent.service.migration.DdlOperation;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PostgresMigrationRiskRulesTest {

    private final PostgresMigrationRiskProvider provider = new PostgresMigrationRiskProvider();
    private final TableFacts big = new TableFacts(48_000_000L, 9_200_000_000L, false);
    private final TableFacts small = new TableFacts(500L, 40_000L, false);

    private DdlFacts addColumn(String defaultFn, boolean notNull) {
        return new DdlFacts(DdlOperation.ADD_COLUMN, "orders", "c", null, "text",
                defaultFn == null ? null : "DEFAULT", defaultFn, notNull, false, false, null, "sql");
    }

    // MEASURED: now() is STABLE, so this does NOT rewrite.
    @Test
    void addColumnWithStableDefault_doesNotRewrite() {
        var r = provider.classify(addColumn("now", false), big);
        assertThat(r.rewritesTable()).isFalse();
        assertThat(r.verdict()).isEqualTo("SAFE");
    }

    // MEASURED: gen_random_uuid() is VOLATILE, so this DOES rewrite.
    @Test
    void addColumnWithVolatileDefaultOnBigTable_isDanger() {
        var r = provider.classify(addColumn("gen_random_uuid", false), big);
        assertThat(r.rewritesTable()).isTrue();
        assertThat(r.verdict()).isEqualTo("DANGER");
        assertThat(r.saferAlternative()).isNotBlank();
    }

    @Test
    void addColumnWithVolatileDefaultOnSmallTable_isCaution() {
        var r = provider.classify(addColumn("random", false), small);
        assertThat(r.rewritesTable()).isTrue();
        assertThat(r.verdict()).isEqualTo("CAUTION");
    }

    // Finding 1 fix: an unrecognised default function must not be reported SAFE.
    @Test
    void addColumnWithUnrecognisedDefaultFunction_isCautionNotSafe() {
        var r = provider.classify(addColumn("my_custom_uuid", false), big);
        assertThat(r.verdict()).isEqualTo("CAUTION");
        assertThat(r.safeToRun()).isFalse();
        assertThat(r.rewritesTable()).isFalse();
        assertThat(r.reason()).containsIgnoringCase("cannot verify");
    }

    @Test
    void addColumnWithConstantDefault_isSafe() {
        var f = new DdlFacts(DdlOperation.ADD_COLUMN, "orders", "c", null, "text",
                "'x'", null, false, false, false, null, "sql");
        var r = provider.classify(f, big);
        assertThat(r.verdict()).isEqualTo("SAFE");
        assertThat(r.rewritesTable()).isFalse();
    }

    @Test
    void addColumnNotNullNoDefaultOnNonEmptyTable_isFailsOutright() {
        var r = provider.classify(addColumn(null, true), big);
        assertThat(r.verdict()).isEqualTo("FAILS");
        assertThat(r.reason()).containsIgnoringCase("null values");
    }

    @Test
    void addColumnPlain_isSafeEvenOnHugeTable() {
        var r = provider.classify(addColumn(null, false), big);
        assertThat(r.verdict()).isEqualTo("SAFE");
        assertThat(r.locks()).anySatisfy(l -> assertThat(l.mode()).isEqualTo("AccessExclusiveLock"));
    }

    // MEASURED: FK locks BOTH tables.
    @Test
    void addForeignKey_reportsLockOnReferencedTableToo() {
        var f = new DdlFacts(DdlOperation.ADD_FOREIGN_KEY, "child", null, null, null,
                null, null, false, false, false, "orders", "sql");
        var r = provider.classify(f, big);
        assertThat(r.locks()).extracting(MigrationRiskReport.LockRef::table)
                .containsExactlyInAnyOrder("child", "orders");
    }

    @Test
    void addForeignKeyNotValid_isSaferThanValidating() {
        var validating = new DdlFacts(DdlOperation.ADD_FOREIGN_KEY, "child", null, null, null,
                null, null, false, false, false, "orders", "sql");
        var notValid = new DdlFacts(DdlOperation.ADD_FOREIGN_KEY, "child", null, null, null,
                null, null, false, true, false, "orders", "sql");
        assertThat(provider.classify(validating, big).verdict()).isEqualTo("DANGER");
        assertThat(provider.classify(notValid, big).verdict()).isEqualTo("CAUTION");
    }

    @Test
    void createIndexConcurrently_isSafeAndPlainIsNot() {
        var plain = new DdlFacts(DdlOperation.CREATE_INDEX, "orders", null, null, null,
                null, null, false, false, false, null, "sql");
        var conc = new DdlFacts(DdlOperation.CREATE_INDEX, "orders", null, null, null,
                null, null, false, false, true, null, "sql");
        assertThat(provider.classify(plain, big).verdict()).isEqualTo("DANGER");
        assertThat(provider.classify(conc, big).verdict()).isEqualTo("SAFE");
    }

    @Test
    void dropColumn_isMetadataOnly() {
        var f = new DdlFacts(DdlOperation.DROP_COLUMN, "orders", "a", null, null,
                null, null, false, false, false, null, "sql");
        var r = provider.classify(f, big);
        assertThat(r.rewritesTable()).isFalse();
        assertThat(r.verdict()).isEqualTo("SAFE");
    }

    @Test
    void renameColumn_isMetadataOnly() {
        var f = new DdlFacts(DdlOperation.RENAME_COLUMN, "orders", "a", "b", null,
                null, null, false, false, false, null, "sql");
        var r = provider.classify(f, big);
        assertThat(r.rewritesTable()).isFalse();
        assertThat(r.verdict()).isEqualTo("SAFE");
    }

    @Test
    void alterColumnType_rewritesAndIsDangerOnBigTable() {
        var f = new DdlFacts(DdlOperation.ALTER_COLUMN_TYPE, "orders", "c", null, "uuid",
                null, null, false, false, false, null, "sql");
        var r = provider.classify(f, big);
        assertThat(r.rewritesTable()).isTrue();
        assertThat(r.verdict()).isEqualTo("DANGER");
        assertThat(r.safeToRun()).isFalse();
    }

    @Test
    void setNotNull_isSafeOnSmallTableAndCautionOnLarge() {
        var f = new DdlFacts(DdlOperation.SET_NOT_NULL, "orders", "c", null, null,
                null, null, false, false, false, null, "sql");
        var small = provider.classify(f, this.small);
        var large = provider.classify(f, big);
        assertThat(small.verdict()).isEqualTo("SAFE");
        assertThat(small.rewritesTable()).isFalse();
        assertThat(large.verdict()).isEqualTo("CAUTION");
        assertThat(large.safeToRun()).isFalse();
    }

    @Test
    void addCheckNotValid_isSafe() {
        var f = new DdlFacts(DdlOperation.ADD_CHECK, "orders", null, null, null,
                null, null, false, true, false, null, "sql");
        var r = provider.classify(f, big);
        assertThat(r.verdict()).isEqualTo("SAFE");
    }

    @Test
    void addCheckValidating_isDangerOnBigTable() {
        var f = new DdlFacts(DdlOperation.ADD_CHECK, "orders", null, null, null,
                null, null, false, false, false, null, "sql");
        var r = provider.classify(f, big);
        assertThat(r.verdict()).isEqualTo("DANGER");
        assertThat(r.safeToRun()).isFalse();
    }

    @Test
    void validateConstraint_isSafe() {
        var f = new DdlFacts(DdlOperation.VALIDATE_CONSTRAINT, "orders", null, null, null,
                null, null, false, false, false, null, "sql");
        var r = provider.classify(f, big);
        assertThat(r.verdict()).isEqualTo("SAFE");
        assertThat(r.rewritesTable()).isFalse();
        assertThat(r.locks()).anySatisfy(l -> assertThat(l.mode()).isEqualTo("ShareUpdateExclusiveLock"));
    }

    @Test
    void unknownOperation_failsClosed() {
        var f = new DdlFacts(DdlOperation.UNKNOWN, "orders", null, null, null,
                null, null, false, false, false, null, "sql");
        var r = provider.classify(f, big);
        assertThat(r.verdict()).isEqualTo("UNKNOWN");
        assertThat(r.safeToRun()).isFalse();
    }
}
