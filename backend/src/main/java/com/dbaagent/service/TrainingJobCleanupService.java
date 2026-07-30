package com.dbaagent.service;

import com.dbaagent.repository.TrainingJobHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class TrainingJobCleanupService {

    private final TrainingJobHistoryRepository trainingJobHistoryRepository;

    @Value("${training.history.retention.max-per-connection:50}")
    private int maxPerConnection;

    @Value("${training.history.retention.days:0}")
    private int retentionDays;

    @Transactional
    public void cleanupTrainingHistory() {
        if (maxPerConnection <= 0 && retentionDays <= 0) {
            return;
        }

        log.info("Starting training history cleanup (maxPerConnection={}, retentionDays={})",
            maxPerConnection, retentionDays);

        int deletedByAge = 0;
        if (retentionDays > 0) {
            LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
            deletedByAge = trainingJobHistoryRepository.deleteByStartedAtBefore(cutoff);
            log.info("Deleted {} training jobs older than {}", deletedByAge, cutoff);
        }

        int deletedByCount = 0;
        if (maxPerConnection > 0) {
            List<String> connectionIds = trainingJobHistoryRepository.findDistinctConnectionIds();
            for (String connectionId : connectionIds) {
                deletedByCount += trainingJobHistoryRepository.deleteExcessForConnection(
                    connectionId,
                    maxPerConnection
                );
            }
            log.info("Deleted {} training jobs beyond per-connection limit", deletedByCount);
        }

        log.info("Training history cleanup complete (deletedByAge={}, deletedByCount={})",
            deletedByAge, deletedByCount);
    }
}
