package com.dbaagent.service;

import com.dbaagent.dto.WorkloadAnalysisData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PreAggregationAnalyzerTest {

    @Mock private ConnectionService connectionService;

    private PreAggregationAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new PreAggregationAnalyzer(connectionService);
        ReflectionTestUtils.setField(analyzer, "minSharedQueries", 2);
        lenient().when(connectionService.getDbType("pg")).thenReturn("postgres");
        lenient().when(connectionService.getDbType("my")).thenReturn("mysql");
    }

    private static PreAggregationAnalyzer.QueryInput q(String fp, String sql, long score) {
        return new PreAggregationAnalyzer.QueryInput(fp, sql, score);
    }

    @Test
    void detectsSharedShape_acrossTwoQueries_postgresMaterializedView() {
        // Two queries, same table + same GROUP BY dimension, different measures
        // and different filters (filters are ignored for shape identity).
        List<PreAggregationAnalyzer.QueryInput> inputs = List.of(
            q("f1", "SELECT region, SUM(amount) FROM orders WHERE status = 'paid' GROUP BY region", 30000),
            q("f2", "SELECT region, COUNT(*) FROM orders WHERE created_at > '2026-01-01' GROUP BY region", 20000));

        List<WorkloadAnalysisData.PreAggRec> recs = analyzer.analyze("pg", inputs);

        assertEquals(1, recs.size(), "the two queries share one aggregate shape");
        WorkloadAnalysisData.PreAggRec r = recs.get(0);
        assertEquals(2, r.getSharedByQueries());
        assertEquals(50000L, r.getWorkloadScoreMs(), "workload scores accumulate");
        assertTrue(r.getDimensions().contains("region"));
        // Both measures unioned.
        assertTrue(r.getMeasures().stream().anyMatch(m -> m.contains("sum(amount)")));
        assertTrue(r.getMeasures().stream().anyMatch(m -> m.contains("count(*)")));
        // Postgres → materialized view + CONCURRENTLY-enabling unique index.
        assertTrue(r.getMaterializationDdl().contains("CREATE MATERIALIZED VIEW"));
        assertTrue(r.getMaterializationDdl().contains("CREATE UNIQUE INDEX"));
        assertTrue(r.getRefreshStrategy().contains("REFRESH MATERIALIZED VIEW CONCURRENTLY"));
    }

    @Test
    void mysqlEmitsSummaryTable_notMaterializedView() {
        List<PreAggregationAnalyzer.QueryInput> inputs = List.of(
            q("f1", "SELECT region, SUM(amount) FROM orders GROUP BY region", 30000),
            q("f2", "SELECT region, AVG(amount) FROM orders GROUP BY region", 25000));

        List<WorkloadAnalysisData.PreAggRec> recs = analyzer.analyze("my", inputs);

        assertEquals(1, recs.size());
        WorkloadAnalysisData.PreAggRec r = recs.get(0);
        assertFalse(r.getMaterializationDdl().contains("CREATE MATERIALIZED VIEW"));
        assertTrue(r.getMaterializationDdl().contains("CREATE TABLE"));
        assertTrue(r.getRefreshStrategy().toLowerCase().contains("no materialized views"));
    }

    @Test
    void differentDimensionsAreDistinctShapes_belowThreshold_skipped() {
        // Same table but different GROUP BY → two shapes, each seen once.
        // Neither hits min-shared=2, and neither is an expensive singleton.
        List<PreAggregationAnalyzer.QueryInput> inputs = List.of(
            q("f1", "SELECT region, SUM(amount) FROM orders GROUP BY region", 1000),
            q("f2", "SELECT product, SUM(amount) FROM orders GROUP BY product", 1000));

        List<WorkloadAnalysisData.PreAggRec> recs = analyzer.analyze("pg", inputs);

        assertTrue(recs.isEmpty(), "distinct shapes seen once each, below cost threshold → no recs");
    }

    @Test
    void expensiveSingletonAggregate_isRecommended() {
        // One query, but very expensive (≥60s/period) → worth materializing alone.
        List<PreAggregationAnalyzer.QueryInput> inputs = List.of(
            q("f1", "SELECT region, day, SUM(amount) FROM orders GROUP BY region, day", 90000));

        List<WorkloadAnalysisData.PreAggRec> recs = analyzer.analyze("pg", inputs);

        assertEquals(1, recs.size());
        assertEquals(1, recs.get(0).getSharedByQueries());
        assertEquals(2, recs.get(0).getDimensions().size());
    }

    @Test
    void nonAggregateQueries_areIgnored() {
        List<PreAggregationAnalyzer.QueryInput> inputs = List.of(
            q("f1", "SELECT * FROM orders WHERE region = 'us'", 50000),
            q("f2", "SELECT id, region FROM orders ORDER BY created_at", 40000));

        List<WorkloadAnalysisData.PreAggRec> recs = analyzer.analyze("pg", inputs);

        assertTrue(recs.isEmpty(), "no GROUP BY + aggregate → not pre-agg candidates");
    }

    @Test
    void unparseableQuery_isSkippedNotThrown() {
        List<PreAggregationAnalyzer.QueryInput> inputs = List.of(
            q("f1", "this is not valid sql at all", 10000),
            q("f2", "SELECT region, SUM(amount) FROM orders GROUP BY region", 70000));

        List<WorkloadAnalysisData.PreAggRec> recs = analyzer.analyze("pg", inputs);

        // The valid expensive singleton still surfaces; the garbage is skipped.
        assertEquals(1, recs.size());
    }
}
