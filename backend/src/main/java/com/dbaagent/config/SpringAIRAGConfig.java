package com.dbaagent.config;

import com.dbaagent.service.telemetry.BrainRetrievalCountingVectorStore;
import com.dbaagent.service.telemetry.TelemetryCounters;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Configuration for Spring AI RAG (Retrieval-Augmented Generation).
 *
 * Uses Spring AI's VectorStore abstraction for:
 * - Document embedding and storage
 * - Similarity search for context retrieval
 * - QuestionAnswerAdvisor for automatic RAG in chat
 *
 * The Azure AI Search VectorStore is auto-configured by spring-ai-starter-vector-store-azure
 * when the required properties are set in application.properties.
 */
@Configuration
@Slf4j
public class SpringAIRAGConfig {

    @Value("${spring.ai.rag.top-k:10}")
    private int topK;

    @Value("${spring.ai.rag.similarity-threshold:0.7}")
    private double similarityThreshold;

    /**
     * Creates a QuestionAnswerAdvisor that automatically retrieves relevant documents
     * from the VectorStore and adds them to the chat context.
     *
     * This advisor:
     * 1. Takes the user's question
     * 2. Performs similarity search in the VectorStore
     * 3. Adds relevant documents as context to the LLM prompt
     * 4. The LLM uses this context to generate informed responses
     *
     * @param vectorStore the configured VectorStore (Azure AI Search)
     * @return configured QuestionAnswerAdvisor
     */
    /**
     * Wraps the auto-configured Spring AI VectorStore with a counter for
     * Phase-2.5 brain-retrieval telemetry. Marked @Primary so all autowired
     * VectorStore consumers (QuestionAnswerAdvisor included) go through this.
     */
    @Bean
    @Primary
    @ConditionalOnBean(VectorStore.class)
    public VectorStore countingVectorStore(
            @Qualifier("vectorStore") VectorStore delegate,
            TelemetryCounters counters) {
        return new BrainRetrievalCountingVectorStore(delegate, counters);
    }

    @Bean
    @ConditionalOnBean(VectorStore.class)
    public QuestionAnswerAdvisor questionAnswerAdvisor(VectorStore vectorStore) {
        log.info("Configuring Spring AI QuestionAnswerAdvisor with topK={}, similarityThreshold={}",
                topK, similarityThreshold);

        SearchRequest searchRequest = SearchRequest.builder()
                .topK(topK)
                .similarityThreshold(similarityThreshold)
                .build();

        return QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(searchRequest)
                .build();
    }
}
