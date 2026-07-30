/*
 * Copyright (c) 2026 DeepSQL. All rights reserved.
 */

package com.dbaagent;

import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Every OpenAI model auto-configuration is excluded. {@code ChatModel} and
 * {@code EmbeddingModel} come from {@code LlmConfig}, which resolves provider and
 * credentials per call instead of binding {@code spring.ai.openai.*} at startup.
 *
 * <p>{@code OpenAiEmbeddingAutoConfiguration} has to be excluded, not merely out-voted by
 * {@code @Primary}: its {@code openAiEmbeddingModel} bean is an eager singleton that
 * asserts {@code spring.ai.openai.api-key} is non-empty, so with that property removed it
 * fails context refresh before precedence is ever consulted.
 */
@SpringBootApplication(exclude = {
        OpenAiChatAutoConfiguration.class,
        OpenAiEmbeddingAutoConfiguration.class,
        OpenAiAudioSpeechAutoConfiguration.class,
        OpenAiAudioTranscriptionAutoConfiguration.class,
        OpenAiImageAutoConfiguration.class,
        OpenAiModerationAutoConfiguration.class
})
@EnableJpaRepositories(basePackages = "com.dbaagent")
@EnableAsync
public class DbaAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(DbaAgentApplication.class, args);
    }
}
