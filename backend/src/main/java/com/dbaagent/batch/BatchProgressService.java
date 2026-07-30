package com.dbaagent.batch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service to persist batch job progress in a separate transaction.
 * Using REQUIRES_NEW ensures progress updates are committed immediately
 * and visible to status queries, even while the main step transaction is still open.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BatchProgressService {

    private final JobRepository jobRepository;

    /**
     * Persist execution context in a new transaction.
     * This commits immediately, making progress visible to other threads/connections.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistProgress(JobExecution jobExecution) {
        try {
            jobRepository.updateExecutionContext(jobExecution);
            log.debug("Progress persisted for job {}: {}",
                jobExecution.getId(),
                jobExecution.getExecutionContext().getString("currentStep", ""));
        } catch (Exception e) {
            log.warn("Failed to persist progress for job {}: {}",
                jobExecution.getId(), e.getMessage());
        }
    }
}
