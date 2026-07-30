package com.dbaagent.repository;

import com.dbaagent.model.AgentConversation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentConversationRepository extends JpaRepository<AgentConversation, String> {

    /** A user's non-archived conversations for a connection, newest activity first. */
    List<AgentConversation> findByUserIdAndConnectionIdAndArchivedFalseOrderByLastMessageAtDesc(
        Long userId, String connectionId);

    /** A user's non-archived conversations with no connection, newest activity first. */
    List<AgentConversation> findByUserIdAndConnectionIdIsNullAndArchivedFalseOrderByLastMessageAtDesc(
        Long userId);

    /** Ownership-checked lookup so one user can never read/write another's conversation. */
    Optional<AgentConversation> findByIdAndUserId(String id, Long userId);
}
