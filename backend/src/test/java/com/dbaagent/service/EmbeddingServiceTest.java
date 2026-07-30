package com.dbaagent.service;

import com.dbaagent.llm.LlmConfigResolver;
import com.dbaagent.llm.LlmProviderRegistry;
import com.dbaagent.llm.api.LlmCredentials;
import com.dbaagent.llm.api.LlmEmbeddingProvider;
import com.dbaagent.llm.api.LlmErrorCategory;
import com.dbaagent.llm.api.LlmNotConfiguredException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmbeddingServiceTest {

    private static LlmCredentials creds() {
        return new LlmCredentials("openai",
                Map.of("api-key", "k", "endpoint", "https://x.invalid/",
                       "model", "text-embedding-3-large"));
    }

    @Test
    void delegatesToTheResolvedEmbeddingProvider() {
        var resolver = mock(LlmConfigResolver.class);
        var registry = mock(LlmProviderRegistry.class);
        var provider = mock(LlmEmbeddingProvider.class);

        when(resolver.resolveEmbedding()).thenReturn(creds());
        when(registry.embeddingProvider("openai")).thenReturn(provider);
        when(provider.embed(anyString(), any())).thenReturn(List.of(0.1, 0.2, 0.3));

        var service = new EmbeddingService(resolver, registry, 30_000, false);

        assertThat(service.createEmbedding("hello")).containsExactly(0.1, 0.2, 0.3);
    }

    @Test
    void truncatesOverlongInputBeforeEmbedding() {
        var resolver = mock(LlmConfigResolver.class);
        var registry = mock(LlmProviderRegistry.class);
        var provider = mock(LlmEmbeddingProvider.class);

        when(resolver.resolveEmbedding()).thenReturn(creds());
        when(registry.embeddingProvider("openai")).thenReturn(provider);
        when(provider.embed(anyString(), any())).thenReturn(List.of(0.1));

        new EmbeddingService(resolver, registry, 30_000, false)
                .createEmbedding("x".repeat(50_000));

        var captor = ArgumentCaptor.forClass(String.class);
        verify(provider).embed(captor.capture(), any());
        assertThat(captor.getValue()).hasSize(30_000);
    }

    @Test
    void failOpenReturnsEmptyInsteadOfPropagating() {
        var resolver = mock(LlmConfigResolver.class);
        var registry = mock(LlmProviderRegistry.class);
        var provider = mock(LlmEmbeddingProvider.class);

        when(resolver.resolveEmbedding()).thenReturn(creds());
        when(registry.embeddingProvider("openai")).thenReturn(provider);
        when(provider.embed(anyString(), any())).thenThrow(new RuntimeException("boom"));
        when(provider.classify(any())).thenReturn(LlmErrorCategory.UNKNOWN);

        var service = new EmbeddingService(resolver, registry, 30_000, true);

        assertThat(service.createEmbedding("hello")).isEmpty();
    }

    @Test
    void failClosedPropagatesTheError() {
        var resolver = mock(LlmConfigResolver.class);
        var registry = mock(LlmProviderRegistry.class);
        var provider = mock(LlmEmbeddingProvider.class);

        when(resolver.resolveEmbedding()).thenReturn(creds());
        when(registry.embeddingProvider("openai")).thenReturn(provider);
        when(provider.embed(anyString(), any())).thenThrow(new RuntimeException("boom"));
        when(provider.classify(any())).thenReturn(LlmErrorCategory.UNKNOWN);

        var service = new EmbeddingService(resolver, registry, 30_000, false);

        assertThatThrownBy(() -> service.createEmbedding("hello"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void unconfiguredEmbeddingProviderRaisesTheOnboardingError() {
        var resolver = mock(LlmConfigResolver.class);
        var registry = mock(LlmProviderRegistry.class);
        when(resolver.resolveEmbedding()).thenReturn(null);

        var service = new EmbeddingService(resolver, registry, 30_000, false);

        assertThatThrownBy(() -> service.createEmbedding("hello"))
                .isInstanceOf(LlmNotConfiguredException.class);
    }

    @Test
    void batchDelegatesTruncatedTextsAndPreservesOrder() {
        var resolver = mock(LlmConfigResolver.class);
        var registry = mock(LlmProviderRegistry.class);
        var provider = mock(LlmEmbeddingProvider.class);

        when(resolver.resolveEmbedding()).thenReturn(creds());
        when(registry.embeddingProvider("openai")).thenReturn(provider);
        when(provider.embedBatch(anyList(), any()))
                .thenReturn(List.of(List.of(1.0), List.of(2.0)));

        var service = new EmbeddingService(resolver, registry, 10, false);

        assertThat(service.createEmbeddings(List.of("a".repeat(50), "b")))
                .containsExactly(List.of(1.0), List.of(2.0));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(provider).embedBatch(captor.capture(), any());
        assertThat(captor.getValue()).containsExactly("a".repeat(10), "b");
    }

    @Test
    void batchFailOpenReturnsOneEmptyVectorPerInput() {
        var resolver = mock(LlmConfigResolver.class);
        var registry = mock(LlmProviderRegistry.class);
        var provider = mock(LlmEmbeddingProvider.class);

        when(resolver.resolveEmbedding()).thenReturn(creds());
        when(registry.embeddingProvider("openai")).thenReturn(provider);
        when(provider.embedBatch(anyList(), any())).thenThrow(new RuntimeException("boom"));
        when(provider.classify(any())).thenReturn(LlmErrorCategory.UNKNOWN);

        var service = new EmbeddingService(resolver, registry, 30_000, true);

        assertThat(service.createEmbeddings(List.of("a", "b")))
                .containsExactly(List.of(), List.of());
    }

    @Test
    void batchWithNoConfiguredProviderRaisesTheOnboardingError() {
        var resolver = mock(LlmConfigResolver.class);
        var registry = mock(LlmProviderRegistry.class);
        when(resolver.resolveEmbedding()).thenReturn(null);

        var service = new EmbeddingService(resolver, registry, 30_000, true);

        assertThatThrownBy(() -> service.createEmbeddings(List.of("a")))
                .isInstanceOf(LlmNotConfiguredException.class);
    }

    // ------------------------------------------------------------------
    // Bounded retry, keyed on LlmErrorCategory.isRetryable().
    // ------------------------------------------------------------------

    /** Backoff of 0ms keeps the retry tests fast without weakening what they assert. */
    private static EmbeddingService serviceWithNoBackoff(
            LlmConfigResolver resolver, LlmProviderRegistry registry, boolean failOpen) {
        return new EmbeddingService(resolver, registry, 30_000, failOpen, 0L);
    }

    /**
     * A single socket timeout on the RAG retrieval path used to survive three attempts.
     * With maxRetries(0) on the SDK client, this layer is the only thing standing between
     * a transient blip and a failed user request in prod, where fail-open is off.
     */
    @Test
    void retriesRetryableFailuresUpToTheAttemptLimitThenGivesUp() {
        var resolver = mock(LlmConfigResolver.class);
        var registry = mock(LlmProviderRegistry.class);
        var provider = mock(LlmEmbeddingProvider.class);

        when(resolver.resolveEmbedding()).thenReturn(creds());
        when(registry.embeddingProvider("openai")).thenReturn(provider);
        when(provider.embed(anyString(), any())).thenThrow(new RuntimeException("timed out"));
        when(provider.classify(any())).thenReturn(LlmErrorCategory.TRANSIENT);

        assertThatThrownBy(() ->
                serviceWithNoBackoff(resolver, registry, false).createEmbedding("hello"))
                .isInstanceOf(RuntimeException.class);

        verify(provider, times(3)).embed(anyString(), any());
    }

    @Test
    void retriedCallThatEventuallySucceedsReturnsTheVector() {
        var resolver = mock(LlmConfigResolver.class);
        var registry = mock(LlmProviderRegistry.class);
        var provider = mock(LlmEmbeddingProvider.class);

        when(resolver.resolveEmbedding()).thenReturn(creds());
        when(registry.embeddingProvider("openai")).thenReturn(provider);
        when(provider.embed(anyString(), any()))
                .thenThrow(new RuntimeException("timed out"))
                .thenReturn(List.of(0.7));
        when(provider.classify(any())).thenReturn(LlmErrorCategory.TRANSIENT);

        assertThat(serviceWithNoBackoff(resolver, registry, false).createEmbedding("hello"))
                .containsExactly(0.7);

        verify(provider, times(2)).embed(anyString(), any());
    }

    /** AUTH is never retryable — hammering a rejected key three times helps nobody. */
    @Test
    void doesNotRetryNonRetryableFailures() {
        var resolver = mock(LlmConfigResolver.class);
        var registry = mock(LlmProviderRegistry.class);
        var provider = mock(LlmEmbeddingProvider.class);

        when(resolver.resolveEmbedding()).thenReturn(creds());
        when(registry.embeddingProvider("openai")).thenReturn(provider);
        when(provider.embed(anyString(), any())).thenThrow(new RuntimeException("401"));
        when(provider.classify(any())).thenReturn(LlmErrorCategory.AUTH);

        assertThat(serviceWithNoBackoff(resolver, registry, true).createEmbedding("hello"))
                .isEmpty();

        verify(provider, times(1)).embed(anyString(), any());
    }

    @Test
    void batchRetriesRetryableFailuresToo() {
        var resolver = mock(LlmConfigResolver.class);
        var registry = mock(LlmProviderRegistry.class);
        var provider = mock(LlmEmbeddingProvider.class);

        when(resolver.resolveEmbedding()).thenReturn(creds());
        when(registry.embeddingProvider("openai")).thenReturn(provider);
        when(provider.embedBatch(anyList(), any())).thenThrow(new RuntimeException("timed out"));
        when(provider.classify(any())).thenReturn(LlmErrorCategory.TRANSIENT);

        assertThat(serviceWithNoBackoff(resolver, registry, true)
                .createEmbeddings(List.of("a", "b")))
                .containsExactly(List.of(), List.of());

        verify(provider, times(3)).embedBatch(anyList(), any());
    }

    @Test
    void dimensionsReportsTheProvidersRealWidth() {
        var resolver = mock(LlmConfigResolver.class);
        var registry = mock(LlmProviderRegistry.class);
        var provider = mock(LlmEmbeddingProvider.class);

        when(resolver.resolveEmbedding()).thenReturn(creds());
        when(registry.embeddingProvider("openai")).thenReturn(provider);
        when(provider.dimensions(any())).thenReturn(3072);

        assertThat(new EmbeddingService(resolver, registry, 30_000, true).dimensions())
                .isEqualTo(3072);
    }

    @Test
    void dimensionsIsMemoisedBecauseVectorStoresAskOnEveryOperation() {
        var resolver = mock(LlmConfigResolver.class);
        var registry = mock(LlmProviderRegistry.class);
        var provider = mock(LlmEmbeddingProvider.class);

        when(resolver.resolveEmbedding()).thenReturn(creds());
        when(registry.embeddingProvider("openai")).thenReturn(provider);
        when(provider.dimensions(any())).thenReturn(3072);

        var service = new EmbeddingService(resolver, registry, 30_000, true);
        service.dimensions();
        service.dimensions();
        service.dimensions();

        // A custom deployment name is probed with a live request, so re-asking per
        // vector-store operation would be one network round trip per RAG search.
        verify(provider, times(1)).dimensions(any());
    }

    @Test
    void dimensionsReResolvesWhenTheConfiguredModelChanges() {
        var resolver = mock(LlmConfigResolver.class);
        var registry = mock(LlmProviderRegistry.class);
        var provider = mock(LlmEmbeddingProvider.class);

        var large = creds();
        var small = new LlmCredentials("openai",
                Map.of("api-key", "k", "endpoint", "https://x.invalid/",
                       "model", "text-embedding-3-small"));

        when(resolver.resolveEmbedding()).thenReturn(large, small);
        when(registry.embeddingProvider("openai")).thenReturn(provider);
        when(provider.dimensions(large)).thenReturn(3072);
        when(provider.dimensions(small)).thenReturn(1536);

        var service = new EmbeddingService(resolver, registry, 30_000, true);

        assertThat(service.dimensions()).isEqualTo(3072);
        assertThat(service.dimensions())
                .as("the memo is keyed on the credential signature, which carries the model, "
                    + "so a wizard-driven model switch must not keep reporting the old width")
                .isEqualTo(1536);
    }

    @Test
    void dimensionsRetriesATransientProbeFailure() {
        var resolver = mock(LlmConfigResolver.class);
        var registry = mock(LlmProviderRegistry.class);
        var provider = mock(LlmEmbeddingProvider.class);

        when(resolver.resolveEmbedding()).thenReturn(creds());
        when(registry.embeddingProvider("openai")).thenReturn(provider);
        when(provider.classify(any())).thenReturn(LlmErrorCategory.TRANSIENT);
        // An unrecognised model id is probed with a live request, and a failed probe is not
        // memoised — so without a retry a single socket blip makes the next RAG search throw.
        when(provider.dimensions(any()))
                .thenThrow(new RuntimeException("socket timeout"))
                .thenReturn(3072);

        var service = new EmbeddingService(resolver, registry, 30_000, true, 0L);

        assertThat(service.dimensions()).isEqualTo(3072);
        verify(provider, times(2)).dimensions(any());
    }

    @Test
    void dimensionsStaysFailClosedEvenWhenFailOpenIsOn() {
        var resolver = mock(LlmConfigResolver.class);
        var registry = mock(LlmProviderRegistry.class);
        var provider = mock(LlmEmbeddingProvider.class);

        when(resolver.resolveEmbedding()).thenReturn(creds());
        when(registry.embeddingProvider("openai")).thenReturn(provider);
        when(provider.classify(any())).thenReturn(LlmErrorCategory.AUTH);
        when(provider.dimensions(any())).thenThrow(new RuntimeException("401 unauthorized"));

        // failOpen=true, which for embed() returns an empty vector. A width has no
        // equivalent: degrading it to a fallback is corruption, not degradation.
        assertThatThrownBy(() ->
                new EmbeddingService(resolver, registry, 30_000, true, 0L).dimensions())
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("401");
    }

    @Test
    void dimensionsRefusesToMemoiseAWidthThatCannotIndexAnything() {
        var resolver = mock(LlmConfigResolver.class);
        var registry = mock(LlmProviderRegistry.class);
        var provider = mock(LlmEmbeddingProvider.class);

        when(resolver.resolveEmbedding()).thenReturn(creds());
        when(registry.embeddingProvider("openai")).thenReturn(provider);
        when(provider.dimensions(any())).thenReturn(0);

        assertThatThrownBy(() ->
                new EmbeddingService(resolver, registry, 30_000, true, 0L).dimensions())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refusing to memoise");
    }

    @Test
    void dimensionsRaisesTheOnboardingErrorWhenNoProviderIsConfigured() {
        var resolver = mock(LlmConfigResolver.class);
        when(resolver.resolveEmbedding()).thenReturn(null);

        assertThatThrownBy(() ->
                new EmbeddingService(resolver, mock(LlmProviderRegistry.class), 30_000, true)
                        .dimensions())
                .as("fail-open must not apply here: a guessed width corrupts the index silently")
                .isInstanceOf(LlmNotConfiguredException.class);
    }

    @Test
    void cosineSimilarityReturnsZeroForEmptyOrMismatchedEmbeddings() {
        var service = new EmbeddingService(
                mock(LlmConfigResolver.class), mock(LlmProviderRegistry.class), 30_000, true);

        assertThat(service.cosineSimilarity(List.of(), List.of(1.0, 2.0))).isZero();
        assertThat(service.cosineSimilarity(List.of(1.0), List.of(1.0, 2.0))).isZero();
        assertThat(service.cosineSimilarity(null, List.of(1.0, 2.0))).isZero();
        assertThat(service.cosineSimilarity(List.of(0.0, 0.0), List.of(1.0, 2.0))).isZero();
    }

    @Test
    void cosineSimilarityOfIdenticalVectorsIsOne() {
        var service = new EmbeddingService(
                mock(LlmConfigResolver.class), mock(LlmProviderRegistry.class), 30_000, true);

        assertThat(service.cosineSimilarity(List.of(1.0, 2.0, 3.0), List.of(1.0, 2.0, 3.0)))
                .isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
    }
}
