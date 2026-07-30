package com.dbaagent.service.index;

import com.dbaagent.model.KeyColumnAnalysis;
import com.dbaagent.repository.KeyColumnAnalysisRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompositeIndexPlannerTest {

    @Mock private KeyColumnAnalysisRepository keyColumnAnalysisRepository;

    private CompositeIndexPlanner planner;

    @BeforeEach
    void setUp() {
        planner = new CompositeIndexPlanner(keyColumnAnalysisRepository);
        ReflectionTestUtils.setField(planner, "minWorkloadMs", 60_000L);
        ReflectionTestUtils.setField(planner, "maxColumns", 3);
    }

    private KeyColumnAnalysis col(String table, String column, double selectivity) {
        return KeyColumnAnalysis.builder()
            .connectionId("c1")
            .tableName(table)
            .columnName(column)
            .selectivity(BigDecimal.valueOf(selectivity))
            .build();
    }

    @Test
    void equalityColumnsAreOrderedBySelectivityDescending() {
        when(keyColumnAnalysisRepository.findByConnectionIdOrderByImportanceScoreDesc("c1"))
            .thenReturn(List.of(
                col("orders", "customer_id", 0.99), // very selective
                col("orders", "status", 0.20)        // not selective
            ));

        CompositeIndexPlanner.CandidateGroup g = new CompositeIndexPlanner.CandidateGroup("orders");
        g.equalityColumns.add(new TableColumnRef("orders", "status"));
        g.equalityColumns.add(new TableColumnRef("orders", "customer_id"));
        g.workloadScoreMs = 120_000L;
        g.evidenceCount = 5;

        List<CompositeIndexPlanner.Plan> plans = planner.plan("c1", List.of(g));
        assertThat(plans).hasSize(1);
        // High-selectivity column first.
        assertThat(plans.get(0).columnsInOrder()).containsExactly("customer_id", "status");
    }

    @Test
    void rangeColumnGoesLastEvenWithHighSelectivity() {
        when(keyColumnAnalysisRepository.findByConnectionIdOrderByImportanceScoreDesc("c1"))
            .thenReturn(List.of(
                col("orders", "status", 0.20),
                col("orders", "created_at", 0.95)    // selective but range → last
            ));

        CompositeIndexPlanner.CandidateGroup g = new CompositeIndexPlanner.CandidateGroup("orders");
        g.equalityColumns.add(new TableColumnRef("orders", "status"));
        g.rangeColumns.add(new TableColumnRef("orders", "created_at"));
        g.workloadScoreMs = 120_000L;
        g.evidenceCount = 5;

        List<CompositeIndexPlanner.Plan> plans = planner.plan("c1", List.of(g));
        assertThat(plans.get(0).columnsInOrder()).containsExactly("status", "created_at");
    }

    @Test
    void orderByAppendedOnlyWhenNoRangeColumn() {
        when(keyColumnAnalysisRepository.findByConnectionIdOrderByImportanceScoreDesc("c1"))
            .thenReturn(List.of());

        CompositeIndexPlanner.CandidateGroup g = new CompositeIndexPlanner.CandidateGroup("orders");
        g.equalityColumns.add(new TableColumnRef("orders", "customer_id"));
        g.rangeColumns.add(new TableColumnRef("orders", "created_at"));
        g.orderByColumns.add(new TableColumnRef("orders", "id"));  // would-be suffix
        g.workloadScoreMs = 120_000L;
        g.evidenceCount = 5;

        List<CompositeIndexPlanner.Plan> plans = planner.plan("c1", List.of(g));
        // Range present → ORDER BY cannot ride the index; suffix not appended.
        assertThat(plans.get(0).columnsInOrder()).doesNotContain("id");
    }

    @Test
    void orderByAppendedWhenAllEqualityPrefix() {
        when(keyColumnAnalysisRepository.findByConnectionIdOrderByImportanceScoreDesc("c1"))
            .thenReturn(List.of());

        CompositeIndexPlanner.CandidateGroup g = new CompositeIndexPlanner.CandidateGroup("orders");
        g.equalityColumns.add(new TableColumnRef("orders", "customer_id"));
        g.orderByColumns.add(new TableColumnRef("orders", "created_at"));
        g.workloadScoreMs = 120_000L;
        g.evidenceCount = 5;

        List<CompositeIndexPlanner.Plan> plans = planner.plan("c1", List.of(g));
        assertThat(plans.get(0).columnsInOrder()).containsExactly("customer_id", "created_at");
    }

    @Test
    void planCapsAtThreeColumns() {
        when(keyColumnAnalysisRepository.findByConnectionIdOrderByImportanceScoreDesc("c1"))
            .thenReturn(List.of());

        CompositeIndexPlanner.CandidateGroup g = new CompositeIndexPlanner.CandidateGroup("orders");
        g.equalityColumns.add(new TableColumnRef("orders", "a"));
        g.equalityColumns.add(new TableColumnRef("orders", "b"));
        g.equalityColumns.add(new TableColumnRef("orders", "c"));
        g.equalityColumns.add(new TableColumnRef("orders", "d"));
        g.workloadScoreMs = 120_000L;
        g.evidenceCount = 5;

        List<CompositeIndexPlanner.Plan> plans = planner.plan("c1", List.of(g));
        assertThat(plans.get(0).columnsInOrder()).hasSize(3);
    }

    @Test
    void belowMinWorkloadGroupsAreSkipped() {
        when(keyColumnAnalysisRepository.findByConnectionIdOrderByImportanceScoreDesc("c1"))
            .thenReturn(List.of());

        CompositeIndexPlanner.CandidateGroup g = new CompositeIndexPlanner.CandidateGroup("orders");
        g.equalityColumns.add(new TableColumnRef("orders", "status"));
        g.workloadScoreMs = 1_000L; // 1s, below the 60s default
        g.evidenceCount = 5;

        assertThat(planner.plan("c1", List.of(g))).isEmpty();
    }

    @Test
    void emptyGroupsListYieldsNoPlans() {
        assertThat(planner.plan("c1", List.of())).isEmpty();
    }

    @Test
    void planIndexNameAndCreateStatementUseOrderedColumns() {
        when(keyColumnAnalysisRepository.findByConnectionIdOrderByImportanceScoreDesc("c1"))
            .thenReturn(List.of());

        CompositeIndexPlanner.CandidateGroup g = new CompositeIndexPlanner.CandidateGroup("orders");
        g.equalityColumns.add(new TableColumnRef("orders", "customer_id"));
        g.workloadScoreMs = 120_000L;

        CompositeIndexPlanner.Plan plan = planner.plan("c1", List.of(g)).get(0);
        assertThat(plan.indexName()).isEqualTo("idx_orders_customer_id");
        assertThat(plan.createStatement()).isEqualTo("CREATE INDEX idx_orders_customer_id ON orders (customer_id);");
    }
}
