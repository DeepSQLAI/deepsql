package com.dbaagent.repository;

import com.dbaagent.model.IngestionJobEntity;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for the v1.5.1 bug report: {@code ingestion-job-cleanup}
 * failed every run because {@link IngestionJobRepository#deleteOldCompletedJobs}
 * is a DELETE {@code @Query} that was missing {@code @Modifying}. On Spring 7 /
 * Jakarta Persistence 3.2 that routes the query through {@code getResultList()},
 * which now throws {@code InvalidDataAccessApiUsageException} ("must be a select
 * query"). With {@code @Modifying} it runs via {@code executeUpdate()}.
 *
 * <p>This is the guard against that whole class of bug (6 sibling repositories
 * had the same defect).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class IngestionJobRepositoryDeleteTest {

    @Autowired
    private IngestionJobRepository repository;

    @Autowired
    private EntityManager em;

    private IngestionJobEntity job(IngestionJobEntity.Status status, Instant completedAt) {
        return IngestionJobEntity.builder()
            .jobId(UUID.randomUUID().toString())
            .connectionId("conn-test")
            .status(status)
            .createdAt(Instant.now())
            .completedAt(completedAt)
            .build();
    }

    @Test
    void deleteOldCompletedJobsRunsAndRemovesOnlyOldTerminalJobs() {
        Instant old = Instant.now().minus(10, ChronoUnit.DAYS);
        Instant recent = Instant.now().minus(1, ChronoUnit.HOURS);

        IngestionJobEntity oldCompleted = job(IngestionJobEntity.Status.COMPLETED, old);
        IngestionJobEntity oldFailed = job(IngestionJobEntity.Status.FAILED, old);
        IngestionJobEntity recentCompleted = job(IngestionJobEntity.Status.COMPLETED, recent);
        IngestionJobEntity running = job(IngestionJobEntity.Status.RUNNING, null);
        repository.saveAll(List.of(oldCompleted, oldFailed, recentCompleted, running));
        repository.flush();

        Instant cutoff = Instant.now().minus(5, ChronoUnit.DAYS);

        // The bug: without @Modifying this throws InvalidDataAccessApiUsageException.
        assertDoesNotThrow(() -> repository.deleteOldCompletedJobs(cutoff));

        // Bulk JPQL delete bypasses the persistence context; clear so reads hit the DB.
        em.clear();

        assertFalse(repository.findById(oldCompleted.getJobId()).isPresent(),
            "old COMPLETED job should be deleted");
        assertFalse(repository.findById(oldFailed.getJobId()).isPresent(),
            "old FAILED job should be deleted");
        assertTrue(repository.findById(recentCompleted.getJobId()).isPresent(),
            "recent COMPLETED job should be retained (after cutoff)");
        assertTrue(repository.findById(running.getJobId()).isPresent(),
            "RUNNING job should be retained (non-terminal)");
    }
}
