package com.dbaagent.service;

import com.dbaagent.model.SlowLogSourceConfig;
import com.dbaagent.provider.DatabaseProviderRegistry;
import com.dbaagent.repository.ConnectionAnalyticsConfigRepository;
import com.dbaagent.repository.CredentialRepository;
import com.dbaagent.repository.SlowLogSourceConfigRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the slow-log "enabled" self-heal added for the v1.5.1 bug
 * report (CloudWatch ingestion stuck disabled after upgrade). The live ingestion
 * path no longer gates on {@code enabled} (the per-config poll task was removed
 * in V102), so a config left disabled by that retired machinery must recover on
 * its own once a healthy ingest happens.
 */
@ExtendWith(MockitoExtension.class)
class SlowQueryDailyAnalysisServiceSelfHealTest {

    @Mock CredentialRepository credentialRepository;
    @Mock ConnectionAnalyticsConfigRepository configRepository;
    @Mock SlowLogSourceConfigRepository logSourceRepository;
    @Mock SlowLogIngestionService slowLogIngestionService;
    @Mock ConnectionService connectionService;
    @Mock DatabaseProviderRegistry providerRegistry;

    @InjectMocks SlowQueryDailyAnalysisService service;

    @Test
    void successfulRunSelfHealsStaleDisabledFlag() {
        SlowLogSourceConfig cfg = SlowLogSourceConfig.builder()
            .connectionId("c1")
            .enabled(false)          // stale, left over from the removed V102 poll
            .consecutiveDryRuns(0)
            .build();
        when(logSourceRepository.findByConnectionId("c1")).thenReturn(Optional.of(cfg));
        // No MySQL Slow_queries lookup: missing credential -> readDbSlowQueryCount returns null.
        when(credentialRepository.findById("c1")).thenReturn(Optional.empty());

        service.resetDrySource("c1");

        assertTrue(cfg.isEnabled(), "a healthy ingest must clear a stale enabled=false");
        verify(logSourceRepository).save(cfg);
    }

    @Test
    void alreadyEnabledHealthyRunMakesNoNeedlessWrite() {
        SlowLogSourceConfig cfg = SlowLogSourceConfig.builder()
            .connectionId("c2")
            .enabled(true)
            .consecutiveDryRuns(0)
            .build();
        when(logSourceRepository.findByConnectionId("c2")).thenReturn(Optional.of(cfg));
        when(credentialRepository.findById("c2")).thenReturn(Optional.empty());

        service.resetDrySource("c2");

        assertTrue(cfg.isEnabled());
        verify(logSourceRepository, never()).save(any());
    }
}
