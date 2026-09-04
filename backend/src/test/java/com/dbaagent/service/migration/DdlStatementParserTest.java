package com.dbaagent.service.migration;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

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
}
