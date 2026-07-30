package com.dbaagent.config;

import com.dbaagent.llm.LlmConfigResolver;
import com.dbaagent.llm.LlmProviderRegistry;
import com.dbaagent.llm.spring.ProviderBackedEmbeddingModel;
import com.dbaagent.service.EmbeddingService;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Wires the provider-agnostic LLM beans. They resolve credentials per call, so the
 * onboarding wizard can change provider or rotate keys with no restart.
 *
 * <p>Replaces {@code ResponsesApiConfig}, which bound Azure OpenAI settings into the bean
 * at construction time. The bean contract is unchanged for consumers: a single
 * {@link Primary} {@link ChatModel}, injected by type — and, since Task 8, a single
 * {@link Primary} {@link EmbeddingModel} alongside it.
 */
@Configuration
public class LlmConfig {

    @Bean
    @Primary
    public ChatModel chatModel(LlmConfigResolver resolver, LlmProviderRegistry registry) {
        return new RefreshableChatModel(resolver, registry);
    }

    /**
     * The single embedding surface. {@code VectorStore} and {@code QuestionAnswerAdvisor}
     * bind to this, so the RAG read and write paths use the same provider as
     * {@link EmbeddingService} — the one thing that cannot be allowed to diverge, because
     * a store written by one embedding model and read through another raises no error and
     * silently degrades retrieval.
     *
     * <p>Marked {@link Primary} so it wins wherever an {@code EmbeddingModel} is injected
     * by type. That alone is not enough: Spring AI's {@code OpenAiEmbeddingAutoConfiguration}
     * builds its bean eagerly and asserts {@code spring.ai.openai.api-key} is non-empty, so
     * once that property is gone it would fail the context rather than defer. It is
     * excluded outright in {@code DbaAgentApplication}, alongside the chat, image, audio
     * and moderation auto-configurations that were already excluded there.
     */
    @Bean
    @Primary
    public EmbeddingModel embeddingModel(EmbeddingService embeddingService) {
        return new ProviderBackedEmbeddingModel(embeddingService);
    }
}
