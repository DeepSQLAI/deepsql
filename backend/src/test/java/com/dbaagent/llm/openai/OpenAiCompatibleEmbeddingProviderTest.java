package com.dbaagent.llm.openai;

import com.dbaagent.llm.api.LlmCapability;
import com.dbaagent.llm.api.LlmCredentials;
import com.dbaagent.llm.api.LlmErrorCategory;
import com.openai.azure.credential.AzureApiKeyCredential;
import com.openai.client.OpenAIClient;
import com.openai.credential.BearerTokenCredential;
import com.openai.models.embeddings.CreateEmbeddingResponse;
import com.openai.models.embeddings.Embedding;
import com.openai.models.embeddings.EmbeddingCreateParams;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpenAiCompatibleEmbeddingProviderTest {

    private final OpenAiCompatibleEmbeddingProvider provider =
            new OpenAiCompatibleEmbeddingProvider();

    private static LlmCredentials creds(String model) {
        return new LlmCredentials("openai", Map.of(
                "api-key", "k",
                "endpoint", "https://example.invalid/",
                "model", model));
    }

    @Test
    void descriptorClaimsTheOpenAiIdAndOnlyTheEmbeddingCapability() {
        var d = provider.descriptor();
        assertThat(d.id()).isEqualTo("openai");
        assertThat(d.capabilities()).containsExactly(LlmCapability.EMBEDDING);
        assertThat(d.credentialFields()).extracting("name")
                .containsExactly("api-key", "endpoint", "model");
    }

    @Test
    void descriptorDeclaresTheAzureAliases() {
        assertThat(provider.descriptor().aliases())
                .containsExactlyInAnyOrder("azure", "azure-openai");
    }

    /**
     * The aliases must resolve through a real registry, not just be present on the record.
     *
     * <p>Provider ids are free-form operator input — {@code LlmConfigResolver} lowercases
     * {@code llm.embedding.provider} and {@code DEEPSQL_EMBEDDING_PROVIDER} and passes them
     * straight through. An operator who writes "azure" for both roles would otherwise get a
     * working chat model and an {@code UnsupportedLlmProviderException} on every embedding
     * call — which is raised outside EmbeddingService's try/catch, so fail-open cannot
     * absorb it and prod RAG hard-fails while chat looks healthy.
     *
     * <p>Registering both providers together also proves the shared id "openai" does not
     * collide: the registry indexes chat and embedding in separate maps.
     */
    @Test
    void registryResolvesEveryAliasForEmbeddingWithoutCollidingWithChat() {
        var registry = new com.dbaagent.llm.LlmProviderRegistry(
                java.util.List.of(new OpenAiCompatibleChatProvider()),
                java.util.List.of(provider));

        assertThat(registry.embeddingProvider("openai")).isSameAs(provider);
        assertThat(registry.embeddingProvider("azure")).isSameAs(provider);
        assertThat(registry.embeddingProvider("azure-openai")).isSameAs(provider);
        assertThat(registry.embeddingProvider("  AZURE  ")).isSameAs(provider);

        assertThat(registry.chatProvider("azure"))
                .isInstanceOf(OpenAiCompatibleChatProvider.class);
        assertThat(registry.supportedEmbeddingIds()).containsExactly("openai");
    }

    @Test
    void apiKeyFieldIsMarkedSensitiveSoItIsEncryptedAndMasked() {
        assertThat(provider.descriptor().credentialFields())
                .filteredOn(f -> f.name().equals("api-key"))
                .allMatch(com.dbaagent.llm.api.LlmCredentialField::sensitive);
    }

    @Test
    void dimensionsAreResolvedFromTheKnownModelTableWithoutANetworkCall() {
        // The endpoint is unroutable: any network attempt would throw, so a returned
        // value proves the known-model table answered.
        assertThat(provider.dimensions(creds("text-embedding-3-large"))).isEqualTo(3072);
        assertThat(provider.dimensions(creds("text-embedding-3-small"))).isEqualTo(1536);
        assertThat(provider.dimensions(creds("text-embedding-ada-002"))).isEqualTo(1536);
    }

    @Test
    void knownModelLookupIsCaseAndWhitespaceInsensitive() {
        assertThat(provider.dimensions(creds("  Text-Embedding-3-Large  "))).isEqualTo(3072);
    }

    @Test
    void classifyMapsHttpStatusesToCategories() {
        assertThat(provider.classify(new RuntimeException("HTTP 401 Unauthorized")))
                .isEqualTo(LlmErrorCategory.AUTH);
        assertThat(provider.classify(new RuntimeException("403 forbidden")))
                .isEqualTo(LlmErrorCategory.AUTH);
        assertThat(provider.classify(new RuntimeException("DeploymentNotFound")))
                .isEqualTo(LlmErrorCategory.MODEL_NOT_FOUND);
        assertThat(provider.classify(new RuntimeException("429 Too Many Requests")))
                .isEqualTo(LlmErrorCategory.RATE_LIMIT);
        assertThat(provider.classify(new RuntimeException("503 Service Unavailable")))
                .isEqualTo(LlmErrorCategory.TRANSIENT);
    }

    @Test
    void classifyWalksTheCauseChain() {
        assertThat(provider.classify(
                new RuntimeException("wrapper", new RuntimeException("429 slow down"))))
                .isEqualTo(LlmErrorCategory.RATE_LIMIT);
    }

    @Test
    void classifyTreatsIoFailuresAsTransientEvenWithNoMessage() {
        assertThat(provider.classify(new RuntimeException("Request failed", new IOException())))
                .isEqualTo(LlmErrorCategory.TRANSIENT);
    }

    @Test
    void classifyFallsBackToUnknown() {
        assertThat(provider.classify(new RuntimeException("something odd")))
                .isEqualTo(LlmErrorCategory.UNKNOWN);
        assertThat(provider.classify(new RuntimeException()))
                .isEqualTo(LlmErrorCategory.UNKNOWN);
    }

    @Test
    void embedBatchOfNoTextsMakesNoCallAndReturnsEmpty() {
        // Unroutable endpoint again: returning at all proves the short-circuit fired.
        assertThat(provider.embedBatch(java.util.List.of(), creds("text-embedding-3-large")))
                .isEmpty();
    }

    // ------------------------------------------------------------------
    // Paths that actually reach the API, exercised through the client seam.
    // ------------------------------------------------------------------

    private static Embedding embedding(long index, double... values) {
        List<Float> floats = new ArrayList<>();
        for (double v : values) {
            floats.add((float) v);
        }
        return Embedding.builder().index(index).embedding(floats).build();
    }

    private static CreateEmbeddingResponse response(Embedding... items) {
        return CreateEmbeddingResponse.builder()
                .data(List.of(items))
                .model("text-embedding-3-large")
                .usage(CreateEmbeddingResponse.Usage.builder()
                        .promptTokens(1).totalTokens(1).build())
                .build();
    }

    /** A provider whose client always answers with {@code response}. */
    private static OpenAiCompatibleEmbeddingProvider providerReturning(
            CreateEmbeddingResponse response) {
        OpenAIClient client = mock(OpenAIClient.class, Answers.RETURNS_DEEP_STUBS);
        when(client.embeddings().create(any(EmbeddingCreateParams.class))).thenReturn(response);
        return new OpenAiCompatibleEmbeddingProvider(c -> client);
    }

    @Test
    void embedReturnsTheFirstVectorAsDoubles() {
        var p = providerReturning(response(embedding(0, 0.5, -0.25)));
        assertThat(p.embed("hello", creds("text-embedding-3-large")))
                .containsExactly(0.5, -0.25);
    }

    @Test
    void embedReturnsEmptyWhenTheApiReturnsNoData() {
        var p = providerReturning(response());
        assertThat(p.embed("hello", creds("text-embedding-3-large"))).isEmpty();
    }

    /**
     * The defect this guards against is silent: if vectors were taken in response-array
     * order, a reordered response would associate every vector with the wrong text and
     * nothing downstream would error — only retrieval quality would quietly rot.
     */
    @Test
    void embedBatchRestoresInputOrderWhenTheApiReturnsResultsShuffled() {
        var p = providerReturning(response(
                embedding(2, 3.0),
                embedding(0, 1.0),
                embedding(1, 2.0)));

        assertThat(p.embedBatch(List.of("a", "b", "c"), creds("text-embedding-3-large")))
                .containsExactly(List.of(1.0), List.of(2.0), List.of(3.0));
    }

    @Test
    void embedBatchRefusesAShortResponseRatherThanMisattributingVectors() {
        var p = providerReturning(response(embedding(0, 1.0), embedding(1, 2.0)));

        assertThatThrownBy(() ->
                p.embedBatch(List.of("a", "b", "c"), creds("text-embedding-3-large")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("2 vectors for 3 inputs");
    }

    @Test
    void dimensionsProbesLiveForAModelMissingFromTheKnownTable() {
        var p = providerReturning(response(embedding(0, 1.0, 2.0, 3.0, 4.0)));
        assertThat(p.dimensions(creds("my-custom-azure-deployment"))).isEqualTo(4);
    }

    /**
     * pgvector's text-column fallback has no dimension constraint, so a 0 would be stored
     * without complaint and corrupt retrieval invisibly. Throwing is the only place the
     * mismatch is still detectable.
     */
    @Test
    void dimensionsRefusesToReportZeroWhenTheProbeReturnsNothing() {
        var p = providerReturning(response());
        assertThatThrownBy(() -> p.dimensions(creds("my-custom-azure-deployment")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("my-custom-azure-deployment");
    }

    // ------------------------------------------------------------------
    // Client construction. Every test above injects the clientFactory stub, so
    // build() and normalizeBaseUrl() never ran — the embedding-side twin of the
    // chat-side auth defect had zero coverage while chat auth had seven tests.
    // ------------------------------------------------------------------

    @Test
    void anAzureEndpointAuthenticatesWithAzuresApiKeyCredential() {
        var credential = OpenAiCompatibleEmbeddingProvider.credentialFor(
                "https://example.openai.azure.com/openai/v1/", "azure-key");

        assertThat(credential).isInstanceOf(AzureApiKeyCredential.class);
        assertThat(((AzureApiKeyCredential) credential).apiKey()).isEqualTo("azure-key");
    }

    /**
     * api.openai.com, vLLM, Ollama, LM Studio and LiteLLM all expect
     * {@code Authorization: Bearer}. Sending Azure's {@code api-key} header instead is
     * exactly the 401 that took ten tasks to find on the chat side.
     */
    @Test
    void aNonAzureEndpointAuthenticatesWithABearerToken() {
        var credential = OpenAiCompatibleEmbeddingProvider.credentialFor(
                "https://api.openai.com/v1/", "sk-openai");

        assertThat(credential).isInstanceOf(BearerTokenCredential.class);
        assertThat(((BearerTokenCredential) credential).token()).isEqualTo("sk-openai");
        assertThat(credential).isNotInstanceOf(AzureApiKeyCredential.class);
    }

    @Test
    void sovereignAzureCloudsAlsoGetTheAzureCredential() {
        assertThat(OpenAiCompatibleEmbeddingProvider.credentialFor(
                "https://example.openai.azure.us/openai/v1/", "k"))
                .isInstanceOf(AzureApiKeyCredential.class);
        assertThat(OpenAiCompatibleEmbeddingProvider.credentialFor(
                "https://example.openai.azure.cn/openai/v1/", "k"))
                .isInstanceOf(AzureApiKeyCredential.class);
    }

    /**
     * An Azure resource root has to grow the {@code openai/v1/} prefix or every request
     * 404s; a non-Azure base URL must not, or {@code api.openai.com/v1} becomes
     * {@code api.openai.com/v1/openai/v1}.
     */
    @Test
    void anAzureResourceRootIsNormalisedToTheOpenAiV1BaseUrl() {
        assertThat(OpenAiCompatibleEmbeddingProvider.normalizeBaseUrl(
                "https://example.openai.azure.com"))
                .isEqualTo("https://example.openai.azure.com/openai/v1/");
    }

    @Test
    void anAzureBaseUrlThatAlreadyCarriesThePrefixIsLeftAlone() {
        assertThat(OpenAiCompatibleEmbeddingProvider.normalizeBaseUrl(
                "https://example.openai.azure.com/openai/v1"))
                .isEqualTo("https://example.openai.azure.com/openai/v1/");
    }

    @Test
    void aNonAzureBaseUrlOnlyGainsATrailingSlash() {
        assertThat(OpenAiCompatibleEmbeddingProvider.normalizeBaseUrl("  https://api.openai.com/v1  "))
                .isEqualTo("https://api.openai.com/v1/");
        assertThat(OpenAiCompatibleEmbeddingProvider.normalizeBaseUrl("http://localhost:11434/v1/"))
                .isEqualTo("http://localhost:11434/v1/");
    }
}
