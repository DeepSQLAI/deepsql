package com.dbaagent.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Configuration for Spring AI Chat Memory with JDBC/PostgreSQL persistence.
 *
 * Enables conversation history to persist across:
 * - Server restarts
 * - User logout/login
 * - Browser tab closes
 *
 * The JDBC-backed ChatMemoryRepository is auto-configured by
 * spring-ai-starter-model-chat-memory-repository-jdbc.
 *
 * Note: This uses the same PostgreSQL database as our vault DB,
 * storing messages in Spring AI's chat_memory schema.
 */
@Configuration
@Slf4j
public class SpringAIChatMemoryConfig {

    /**
     * Configured window size for conversation history.
     * Keeps only the last N messages per chat to bound prompt context.
     */
    @Value("${app.chat.memory.max-messages:50}")
    private int maxMessages;

    /**
     * Creates a ChatMemory instance backed by JDBC/PostgreSQL.
     * This is used to store and retrieve conversation history.
     *
     * @param chatMemoryRepository the JDBC-backed repository (auto-configured)
     * @return configured ChatMemory instance
     */
    @Bean
    @Primary
    public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository) {
        log.info("Configuring Spring AI ChatMemory with repository={}, maxMessages={}",
            chatMemoryRepository.getClass().getSimpleName(), maxMessages);

        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(maxMessages)
                .build();
    }
}
