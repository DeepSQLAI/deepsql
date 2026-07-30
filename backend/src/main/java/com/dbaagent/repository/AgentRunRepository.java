package com.dbaagent.repository;

import com.dbaagent.model.AgentRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentRunRepository extends JpaRepository<AgentRun, String> {
    List<AgentRun> findByChatIdOrderByCreatedAtAsc(String chatId);
    Optional<AgentRun> findTopByChatIdOrderByCreatedAtDesc(String chatId);
}
