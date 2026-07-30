package com.dbaagent.repository;

import com.dbaagent.model.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, String> {
    List<ChatMessage> findByChatIdOrderByCreatedAtAsc(String chatId);
    long countByChatId(String chatId);
    long countByChatIdAndRole(String chatId, ChatMessage.MessageRole role);
    void deleteByChatId(String chatId);
}
