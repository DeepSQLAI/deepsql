package com.dbaagent.repository;

import com.dbaagent.model.ConnectionInitStatus;
import com.dbaagent.model.InitStage;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

@org.springframework.stereotype.Repository
public interface ConnectionInitStatusRepository extends JpaRepository<ConnectionInitStatus, String> {
    List<ConnectionInitStatus> findByCurrentStageNotIn(List<InitStage> stages);
}
