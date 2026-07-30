package com.dbaagent.repository;

import com.dbaagent.model.TrainingJobHistory;
import com.dbaagent.model.TrainingJobStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface TrainingJobHistoryRepository extends JpaRepository<TrainingJobHistory, String> {
    List<TrainingJobHistory> findByConnectionIdOrderByCreatedAtDesc(String connectionId, Pageable pageable);

    Optional<TrainingJobHistory> findTopByConnectionIdOrderByCreatedAtDesc(String connectionId);

    Optional<TrainingJobHistory> findTopByConnectionIdAndStatusOrderByCompletedAtDesc(
        String connectionId,
        TrainingJobStatus.Status status
    );

    @Query("select distinct h.connectionId from TrainingJobHistory h")
    List<String> findDistinctConnectionIds();

    @Modifying
    @Query("delete from TrainingJobHistory h where h.startedAt < :cutoff")
    int deleteByStartedAtBefore(@Param("cutoff") LocalDateTime cutoff);

    @Modifying
    @Query(value = """
        with ranked as (
            select job_id,
                   row_number() over (
                       partition by connection_id order by started_at desc
                   ) as rn
            from training_job_history
            where connection_id = :connectionId
        )
        delete from training_job_history
        where job_id in (
            select job_id from ranked where rn > :maxPerConnection
        )
        """, nativeQuery = true)
    int deleteExcessForConnection(
        @Param("connectionId") String connectionId,
        @Param("maxPerConnection") int maxPerConnection
    );
}
