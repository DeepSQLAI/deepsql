package com.dbaagent.service;

import com.dbaagent.repository.CredentialRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class PerformanceRecommendationRefreshScheduler {

    private final CredentialRepository credentialRepository;
    private final IndexRecommendationService indexRecommendationService;
    private final PerformanceActionAggregatorService performanceActionAggregatorService;

    @Value("${performance.recommendations.refresh.enabled:true}")
    private boolean refreshEnabled;

    public void refreshAllConnections() {
        if (!refreshEnabled) {
            return;
        }

        int refreshedConnections = 0;
        for (String connectionId : credentialRepository.findAllIds()) {
            try {
                indexRecommendationService.refreshRecommendations(connectionId);
                performanceActionAggregatorService.refreshActions(connectionId);
                refreshedConnections++;
            } catch (Exception e) {
                log.warn("Could not refresh performance recommendations for {}: {}", connectionId, e.getMessage());
            }
        }

        log.info("Performance recommendation refresh complete for {} connection(s)", refreshedConnections);
    }
}
