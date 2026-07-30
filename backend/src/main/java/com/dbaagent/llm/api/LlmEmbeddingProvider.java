package com.dbaagent.llm.api;

import java.util.List;

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
}
