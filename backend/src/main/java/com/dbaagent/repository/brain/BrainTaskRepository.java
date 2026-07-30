package com.dbaagent.repository.brain;

import com.dbaagent.model.brain.BrainTask;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BrainTaskRepository extends JpaRepository<BrainTask, String> {
    List<BrainTask> findByConnectionIdOrderByCreatedAtDesc(String connectionId);
    List<BrainTask> findByConnectionIdAndStatusOrderByCreatedAtDesc(String connectionId, BrainTask.Status status);
    Optional<BrainTask> findTopByConnectionIdAndTaskTypeAndStatusAndTableName(
        String connectionId,
        BrainTask.TaskType taskType,
        BrainTask.Status status,
        String tableName
    );
}
