package com.dbaagent.support;

import com.dbaagent.llm.LlmConfigResolver;
import com.dbaagent.llm.api.LlmCredentials;
import org.opentest4j.TestAbortedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Test support utility for checking LLM availability within Spring integration tests.
 *
 * <p>This component uses the actual {@link LlmConfigResolver} bean to check LLM
 * configuration, which includes both environment variables and database-stored
 * configuration. This provides more accurate checks than the standalone
 * {@link LlmAvailabilityCondition} which only checks environment variables.
 *
 * <p>Usage in integration tests:
 * <pre>{@code
 * @SpringBootTest
 * class MyIntegrationTest {
 *
 *     @Autowired
 *     private LlmTestSupport llmTestSupport;
 *
 *     @Test
 *     void testRequiringChat() {
 *         llmTestSupport.requireChat("my test");
 *         // test code that needs chat LLM
 *     }
 *
 *     @Test
 *     void testOptionallyUsingLlm() {
 *         if (llmTestSupport.isChatAvailable()) {
 *             // test with LLM
 *         } else {
 *             // fallback test path
 *         }
 *     }
 * }
 * }</pre>
 */
@Component
public class LlmTestSupport {

    private final LlmConfigResolver resolver;

    @Autowired
    public LlmTestSupport(LlmConfigResolver resolver) {
        this.resolver = resolver;
    }

    /**
     * Checks if chat LLM is configured and available.
     *
     * @return true if chat LLM credentials are configured
     */
    public boolean isChatAvailable() {
        return resolver.resolveChat() != null;
    }

    /**
     * Checks if embedding LLM is configured and available.
     *
     * @return true if embedding LLM credentials are configured
     */
    public boolean isEmbeddingAvailable() {
        return resolver.resolveEmbedding() != null;
    }

    /**
     * Aborts the test if chat LLM is not configured.
     *
     * @param purpose description of what the test needs chat LLM for
     * @throws TestAbortedException if chat LLM is not configured
     */
    public void requireChat(String purpose) {
        if (!isChatAvailable()) {
            throw new TestAbortedException(
                    "Chat LLM not configured, skipping test for " + purpose + ". "
                    + "Set DEEPSQL_CHAT_PROVIDER and DEEPSQL_CHAT_API_KEY environment variables, "
                    + "or configure via the setup wizard at /onboarding.");
        }
    }

    /**
     * Aborts the test if embedding LLM is not configured.
     *
     * @param purpose description of what the test needs embedding LLM for
     * @throws TestAbortedException if embedding LLM is not configured
     */
    public void requireEmbedding(String purpose) {
        if (!isEmbeddingAvailable()) {
            throw new TestAbortedException(
                    "Embedding LLM not configured, skipping test for " + purpose + ". "
                    + "Set DEEPSQL_EMBEDDING_PROVIDER and DEEPSQL_EMBEDDING_API_KEY environment variables, "
                    + "or configure via the setup wizard at /onboarding.");
        }
    }

    /**
     * Aborts the test if either chat or embedding LLM is not configured.
     *
     * @param purpose description of what the test needs full LLM for
     * @throws TestAbortedException if any LLM component is not configured
     */
    public void requireBoth(String purpose) {
        requireChat(purpose);
        requireEmbedding(purpose);
    }

    /**
     * Returns the resolved chat credentials, or null if not configured.
     * Useful for tests that need to inspect the configuration.
     */
    public LlmCredentials getChatCredentials() {
        return resolver.resolveChat();
    }

    /**
     * Returns the resolved embedding credentials, or null if not configured.
     * Useful for tests that need to inspect the configuration.
     */
    public LlmCredentials getEmbeddingCredentials() {
        return resolver.resolveEmbedding();
    }

    /**
     * Returns a human-readable description of the current LLM configuration status.
     * Useful for diagnostic output in tests.
     */
    public String describeConfiguration() {
        LlmCredentials chat = resolver.resolveChat();
        LlmCredentials embedding = resolver.resolveEmbedding();

        StringBuilder sb = new StringBuilder();
        sb.append("LLM Configuration Status (via LlmConfigResolver):\n");

        sb.append("  Chat: ");
        if (chat != null) {
            sb.append("CONFIGURED (provider=").append(chat.providerId());
            String model = chat.get("model");
            if (model != null) {
                sb.append(", model=").append(model);
            }
            sb.append(")");
        } else {
            sb.append("NOT CONFIGURED");
        }
        sb.append("\n");

        sb.append("  Embedding: ");
        if (embedding != null) {
            sb.append("CONFIGURED (provider=").append(embedding.providerId());
            String model = embedding.get("model");
            if (model != null) {
                sb.append(", model=").append(model);
            }
            sb.append(")");
        } else {
            sb.append("NOT CONFIGURED");
        }

        return sb.toString();
    }
}
