package com.dbaagent.service.scheduler;

import com.dbaagent.repository.CredentialRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class BrainMetadataRefreshScheduler {

    private final CredentialRepository credentialRepository;
    private final ObjectProvider<BrainInitSchedulerService> brainInitSchedulerServiceProvider;

    @Value("${brain.init.refresh.enabled:true}")
    private boolean refreshEnabled;

    @Value("${brain.init.refresh.interval-hours:24}")
    private long refreshIntervalHours;

    public void refreshStaleConnections() {
        if (!refreshEnabled) {
            return;
        }

        int scheduled = 0;
        BrainInitSchedulerService brainInitSchedulerService = brainInitSchedulerServiceProvider.getIfAvailable();
        if (brainInitSchedulerService == null) {
            log.warn("Skipping periodic Brain metadata refresh because BrainInitSchedulerService is unavailable");
            return;
        }

        for (String connectionId : credentialRepository.findAllIds()) {
            try {
                BrainInitPlan plan = brainInitSchedulerService.planAndScheduleInit(connectionId, false);
                if (!plan.skipped()) {
                    scheduled++;
                }
            } catch (Exception e) {
                log.debug("Could not schedule periodic brain refresh for {}: {}", connectionId, e.getMessage());
            }
        }

        log.info("Periodic Brain metadata refresh check complete: scheduled {} connection(s)", scheduled);
    }
}
