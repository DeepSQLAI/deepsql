package com.dbaagent.repository;

import com.dbaagent.model.ChatTurnContext;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChatTurnContextRepository extends JpaRepository<ChatTurnContext, String> {
    List<ChatTurnContext> findByChatIdOrderByCreatedAtDesc(String chatId);
    Optional<ChatTurnContext> findTopByChatIdOrderByCreatedAtDesc(String chatId);
    Optional<ChatTurnContext> findByAssistantMessageId(String assistantMessageId);
    void deleteByChatId(String chatId);
}
