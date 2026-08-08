package com.dbaagent.support;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.commons.support.AnnotationSupport;

import java.util.Optional;

/**
 * JUnit 5 ExecutionCondition that checks whether LLM configuration is available.
 *
 * <p>This condition checks the {@code DEEPSQL_CHAT_*} and {@code DEEPSQL_EMBEDDING_*}
 * environment variables to determine if LLM is configured. It uses the same resolution
 * logic as {@code LlmConfigResolver.fromEnvironment()}.
 *
 * <p>For integration tests running within a Spring context, the actual
 * {@code LlmConfigResolver} bean should be used via {@link LlmTestSupport} for
 * more accurate checks that include database-stored configuration.
 */
public class LlmAvailabilityCondition implements ExecutionCondition {

    private static final String CHAT_PROVIDER_ENV = "DEEPSQL_CHAT_PROVIDER";
    private static final String CHAT_API_KEY_ENV = "DEEPSQL_CHAT_API_KEY";
    private static final String EMBEDDING_PROVIDER_ENV = "DEEPSQL_EMBEDDING_PROVIDER";
    private static final String EMBEDDING_API_KEY_ENV = "DEEPSQL_EMBEDDING_API_KEY";

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        Optional<RequiresLlm> annotation = findAnnotation(context);

        if (annotation.isEmpty()) {
            return ConditionEvaluationResult.enabled("No @RequiresLlm annotation present");
        }

        RequiresLlm requiresLlm = annotation.get();
        boolean requiresChat = requiresLlm.chat();
        boolean requiresEmbedding = requiresLlm.embedding();

        if (requiresChat && !isChatConfigured()) {
            return ConditionEvaluationResult.disabled(
                    "Chat LLM not configured. Set DEEPSQL_CHAT_PROVIDER and DEEPSQL_CHAT_API_KEY "
                    + "environment variables, or configure via the setup wizard.");
        }

        if (requiresEmbedding && !isEmbeddingConfigured()) {
            return ConditionEvaluationResult.disabled(
                    "Embedding LLM not configured. Set DEEPSQL_EMBEDDING_PROVIDER and "
                    + "DEEPSQL_EMBEDDING_API_KEY environment variables, or configure via the setup wizard.");
        }

        return ConditionEvaluationResult.enabled("LLM configuration available");
    }

    private Optional<RequiresLlm> findAnnotation(ExtensionContext context) {
        Optional<RequiresLlm> methodAnnotation = context.getElement()
                .flatMap(element -> AnnotationSupport.findAnnotation(element, RequiresLlm.class));

        if (methodAnnotation.isPresent()) {
            return methodAnnotation;
        }

        return context.getTestClass()
                .flatMap(clazz -> AnnotationSupport.findAnnotation(clazz, RequiresLlm.class));
    }

    /**
     * Checks if chat LLM is configured via environment variables.
     *
     * <p>This mirrors the logic in {@code LlmConfigResolver.fromEnvironment("CHAT")}.
     */
    public static boolean isChatConfigured() {
        String provider = System.getenv(CHAT_PROVIDER_ENV);
        String apiKey = System.getenv(CHAT_API_KEY_ENV);
        return isConfigured(provider, apiKey);
    }

    /**
     * Checks if embedding LLM is configured via environment variables.
     *
     * <p>This mirrors the logic in {@code LlmConfigResolver.fromEnvironment("EMBEDDING")}.
     */
    public static boolean isEmbeddingConfigured() {
        String provider = System.getenv(EMBEDDING_PROVIDER_ENV);
        String apiKey = System.getenv(EMBEDDING_API_KEY_ENV);
        return isConfigured(provider, apiKey);
    }

    private static boolean isConfigured(String provider, String apiKey) {
        return provider != null && !provider.isBlank()
                && apiKey != null && !apiKey.isBlank();
    }

    /**
     * Returns a human-readable description of the current LLM configuration status.
     * Useful for test diagnostic output.
     */
    public static String describeConfiguration() {
        StringBuilder sb = new StringBuilder();
        sb.append("LLM Configuration Status:\n");
        sb.append("  Chat: ").append(isChatConfigured() ? "CONFIGURED" : "NOT CONFIGURED");
        if (!isChatConfigured()) {
            sb.append(" (set DEEPSQL_CHAT_PROVIDER and DEEPSQL_CHAT_API_KEY)");
        }
        sb.append("\n");
        sb.append("  Embedding: ").append(isEmbeddingConfigured() ? "CONFIGURED" : "NOT CONFIGURED");
        if (!isEmbeddingConfigured()) {
            sb.append(" (set DEEPSQL_EMBEDDING_PROVIDER and DEEPSQL_EMBEDDING_API_KEY)");
        }
        return sb.toString();
    }
}
