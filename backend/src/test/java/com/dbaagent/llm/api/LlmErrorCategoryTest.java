package com.dbaagent.llm.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the retry/fallback taxonomy so a future added enum constant is forced to declare
 * an explicit answer for both predicates rather than silently inheriting a default.
 */
class LlmErrorCategoryTest {

    @Test
    void rateLimitAndTransientAreRetryable() {
        assertTrue(LlmErrorCategory.RATE_LIMIT.isRetryable());
        assertTrue(LlmErrorCategory.TRANSIENT.isRetryable());
    }

    @Test
    void authModelNotFoundContextLengthAndUnknownAreNotRetryable() {
        assertFalse(LlmErrorCategory.AUTH.isRetryable());
        assertFalse(LlmErrorCategory.MODEL_NOT_FOUND.isRetryable());
        assertFalse(LlmErrorCategory.CONTEXT_LENGTH.isRetryable());
        assertFalse(LlmErrorCategory.UNKNOWN.isRetryable());
    }

    @Test
    void onlyAuthAndModelNotFoundJustifyEnvFallback() {
        assertTrue(LlmErrorCategory.AUTH.justifiesEnvFallback());
        assertTrue(LlmErrorCategory.MODEL_NOT_FOUND.justifiesEnvFallback());
    }

    /**
     * A transient failure entitles you to a retry, never to a configuration change.
     * Invalidation is sticky, so treating one 503 as evidence about the configuration
     * permanently moved an install off the provider its operator chose.
     */
    @Test
    void transientRateLimitContextLengthAndUnknownDoNotJustifyEnvFallback() {
        assertFalse(LlmErrorCategory.TRANSIENT.justifiesEnvFallback());
        assertFalse(LlmErrorCategory.RATE_LIMIT.justifiesEnvFallback());
        assertFalse(LlmErrorCategory.CONTEXT_LENGTH.justifiesEnvFallback());
        assertFalse(LlmErrorCategory.UNKNOWN.justifiesEnvFallback());
    }
}
