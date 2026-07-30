package com.dbaagent.llm.api;

/**
 * Provider-agnostic error taxonomy. Replaces message-substring matching such as
 * {@code message.contains("Azure OpenAI error (401)")}, which does not survive more
 * than one provider.
 */
public enum LlmErrorCategory {
    /** Credentials rejected. Never retry. */
    AUTH,
    /** Unknown deployment or model id. Never retry. */
    MODEL_NOT_FOUND,
    /** Throttled. Retry with backoff. */
    RATE_LIMIT,
    /** 5xx, connect or DNS failure. Bounded retry. */
    TRANSIENT,
    /** Prompt exceeds the model window. Never retry; the caller may trim. */
    CONTEXT_LENGTH,
    /** Unclassified. Never retry; log in full. */
    UNKNOWN;

    public boolean isRetryable() {
        return this == RATE_LIMIT || this == TRANSIENT;
    }

    /**
     * Categories that justify falling back to the environment credential bundle.
     *
     * <p>{@code TRANSIENT} is deliberately absent. A transient failure entitles you to a
     * retry, never to a configuration change: invalidation is sticky, so one 503 mid-stream
     * would permanently switch an install off the provider its operator chose, and nothing
     * would switch it back. Only evidence about the <em>configuration itself</em> — a
     * rejected credential or an unknown model — says anything about which bundle to use.
     */
    public boolean justifiesEnvFallback() {
        return this == AUTH || this == MODEL_NOT_FOUND;
    }
}
