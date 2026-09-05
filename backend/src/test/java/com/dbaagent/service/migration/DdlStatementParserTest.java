package com.dbaagent.service.migration;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class DdlStatementParserTest {

    private final DdlStatementParser parser = new DdlStatementParser();

    @Test
    void addColumnWithVolatileDefault_extractsFunctionName() {
        var f = parser.parse("ALTER TABLE orders ADD COLUMN c uuid DEFAULT gen_random_uuid()").orElseThrow();
        assertThat(f.operation()).isEqualTo(DdlOperation.ADD_COLUMN);
        assertThat(f.table()).isEqualTo("orders");
        assertThat(f.column()).isEqualTo("c");
        assertThat(f.defaultFunction()).isEqualTo("gen_random_uuid");
    }

    @Test
    void addColumnWithoutDefault_hasNoDefaultFunction() {
        var f = parser.parse("ALTER TABLE orders ADD COLUMN c text").orElseThrow();
        assertThat(f.defaultFunction()).isNull();
        assertThat(f.notNull()).isFalse();
    }

    @Test
    void addColumnNotNull_setsFlag() {
        var f = parser.parse("ALTER TABLE orders ADD COLUMN c text NOT NULL").orElseThrow();
        assertThat(f.notNull()).isTrue();
    }

    // JSqlParser 5.2 cannot parse NOT VALID — the shim must strip it into a flag.
    @Test
    void addCheckNotValid_parsesViaShim() {
        var f = parser.parse("ALTER TABLE orders ADD CONSTRAINT ck CHECK (id > 0) NOT VALID").orElseThrow();
        assertThat(f.operation()).isEqualTo(DdlOperation.ADD_CHECK);
        assertThat(f.notValid()).isTrue();
    }

    @Test
    void addForeignKeyNotValid_capturesReferencedTable() {
        var f = parser.parse(
            "ALTER TABLE child ADD CONSTRAINT fk FOREIGN KEY (t_id) REFERENCES t(id) NOT VALID").orElseThrow();
        assertThat(f.operation()).isEqualTo(DdlOperation.ADD_FOREIGN_KEY);
        assertThat(f.notValid()).isTrue();
        assertThat(f.referencedTable()).isEqualTo("t");
    }

    // JSqlParser 5.2 cannot parse CONCURRENTLY — the shim must strip it into a flag.
    @Test
    void createIndexConcurrently_parsesViaShim() {
        var f = parser.parse("CREATE INDEX CONCURRENTLY idx ON orders (a)").orElseThrow();
        assertThat(f.operation()).isEqualTo(DdlOperation.CREATE_INDEX);
        assertThat(f.concurrently()).isTrue();
        assertThat(f.table()).isEqualTo("orders");
    }

    @Test
    void createIndexPlain_isNotConcurrent() {
        var f = parser.parse("CREATE INDEX idx ON orders (a)").orElseThrow();
        assertThat(f.concurrently()).isFalse();
    }

    @Test
    void setNotNull_isDistinguishedFromAlterType() {
        var f = parser.parse("ALTER TABLE orders ALTER COLUMN a SET NOT NULL").orElseThrow();
        assertThat(f.operation()).isEqualTo(DdlOperation.SET_NOT_NULL);
    }

    @Test
    void alterColumnType_capturesTargetType() {
        var f = parser.parse("ALTER TABLE orders ALTER COLUMN a TYPE varchar(50)").orElseThrow();
        assertThat(f.operation()).isEqualTo(DdlOperation.ALTER_COLUMN_TYPE);
        assertThat(f.dataType()).containsIgnoringCase("varchar");
    }

    @Test
    void garbageInput_returnsEmptySoCallerFailsClosed() {
        assertThat(parser.parse("this is not sql at all")).isEmpty();
    }

    @Test
    void selectStatement_returnsEmpty() {
        assertThat(parser.parse("SELECT * FROM orders")).isEmpty();
    }

    // A single DdlFacts cannot honestly represent two clauses; must fail closed rather than
    // judge the statement on the harmless first clause alone.
    @Test
    void multiClauseAlter_returnsEmptySoCallerFailsClosed() {
        assertThat(parser.parse(
            "ALTER TABLE t ADD COLUMN a text, ADD COLUMN b uuid DEFAULT gen_random_uuid()")).isEmpty();
    }

    @Test
    void addColumnNamedCheckFlag_isNotMisclassifiedAsAddCheck() {
        var f = parser.parse("ALTER TABLE orders ADD COLUMN check_flag boolean DEFAULT true").orElseThrow();
        assertThat(f.operation()).isEqualTo(DdlOperation.ADD_COLUMN);
        assertThat(f.column()).isEqualTo("check_flag");
    }

    @Test
    void addColumnDefaultLiteralMentioningReferences_hasNoReferencedTable() {
        var f = parser.parse(
            "ALTER TABLE t ADD COLUMN note text DEFAULT 'see references docs(1)'").orElseThrow();
        assertThat(f.referencedTable()).isNull();
    }

    @Test
    void dropColumn_capturesColumnName() {
        var f = parser.parse("ALTER TABLE orders DROP COLUMN c").orElseThrow();
        assertThat(f.operation()).isEqualTo(DdlOperation.DROP_COLUMN);
        assertThat(f.column()).isEqualTo("c");
    }

    @Test
    void renameColumn_capturesNewNameOnly() {
        var f = parser.parse("ALTER TABLE orders RENAME COLUMN a TO b").orElseThrow();
        assertThat(f.operation()).isEqualTo(DdlOperation.RENAME_COLUMN);
        assertThat(f.newColumnName()).isEqualTo("b");
        assertThat(f.column()).isNull();
    }

    // The parser used to keep only the first of several statements, so a hidden DROP TABLE
    // riding along after a harmless ALTER reported SAFE. Must fail closed instead.
    @Test
    void multiStatementSql_returnsEmptySoCallerFailsClosed() {
        assertThat(parser.parse("ALTER TABLE t ADD COLUMN a text; DROP TABLE users;")).isEmpty();
    }

    @Test
    void singleStatementWithTrailingSemicolon_stillParses() {
        var f = parser.parse("ALTER TABLE orders ADD COLUMN a text;").orElseThrow();
        assertThat(f.operation()).isEqualTo(DdlOperation.ADD_COLUMN);
    }

    // DROP CONSTRAINT shares operation=DROP with DROP COLUMN but names no column; it used to
    // be misclassified as DROP_COLUMN with a false "metadata-only, VACUUM reclaims it" reason
    // and locks that omitted the referenced table. Must fail closed instead.
    @Test
    void dropConstraint_returnsEmptySoCallerFailsClosed() {
        assertThat(parser.parse("ALTER TABLE pc_child DROP CONSTRAINT fk")).isEmpty();
    }

    @Test
    void dropConstraintCascade_returnsEmptySoCallerFailsClosed() {
        assertThat(parser.parse("ALTER TABLE pc_child DROP CONSTRAINT fk CASCADE")).isEmpty();
    }

    // The DROP CONSTRAINT fix must not break the ordinary DROP COLUMN path it shares an
    // operation code with.
    @Test
    void dropColumnStillClassifiesCorrectly_afterDropConstraintFix() {
        var f = parser.parse("ALTER TABLE orders DROP COLUMN c").orElseThrow();
        assertThat(f.operation()).isEqualTo(DdlOperation.DROP_COLUMN);
        assertThat(f.column()).isEqualTo("c");
    }

    // DROP NOT NULL used to fall into the same weak ALTER branch as ALTER COLUMN TYPE and
    // was misreported as a table-rewriting type change. It is metadata-only, but no existing
    // rule models that correctly, so it must report UNKNOWN rather than a wrong DANGER.
    @Test
    void dropNotNull_returnsEmptySoCallerFailsClosed() {
        assertThat(parser.parse("ALTER TABLE orders ALTER COLUMN a DROP NOT NULL")).isEmpty();
    }

    // NOT VALID used to be detected with a regex whose \s+ tail was quadratic on attacker-
    // controlled input (CodeQL alerts 152/153) — 80KB of padding stalled parse() for 35s.
    // The index-based scan must stay linear regardless of how much whitespace precedes it.
    @Test
    void notValidDetection_staysFastOnAdversarialWhitespace() {
        String sql = "ALTER TABLE orders ADD CONSTRAINT ck CHECK (id > 0) NOT VALID"
            + " ".repeat(50_000);
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            var f = parser.parse(sql).orElseThrow();
            assertThat(f.notValid()).isTrue();
        });
    }

    @Test
    void notValidWithMultipleInternalSpaces_stillDetected() {
        var f = parser.parse("ALTER TABLE orders ADD CONSTRAINT ck CHECK (id > 0) NOT    VALID").orElseThrow();
        assertThat(f.notValid()).isTrue();
    }

    @Test
    void notValidWithTrailingSemicolon_stillParsesAndDetected() {
        var f = parser.parse("ALTER TABLE orders ADD CONSTRAINT ck CHECK (id > 0) NOT VALID;").orElseThrow();
        assertThat(f.operation()).isEqualTo(DdlOperation.ADD_CHECK);
        assertThat(f.notValid()).isTrue();
    }
}
