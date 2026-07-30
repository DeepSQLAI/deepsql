package com.dbaagent.service;

import com.dbaagent.model.KeyColumnAnalysis;
import com.dbaagent.model.brain.ColumnStatistics;
import com.dbaagent.provider.DatabaseProviderRegistry;
import com.dbaagent.repository.KeyColumnAnalysisRepository;
import com.dbaagent.repository.QueryFingerprintRepository;
import com.dbaagent.repository.QueryOptimizationCacheRepository;
import com.dbaagent.repository.brain.ColumnStatisticsRepository;
import com.dbaagent.service.optd.OptdOptimizationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.cache.CacheManager;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link QueryOptimizationService#buildEnrichmentContext} — verifies
 * that precomputed Brain enrichment ({@code ColumnStatistics} + {@code KeyColumnAnalysis})
 * is correctly folded into the optimization prompt, and that missing/failing
 * enrichment never blocks query optimization.
 */
class QueryOptimizationServiceTest {

    private static final String CONN = "conn-1";

    private ColumnStatisticsRepository columnStatisticsRepository;
    private KeyColumnAnalysisRepository keyColumnAnalysisRepository;

    private QueryOptimizationService newService() {
        columnStatisticsRepository = mock(ColumnStatisticsRepository.class);
        keyColumnAnalysisRepository = mock(KeyColumnAnalysisRepository.class);
        return new QueryOptimizationService(
            mock(ChatClient.Builder.class),
            mock(CredentialService.class),
            mock(ConnectionService.class),
            mock(ExplainPlanService.class),
            mock(SchemaScannerService.class),
            mock(DatabaseProviderRegistry.class),
            mock(QueryOptimizationCacheRepository.class),
            mock(QueryFingerprintRepository.class),
            columnStatisticsRepository,
            keyColumnAnalysisRepository,
            mock(OptdOptimizationService.class),
            mock(OptimizationCandidateService.class),
            mock(ObjectMapper.class),
            mock(CacheManager.class),
            mock(RewritePlanScorer.class));
    }

    private String invoke(QueryOptimizationService svc, List<String> tables) throws Exception {
        Method m = QueryOptimizationService.class
            .getDeclaredMethod("buildEnrichmentContext", String.class, List.class);
        m.setAccessible(true);
        return (String) m.invoke(svc, CONN, tables);
    }

    private ColumnStatistics colStat(String table, String column, Long distinct,
                                     Long nullCount, Double nullFraction, Long rowCount,
                                     List<String> mcv) {
        return ColumnStatistics.builder()
            .connectionId(CONN).tableName(table).columnName(column)
            .distinctCount(distinct).nullCount(nullCount).nullFraction(nullFraction)
            .rowCount(rowCount).mcvValues(mcv)
            .build();
    }

    private KeyColumnAnalysis keyCol(String table, String column, BigDecimal selectivity,
                                     Long distinct, BigDecimal importance, Boolean skewed) {
        return KeyColumnAnalysis.builder()
            .connectionId(CONN).tableName(table).columnName(column)
            .selectivity(selectivity).distinctCount(distinct)
            .importanceScore(importance).isHeavilySkewed(skewed)
            .build();
    }

    @Test
    void returnsNullWhenNoAffectedTables() throws Exception {
        QueryOptimizationService svc = newService();
        assertNull(invoke(svc, List.of()));
        assertNull(invoke(svc, null));
    }

    @Test
    void returnsNullWhenNoEnrichmentRowsExist() throws Exception {
        QueryOptimizationService svc = newService();
        when(columnStatisticsRepository.findByConnectionIdAndTableName(anyString(), anyString()))
            .thenReturn(List.of());
        when(keyColumnAnalysisRepository
            .findByConnectionIdAndTableNameOrderByImportanceScoreDesc(anyString(), anyString()))
            .thenReturn(List.of());

        assertNull(invoke(svc, List.of("bookings")));
    }

