package com.dbaagent.service;

import com.dbaagent.model.KeyColumnAnalysis;
import com.dbaagent.repository.*;
import com.dbaagent.repository.brain.BrainRuleRepository;
import com.dbaagent.service.brain.analysis.ColumnProfilingService;
import com.dbaagent.service.brain.keycolumn.ColumnValueCollectionService;
import com.dbaagent.service.brain.keycolumn.KeyColumnAnalysisService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class KeyColumnAnalysisScoringTest {

    @Test
    void enhancedScoreHonorsRecencyAndSlowPerfBoosts() throws Exception {
        KeyColumnAnalysisService service = new KeyColumnAnalysisService(
            mock(EnhancedSqlParserService.class),
            mock(KeyColumnAnalysisRepository.class),
            mock(ColumnAntiPatternRepository.class),
            mock(SlowQueryHistoryRepository.class),
            mock(SlowQueryHistoryService.class),
            mock(QueryLineageRepository.class),
            mock(QueryPerformanceHistoryRepository.class),
            mock(CompositeIndexRecommendationRepository.class),
            mock(ColumnProfileRepository.class),
            mock(BrainRuleRepository.class),
            mock(QueryExecutorService.class),
            mock(SkewAnalysisService.class),
            mock(CredentialService.class),
            mock(ColumnDisambiguationRepository.class),
            mock(SchemaScannerService.class),
            mock(SqlUsageService.class),
            mock(ColumnValueCollectionService.class),
            mock(ColumnProfilingService.class),
            mock(PlatformTransactionManager.class)
        );

        KeyColumnAnalysis base = KeyColumnAnalysis.builder()
            .importanceScore(BigDecimal.valueOf(50))
            .recencyScore(BigDecimal.valueOf(50))
            .frequencyScore(BigDecimal.ZERO)
            .slowQueryUsage(0)
            .performanceHistoryUsage(0)
            .build();

        KeyColumnAnalysis boosted = KeyColumnAnalysis.builder()
            .importanceScore(BigDecimal.valueOf(50))
            .recencyScore(BigDecimal.valueOf(50))
            .frequencyScore(BigDecimal.ZERO)
            .slowQueryUsage(10)
            .performanceHistoryUsage(50)
            .build();

        KeyColumnAnalysis decayed = KeyColumnAnalysis.builder()
            .importanceScore(BigDecimal.valueOf(50))
            .recencyScore(BigDecimal.valueOf(25))
            .frequencyScore(BigDecimal.ZERO)
            .slowQueryUsage(0)
            .performanceHistoryUsage(0)
            .build();

        List<KeyColumnAnalysis> analyses = Arrays.asList(base, boosted, decayed);

        Method method = KeyColumnAnalysisService.class
            .getDeclaredMethod("calculateEnhancedScore", List.class);
        method.setAccessible(true);
        method.invoke(service, analyses);

        assertEquals(50.0, base.getEnhancedImportanceScore().doubleValue(), 0.01);
        assertTrue(boosted.getEnhancedImportanceScore().doubleValue() > base.getEnhancedImportanceScore().doubleValue());
        assertTrue(decayed.getEnhancedImportanceScore().doubleValue() < base.getEnhancedImportanceScore().doubleValue());
    }
}
