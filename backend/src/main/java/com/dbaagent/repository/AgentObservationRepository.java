package com.dbaagent.repository;

import com.dbaagent.model.AgentObservationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentObservationRepository extends JpaRepository<AgentObservationEntity, String> {
    List<AgentObservationEntity> findByRunIdOrderByCreatedAtAsc(String runId);
}
