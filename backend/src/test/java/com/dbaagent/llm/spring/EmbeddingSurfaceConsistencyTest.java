package com.dbaagent.llm.spring;

import com.dbaagent.service.EmbeddingService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the failure mode no other test can see: if the RAG store is written by one
 * embedding model and read through another, nothing throws — pgvector accepts the
 * vectors, similarity returns a number, and retrieval quality silently collapses.
 */
@SpringBootTest
@ActiveProfiles("test")
class EmbeddingSurfaceConsistencyTest {

    @Autowired private EmbeddingModel springAiEmbeddingModel;
    @Autowired private EmbeddingService embeddingService;
    @Autowired private Environment environment;

    @Test
    void springAiEmbeddingModelIsBackedByTheSameProviderAsEmbeddingService() {
        assertThat(springAiEmbeddingModel)
                .as("VectorStore and QuestionAnswerAdvisor must embed through the same "
                    + "provider as EmbeddingService, or reads and writes disagree")
                .isInstanceOf(ProviderBackedEmbeddingModel.class);
        assertThat(embeddingService).isNotNull();
    }

    @Test
    void noBeanStillDependsOnTheDeletedAzureRootProperties() {
        // The context started, which already proves no bean required them. Assert the
        // properties are genuinely gone so nothing silently reintroduces the coupling.
        assertThat(environment.getProperty("spring.ai.openai.api-key")).isNull();
        assertThat(environment.getProperty("spring.ai.azure.openai.api-key")).isNull();
    }
}
