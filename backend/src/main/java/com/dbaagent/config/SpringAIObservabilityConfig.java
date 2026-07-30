package com.dbaagent.config;

import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for Spring AI Observability with OpenTelemetry integration.
 *
 * Spring AI provides built-in observability support that integrates with:
 * - OpenTelemetry for distributed tracing
 * - Micrometer for metrics
 * - SLF4J for logging
 *
 * Observations are automatically created for:
 * - ChatClient operations (prompts, completions)
 * - VectorStore operations (similarity search, document storage)
 *
 * Key metrics exposed:
 * - spring.ai.chat.client: Chat client operation timings and counts
 * - spring.ai.vector.store: Vector store operation timings
 *
 * There is no spring.ai.embedding timing any more. Embeddings do not go through a
 * Spring AI auto-configured EmbeddingModel: OpenAiEmbeddingAutoConfiguration is excluded
 * in DbaAgentApplication and ProviderBackedEmbeddingModel calls the provider directly,
 * so nothing instruments that path. Advertising a metric that is never emitted sends
 * whoever is debugging embedding latency looking for a gauge that does not exist.
 *
 * Traces include:
 * - gen_ai.request.model: The AI model being used
 * - gen_ai.request.top_k, temperature, etc.: Model parameters
 * - gen_ai.response.finish_reason: How the generation completed
 * - gen_ai.usage.prompt_tokens, completion_tokens: Token usage
 *
 * Environment variables for production:
 * - SPRING_AI_LOG_PROMPTS=false (disable prompt logging for privacy)
 * - SPRING_AI_LOG_COMPLETIONS=false (disable completion logging for privacy)
 */
@Configuration
@Slf4j
@ConditionalOnClass(ObservationRegistry.class)
public class SpringAIObservabilityConfig {

    /**
     * Log Spring AI observability configuration at startup
     */
    @Bean
    public String springAIObservabilityMarker(ObservationRegistry observationRegistry) {
        if (observationRegistry != null) {
            log.info("Spring AI Observability enabled with ObservationRegistry: {}",
                    observationRegistry.getClass().getSimpleName());
            log.info("AI operations (chat, vector store) will be traced via OpenTelemetry");
        }
        return "springAIObservability";
    }
}
