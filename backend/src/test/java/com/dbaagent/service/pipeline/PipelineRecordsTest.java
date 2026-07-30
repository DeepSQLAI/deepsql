package com.dbaagent.service.pipeline;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class PipelineRecordsTest {

    @Test
    void filterColumnQualifiedName() {
        var fc = new FilterColumn("bookings", "status");
        assertThat(fc.qualifiedName()).isEqualTo("bookings.status");
    }

    @Test
    void resolvedContextEmpty() {
        var empty = ResolvedContext.empty();
        assertThat(empty.isEmpty()).isTrue();
        assertThat(empty.confidence()).isEqualTo(ResolvedContext.Confidence.LOW);
    }

    @Test
    void columnValueContextEmpty() {
        var empty = ColumnValueContext.empty();
        assertThat(empty.isEmpty()).isTrue();
        assertThat(empty.formattedContext()).isEmpty();
    }

    @Test
    void validationResultFactoryMethods() {
        var valid = ValidationResult.valid("{\"plan\": []}");
        assertThat(valid.valid()).isTrue();
        assertThat(valid.error()).isNull();

        var invalid = ValidationResult.invalid("column X not found");
        assertThat(invalid.valid()).isFalse();
        assertThat(invalid.explainPlan()).isNull();
    }

    @Test
    void pipelineResultHasSql() {
        var withSql = new PipelineResult("SELECT 1", "response", false,
            ResolvedContext.empty(), ColumnValueContext.empty(),
            ValidationResult.valid("{}"), List.of("step1"), 100L);
        assertThat(withSql.hasSql()).isTrue();

        var withoutSql = new PipelineResult(null, "no sql", false,
            ResolvedContext.empty(), ColumnValueContext.empty(),
            null, List.of(), 50L);
        assertThat(withoutSql.hasSql()).isFalse();
    }

    @Test
    void pipelineContextRejectsNulls() {
        assertThatThrownBy(() -> new PipelineContext(
            null, "q", "PG", "", null, null, "", "", "", "", "", List.of(), null
        )).isInstanceOf(NullPointerException.class);
    }

    @Test
    void pipelineContextDefaultsNoopListener() {
        var ctx = new PipelineContext(
            "conn1", "q", "PG", "", null, null, "", "", "", "", "", List.of(), null
        );
        assertThat(ctx.progressListener()).isNotNull();
        ctx.progressListener().onProgress("test", "msg", Map.of());
    }

    @Test
    void adaptedSqlResultHasExplicitFields() {
        var result = new AdaptedSqlResult(
            "SELECT 1", "Based on...\n```sql\nSELECT 1\n```", "original q", 0.95
        );
        assertThat(result.adaptedSql()).isEqualTo("SELECT 1");
        assertThat(result.syntheticResponse()).contains("```sql");
        assertThat(result.originalQuestion()).isEqualTo("original q");
    }
}
