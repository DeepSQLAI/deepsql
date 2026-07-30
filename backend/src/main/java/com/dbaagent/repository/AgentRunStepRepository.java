package com.dbaagent.repository;

import com.dbaagent.model.AgentRunStep;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentRunStepRepository extends JpaRepository<AgentRunStep, String> {
    List<AgentRunStep> findByRunIdOrderByStepIndexAsc(String runId);
}
