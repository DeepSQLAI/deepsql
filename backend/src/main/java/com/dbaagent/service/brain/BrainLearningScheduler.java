package com.dbaagent.service.brain;

import com.dbaagent.service.brain.workload.WorkloadMetricsCollectorService;
import com.dbaagent.service.brain.workload.WorkloadCharacterizationService;
import com.dbaagent.service.brain.config.KnobIdentificationService;
import com.dbaagent.service.brain.query.CardinalityEstimationService;
import com.dbaagent.service.brain.query.AdaptivePlanScoringService;

import com.dbaagent.model.brain.*;
import com.dbaagent.repository.CredentialRepository;
import com.dbaagent.repository.brain.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Brain 2.0: Continuous Learning Scheduler
 *
 * Orchestrates background data collection and learning processes.
 * Implements the feedback loop that makes Brain 2.0 improve over time.
 *
 * Inspired by OtterTune's continuous learning approach.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BrainLearningScheduler {

    private final CredentialRepository connectionRepository;
    private final WorkloadMetricsCollectorService metricsCollectorService;
    private final WorkloadCharacterizationService workloadCharacterizationService;
    private final KnobIdentificationService knobIdentificationService;
    private final CardinalityEstimationService cardinalityEstimationService;
    private final AdaptivePlanScoringService adaptivePlanScoringService;
    private final BrainLearningProgressRepository learningProgressRepository;
    private final WorkloadMetricsSnapshotRepository snapshotRepository;
    private final BrainV2AlertRepository alertRepository;

    @Value("${brain.v2.learning.enabled:true}")
    private boolean learningEnabled;

    @Value("${brain.v2.learning.metrics-interval-minutes:15}")
    private int metricsIntervalMinutes;

    @Value("${brain.v2.learning.characterization-interval-hours:4}")
    private int characterizationIntervalHours;

    @Value("${brain.v2.learning.statistics-refresh-days:7}")
    private int statisticsRefreshDays;

    /**
     * Collect workload metrics periodically (every 15 minutes by default).
     */
    public void collectWorkloadMetrics() {
        if (!learningEnabled) {
            return;
        }

        log.debug("Brain V2: Starting scheduled metrics collection");

        List<String> connectionIds = connectionRepository.findAllIds();

        for (String connectionId : connectionIds) {
            try {
                // Check if we need to collect (don't collect too frequently)
                if (shouldCollectMetrics(connectionId)) {
                    metricsCollectorService.collectMetrics(connectionId);
                    updateLearningProgress(connectionId, "metrics");
                }
            } catch (Exception e) {
                log.debug("Could not collect metrics for connection {}: {}", connectionId, e.getMessage());
            }
        }
    }

    /**
     * Update workload characterization periodically (every 4 hours by default).
     */
    public void updateWorkloadCharacterization() {
        if (!learningEnabled) {
            return;
        }

        log.debug("Brain V2: Starting scheduled workload characterization");

        List<String> connectionIds = connectionRepository.findAllIds();

        for (String connectionId : connectionIds) {
            try {
                // Check if we have enough metrics to characterize
                long snapshotCount = snapshotRepository.countByConnectionId(connectionId);
                if (snapshotCount >= 10) {
                    WorkloadProfile profile = workloadCharacterizationService.characterizeWorkload(connectionId);

                    // Check for workload type change and alert
                    checkForWorkloadChange(connectionId, profile);

                    updateLearningProgress(connectionId, "characterization");
                }
            } catch (Exception e) {
                log.debug("Could not characterize workload for connection {}: {}", connectionId, e.getMessage());
            }
        }
    }

    /**
     * Update knob rankings daily.
     */
    public void updateKnobRankings() {
        if (!learningEnabled) {
            return;
        }

        log.info("Brain V2: Starting scheduled knob ranking update");

        List<String> connectionIds = connectionRepository.findAllIds();

        for (String connectionId : connectionIds) {
            try {
                // Only update if we have workload profile
                Optional<WorkloadProfile> profileOpt = workloadCharacterizationService.getProfile(connectionId);
                if (profileOpt.isPresent()) {
                    // Rank knobs for different target metrics
                    knobIdentificationService.identifyKnobs(connectionId, KnobRanking.TargetMetric.LATENCY);
                    knobIdentificationService.identifyKnobs(connectionId, KnobRanking.TargetMetric.THROUGHPUT);

                    updateLearningProgress(connectionId, "knobs");
                }
            } catch (Exception e) {
                log.debug("Could not update knob rankings for connection {}: {}", connectionId, e.getMessage());
            }
        }

        log.info("Brain V2: Completed scheduled knob ranking update");
    }

    /**
     * Refresh stale column statistics weekly.
     */
    public void refreshColumnStatistics() {
        if (!learningEnabled) {
            return;
        }

        log.info("Brain V2: Starting scheduled column statistics refresh");

        List<String> connectionIds = connectionRepository.findAllIds();

        for (String connectionId : connectionIds) {
            try {
                int refreshed = cardinalityEstimationService.refreshStaleStatistics(connectionId);
                if (refreshed > 0) {
                    log.info("Refreshed {} stale statistics for connection {}", refreshed, connectionId);
                    updateLearningProgress(connectionId, "statistics");
                }
            } catch (Exception e) {
                log.debug("Could not refresh statistics for connection {}: {}", connectionId, e.getMessage());
            }
        }

        log.info("Brain V2: Completed scheduled column statistics refresh");
    }

    /**
     * Check estimation accuracy and alert on degradation.
     */
    public void checkEstimationAccuracy() {
        if (!learningEnabled) {
            return;
        }

        log.debug("Brain V2: Checking cardinality estimation accuracy");

        List<String> connectionIds = connectionRepository.findAllIds();

        for (String connectionId : connectionIds) {
            try {
                Double avgError = adaptivePlanScoringService.getAverageCardinalityError(connectionId);

                if (avgError != null && (avgError > 50 || avgError < 0.02)) {
                    // Significant estimation error detected
                    BrainV2Alert alert = BrainV2Alert.cardinalityDegradation(
                        connectionId,
                        avgError,
                        "Average cardinality estimation error exceeds acceptable threshold");
                    alertRepository.save(alert);

                    log.warn("Brain V2: Cardinality degradation detected for connection {} (error: {})",
                        connectionId, avgError);
                }
            } catch (Exception e) {
                log.debug("Could not check estimation accuracy for connection {}: {}", connectionId, e.getMessage());
            }
        }
    }

    /**
     * Calculate Brain 2.0 readiness and log summary daily.
     */
    public void calculateReadiness() {
        if (!learningEnabled) {
            return;
        }

        log.info("Brain V2: Calculating learning readiness for all connections");

        List<BrainLearningProgress> allProgress = learningProgressRepository.findAll();

        int ready = 0;
        int learning = 0;
        int initializing = 0;

        for (BrainLearningProgress progress : allProgress) {
            progress.calculateReadiness();
            learningProgressRepository.save(progress);

            if (progress.getBrainV2Ready()) {
                ready++;
            } else if (progress.getReadinessPercent() >= 30) {
                learning++;
            } else {
                initializing++;
            }
        }

        Double avgReadiness = learningProgressRepository.calculateAverageReadiness();

        log.info("Brain V2 Readiness Summary: {} ready, {} learning, {} initializing (avg: {}%)",
            ready, learning, initializing, avgReadiness != null ? String.format("%.1f", avgReadiness) : "N/A");
    }

    // ========================================
    // Helper Methods
    // ========================================

    private boolean shouldCollectMetrics(String connectionId) {
        // Check last collection time
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(metricsIntervalMinutes - 1);

        WorkloadMetricsSnapshot latest = snapshotRepository.findLatestByConnectionId(connectionId);
        if (latest == null) {
            return true;
        }
        return latest.getCollectedAt().isBefore(threshold);
    }

    private void checkForWorkloadChange(String connectionId, WorkloadProfile newProfile) {
        if (newProfile.getWorkloadType() == null) {
            return;
        }

        // Get previous profile to compare
        Optional<WorkloadProfile> existingOpt = workloadCharacterizationService.getProfile(connectionId);

        if (existingOpt.isPresent()) {
            WorkloadProfile existing = existingOpt.get();

            // Check if workload type changed
            if (existing.getWorkloadType() != null &&
                existing.getWorkloadType() != newProfile.getWorkloadType()) {

                BrainV2Alert alert = BrainV2Alert.workloadChange(
                    connectionId,
                    existing.getWorkloadType().name(),
                    newProfile.getWorkloadType().name());
                alertRepository.save(alert);

                log.info("Brain V2: Workload type changed for connection {} from {} to {}",
                    connectionId, existing.getWorkloadType(), newProfile.getWorkloadType());
            }
        }
    }

    private void updateLearningProgress(String connectionId, String component) {
        try {
            BrainLearningProgress progress = learningProgressRepository.findByConnectionId(connectionId)
                .orElse(BrainLearningProgress.builder()
                    .connectionId(connectionId)
                    .createdAt(LocalDateTime.now())
                    .build());

            switch (component) {
                case "metrics" -> {
                    int current = progress.getWorkloadSnapshotsCollected() != null ?
                        progress.getWorkloadSnapshotsCollected() : 0;
                    progress.setWorkloadSnapshotsCollected(current + 1);
                }
                case "characterization" -> {
                    progress.setWorkloadTypeIdentified(true);
                    progress.setFactorAnalysisCompleted(true);
                }
                case "knobs" -> {
                    progress.setKnobRankingCompleted(true);
                    progress.setTopKnobsIdentified(5);
                }
                case "statistics" -> {
                    long statsCount = cardinalityEstimationService.getStatistics(connectionId).size();
                    progress.setColumnStatsCollected((int) statsCount);
                }
            }

            progress.setUpdatedAt(LocalDateTime.now());
            progress.calculateReadiness();

            learningProgressRepository.save(progress);

        } catch (Exception e) {
            log.debug("Could not update learning progress: {}", e.getMessage());
        }
    }
}
