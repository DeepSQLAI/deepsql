package com.dbaagent.llm.api;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

public interface LlmEmbeddingProvider {

    LlmProviderDescriptor descriptor();

    /** Embed one text. Returns an empty list only when the provider returned no data. */
    List<Double> embed(String text, LlmCredentials credentials);

    /** Embed a batch. Result order matches input order. */
    List<List<Double>> embedBatch(List<String> texts, LlmCredentials credentials);

    /**
     * Vector width for the model named in {@code credentials}. The embedding lock
     * (Phase 2, spec §8) compares against this.
     */
    int dimensions(LlmCredentials credentials);

    LlmErrorCategory classify(Throwable throwable);

    /**
     * How long the provider asked us to wait before retrying, when it said so.
     *
     * <p>{@link #classify} answers <em>whether</em> a retry is worth attempting;
     * this answers <em>when</em>. They are separate questions and only the provider
     * can answer the second: the delay arrives in a transport header or an error
     * string whose shape is vendor-specific.
     *
     * <p>Without it, a rate-limited caller backs off on a schedule it invented.
     * Observed against Azure OpenAI on an S0 tier: the service replied 429
     * "retry after 4 seconds" while the local policy slept 750ms then 1500ms, so
     * every attempt landed inside the cooldown and the work was dropped as
     * "transient". Retrying sooner than asked does not just waste the attempt —
     * it is also what the rate limiter is counting.
     *
     * <p>Empty means no opinion; the caller keeps its own schedule. Callers must
     * treat a returned value as untrusted input and cap it: it is a number from a
     * remote server, and honouring an arbitrarily large one would hang the thread.
     */
    default Optional<Duration> retryAfter(Throwable throwable) {
        return Optional.empty();
    }
}
