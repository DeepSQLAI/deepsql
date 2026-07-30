package com.dbaagent.service;

import com.dbaagent.model.SlowQuery;
import com.dbaagent.model.SlowQueryAnalysis;
import com.dbaagent.model.SlowQuerySample;
import com.dbaagent.repository.ConnectionAnalyticsConfigRepository;
import com.dbaagent.repository.SlowQueryCustomerDayRepository;
import com.dbaagent.repository.SlowQueryCustomerRepository;
import com.dbaagent.repository.SlowQuerySampleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression for the gating bug: `slow_query_sample` (and therefore
 * `slow-queries samples`) must be populated from a literal-bearing slow log
 * even when NO tenant column is configured. Previously the writer early-returned
 * unless a tenant column existed, so `samples` stayed empty regardless of the
 * normalization/fingerprint/literal fixes.
 */
class SlowQueryCustomerAttributionServiceTest {

    private ConnectionAnalyticsConfigRepository configRepository;
    private SlowQuerySampleRepository sampleRepository;
    private EnhancedSqlParserService sqlParserService;
    private SlowQueryCustomerAttributionService service;

    private static final String CONN = "conn-1";

    @BeforeEach
    void setUp() {
        configRepository = mock(ConnectionAnalyticsConfigRepository.class);
        sampleRepository = mock(SlowQuerySampleRepository.class);
        sqlParserService = mock(EnhancedSqlParserService.class);
        service = new SlowQueryCustomerAttributionService(
            configRepository,
            mock(SlowQueryCustomerRepository.class),
            sampleRepository,
            mock(SlowQueryCustomerDayRepository.class),
            sqlParserService,
            mock(ConnectionService.class));
    }

    private SlowQueryAnalysis analysisWith(SlowQuery... queries) {
        return SlowQueryAnalysis.builder().topSlowQueries(List.of(queries)).build();
    }

    @Test
    void writesLiteralSample_evenWithoutTenantConfig() {
        // No tenant column configured for this connection.
        when(configRepository.findById(CONN)).thenReturn(Optional.empty());

        SlowQuery q = SlowQuery.builder()
            .queryText("select * from t where entity_id = $1")
            .sampleQuery("select * from t where entity_id = '471'")
            .avgExecutionTimeMs(1234.0)
            .build();

        service.attributeSamples(CONN, analysisWith(q), SlowQuerySample.Source.SLOW_LOG);

        ArgumentCaptor<SlowQuerySample> captor = ArgumentCaptor.forClass(SlowQuerySample.class);
        verify(sampleRepository).save(captor.capture());
        SlowQuerySample saved = captor.getValue();

        assertNull(saved.getCustomerId(), "no tenant column → customerId must be null");
        assertNotNull(saved.getFingerprint(), "sample must carry the canonical fingerprint");
        assertTrue(saved.getRawSql().contains("471"), "sample must keep the real literal");
        // sqlParserService must not be touched when there is no tenant column.
        verify(sqlParserService, never()).extractWhereClauseLiterals(any());
    }

    @Test
    void skipsWhenNoLiteralSampleAvailable() {
        // Live pg_stat_statements style: only $N placeholders, no sampleQuery.
        when(configRepository.findById(CONN)).thenReturn(Optional.empty());

        SlowQuery q = SlowQuery.builder()
            .queryText("select * from t where entity_id = $1")
            .sampleQuery(null)
            .avgExecutionTimeMs(1234.0)
            .build();

        service.attributeSamples(CONN, analysisWith(q), SlowQuerySample.Source.SLOW_LOG);

        verify(sampleRepository, never()).save(any());
    }
}