    @Test
    void columnStatisticsOnlyEmitsEnumAndSelectivityFlags() throws Exception {
        QueryOptimizationService svc = newService();
        when(columnStatisticsRepository.findByConnectionIdAndTableName(CONN, "bookings"))
            .thenReturn(List.of(
                colStat("bookings", "status", 4L, 0L, 0.0, 1000L,
                    List.of("CONFIRMED", "CANCELLED", "PENDING", "NOSHOW")),
                colStat("bookings", "guest_email", 9500L, 70L, 0.07, 100000L, null)));
        when(keyColumnAnalysisRepository
            .findByConnectionIdAndTableNameOrderByImportanceScoreDesc(CONN, "bookings"))
            .thenReturn(List.of());

        String ctx = invoke(svc, List.of("bookings"));
        assertNotNull(ctx);
        assertTrue(ctx.contains("Table `bookings`:"), ctx);
        assertTrue(ctx.contains("enum-like: [CONFIRMED, CANCELLED, PENDING, NOSHOW]"), ctx);
        // high distinct count with no selectivity value -> leading-edge note on the distinct fact
        assertTrue(ctx.contains("9,500 distinct (high selectivity — strong index leading-edge candidate)"), ctx);
        assertTrue(ctx.contains("null fraction 0.07"), ctx);
    }

    @Test
    void keyColumnAnalysisOnlyFlagsSelectiveLeadingEdge() throws Exception {
        QueryOptimizationService svc = newService();
        when(columnStatisticsRepository.findByConnectionIdAndTableName(CONN, "bookings"))
            .thenReturn(List.of());
        when(keyColumnAnalysisRepository
            .findByConnectionIdAndTableNameOrderByImportanceScoreDesc(CONN, "bookings"))
            .thenReturn(List.of(
                keyCol("bookings", "hotel_id", new BigDecimal("0.41"), 1820L,
                    new BigDecimal("90"), false)));

        String ctx = invoke(svc, List.of("bookings"));
        assertNotNull(ctx);
        assertTrue(ctx.contains("selectivity 0.41 (high selectivity — strong index leading-edge candidate)"), ctx);
    }

    @Test
    void mergesOverlappingColumnFromBothSources() throws Exception {
        QueryOptimizationService svc = newService();
        when(keyColumnAnalysisRepository
            .findByConnectionIdAndTableNameOrderByImportanceScoreDesc(CONN, "orders"))
            .thenReturn(List.of(
                keyCol("orders", "user_id", new BigDecimal("0.30"), null,
                    new BigDecimal("80"), false)));
        // same column, different casing — must merge into one line
        when(columnStatisticsRepository.findByConnectionIdAndTableName(CONN, "orders"))
            .thenReturn(List.of(
                colStat("orders", "USER_ID", 900L, 0L, 0.0, 3000L, null)));

        String ctx = invoke(svc, List.of("orders"));
        assertNotNull(ctx);
        int occurrences = ctx.split("- user_id:", -1).length - 1;
        assertEquals(1, occurrences, "overlapping column should be printed exactly once: " + ctx);
        assertTrue(ctx.contains("900 distinct"), ctx);
        assertTrue(ctx.contains("selectivity 0.30"), ctx);
    }

    @Test
    void skewedColumnSuggestsPartialIndex() throws Exception {
        QueryOptimizationService svc = newService();
        when(columnStatisticsRepository.findByConnectionIdAndTableName(CONN, "bookings"))
            .thenReturn(List.of());
        when(keyColumnAnalysisRepository
            .findByConnectionIdAndTableNameOrderByImportanceScoreDesc(CONN, "bookings"))
            .thenReturn(List.of(
                keyCol("bookings", "created_at", new BigDecimal("0.20"), 500L,
                    new BigDecimal("70"), true)));

        String ctx = invoke(svc, List.of("bookings"));
        assertNotNull(ctx);
        assertTrue(ctx.contains("skewed distribution — consider partial index"), ctx);
    }

    @Test
    void repositoryFailureNeverBlocksOptimization() throws Exception {
        QueryOptimizationService svc = newService();
        when(columnStatisticsRepository.findByConnectionIdAndTableName(anyString(), anyString()))
            .thenThrow(new RuntimeException("brain table unavailable"));
        when(keyColumnAnalysisRepository
            .findByConnectionIdAndTableNameOrderByImportanceScoreDesc(anyString(), anyString()))
            .thenThrow(new RuntimeException("brain table unavailable"));

        // must swallow the failure and return null rather than propagate
        assertNull(invoke(svc, List.of("bookings")));
    }
}
