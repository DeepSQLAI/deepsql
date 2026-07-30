package com.dbaagent.llm.openai;

import com.dbaagent.llm.api.LlmCapability;
import com.dbaagent.llm.api.LlmCredentialField;
import com.dbaagent.llm.api.LlmCredentials;
import com.dbaagent.llm.api.LlmEmbeddingProvider;
import com.dbaagent.llm.api.LlmErrorCategory;
import com.dbaagent.llm.api.LlmProviderDescriptor;
import com.openai.azure.credential.AzureApiKeyCredential;
import com.openai.credential.BearerTokenCredential;
import com.openai.credential.Credential;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.embeddings.CreateEmbeddingResponse;
import com.openai.models.embeddings.Embedding;
import com.openai.models.embeddings.EmbeddingCreateParams;
import com.openai.models.embeddings.EmbeddingModel;

import java.io.IOException;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Embeddings via the official OpenAI Java SDK, which also speaks Azure OpenAI through
 * {@link AzureApiKeyCredential}. Clients are cached per credential so each call does not
 * rebuild an HTTP client.
 *
 * <p>The descriptor id {@code openai} and the {@code azure} aliases are shared with
 * {@link OpenAiCompatibleChatProvider} on purpose: {@code LlmProviderRegistry} indexes chat
 * and embedding providers in separate maps, so one name means "the OpenAI-compatible
 * provider" in both roles and cannot collide across them.
 *
 * <p>Carrying the same aliases as the chat provider is what keeps the two roles symmetric.
 * Provider ids are free-form operator input — {@code LlmConfigResolver} reads
 * {@code llm.embedding.provider} and {@code DEEPSQL_EMBEDDING_PROVIDER} and only lowercases
 * them. An operator who writes "azure" for both roles must get a working embedding
 * provider, not an {@code UnsupportedLlmProviderException}; that exception is raised while
 * resolving the provider, before {@code EmbeddingService}'s try/catch, so fail-open cannot
 * absorb it and RAG would hard-fail while chat carried on looking healthy.
 */
@Component
public class OpenAiCompatibleEmbeddingProvider implements LlmEmbeddingProvider {

    private static final String DEFAULT_MODEL = "text-embedding-3-large";
    private static final String DEFAULT_ENDPOINT = "https://api.openai.com/v1";

    private static final LlmProviderDescriptor DESCRIPTOR = new LlmProviderDescriptor(
            "openai",
            Set.of("azure", "azure-openai"),
            "OpenAI / Azure OpenAI embeddings",
            Set.of(LlmCapability.EMBEDDING),
            List.of(
                LlmCredentialField.secret("api-key", "API key"),
                LlmCredentialField.required("endpoint", "Endpoint base URL", DEFAULT_ENDPOINT),
                LlmCredentialField.required("model", "Embedding model", DEFAULT_MODEL)
            ),
            8192);

    /**
     * Known widths, so the embedding lock can be checked without a network call.
     *
     * <p>Keys are model ids. On Azure the {@code model} field is a <em>deployment</em>
     * name, which the operator chooses; only a deployment named after the model it serves
     * hits this table, and everything else falls through to the live probe in
     * {@link #dimensions}. That is the safe direction: a miss costs one request, never a
     * wrong answer.
     */
    private static final Map<String, Integer> KNOWN_DIMENSIONS = Map.of(
            "text-embedding-3-large", 3072,
            "text-embedding-3-small", 1536,
            "text-embedding-ada-002", 1536);

    /**
     * Keyed by non-secret signature plus the api-key, so rotating the key builds a fresh
     * client instead of silently reusing one holding the revoked credential. The key is
     * never logged and never leaves this map.
     */
    private final ConcurrentHashMap<String, OpenAIClient> clients = new ConcurrentHashMap<>();

    /**
     * How a client is obtained for a credential bundle. Production builds a real HTTP
     * client; tests substitute a stub so the request/response paths — order restoration in
     * {@link #embedBatch} above all — are reachable without a network.
     */
    private final Function<LlmCredentials, OpenAIClient> clientFactory;

    /**
     * Marked explicitly because the package-private test seam below is a second candidate
     * constructor; without this Spring has to guess which to use.
     */
    @Autowired
    public OpenAiCompatibleEmbeddingProvider() {
        this.clientFactory = this::build;
    }

    OpenAiCompatibleEmbeddingProvider(Function<LlmCredentials, OpenAIClient> clientFactory) {
        this.clientFactory = clientFactory;
    }

    @Override
    public LlmProviderDescriptor descriptor() {
        return DESCRIPTOR;
    }

    private OpenAIClient client(LlmCredentials c) {
        return clients.computeIfAbsent(
                c.signature() + "|" + c.getOrDefault("api-key", ""),
                key -> clientFactory.apply(c));
    }

    private OpenAIClient build(LlmCredentials c) {
        String baseUrl = normalizeBaseUrl(c.getOrDefault("endpoint", DEFAULT_ENDPOINT));
        return OpenAIOkHttpClient.builder()
                .baseUrl(baseUrl)
                .maxRetries(0)
                .timeout(Duration.ofSeconds(20))
                .credential(credentialFor(baseUrl, c.getOrDefault("api-key", "")))
                .build();
    }

    /**
     * Azure authenticates with an {@code api-key} header; every other OpenAI-compatible
     * endpoint (api.openai.com, vLLM, Ollama, LM Studio, LiteLLM) expects
     * {@code Authorization: Bearer}. Dispatching on the endpoint shape — not on a provider
     * id — keeps one provider able to serve both without a provider-type switch.
     *
     * <p>Returning the {@link Credential} rather than branching between
     * {@code builder.credential(...)} and {@code builder.apiKey(...)} in place is what makes
     * the choice assertable. {@code apiKey(k)} is the SDK's own shorthand for
     * {@code credential(BearerTokenCredential.create(k))}, so this is the same wire
     * behaviour — but the decision is now a value a test can look at instead of something
     * buried in builder wiring that no test ever reached. This is the embedding-side twin
     * of the chat-side auth defect that survived nine tasks; it had zero coverage because
     * every existing test injects the {@code clientFactory} stub and so never executes
     * {@link #build}.
     */
    static Credential credentialFor(String baseUrl, String apiKey) {
        return isAzure(baseUrl)
                ? AzureApiKeyCredential.create(apiKey)
                : BearerTokenCredential.create(apiKey);
    }

    /**
     * Delegates to the shared definition so the chat model and this provider can never
     * disagree about what "Azure" means — a divergence would authenticate correctly in one
     * role and 401 in the other, with nothing in the configuration to explain it.
     */
    private static boolean isAzure(String baseUrl) {
        return OpenAiEndpoints.isAzure(baseUrl);
    }

    /** Delegates for the same reason {@link #isAzure} does — see {@link OpenAiEndpoints}. */
    static String normalizeBaseUrl(String configured) {
        return OpenAiEndpoints.normalizeBaseUrl(configured);
    }

    private static String model(LlmCredentials c) {
        return c.getOrDefault("model", DEFAULT_MODEL).trim();
    }

    @Override
    public List<Double> embed(String text, LlmCredentials c) {
        EmbeddingCreateParams params = EmbeddingCreateParams.builder()
                .model(EmbeddingModel.of(model(c)))
                .input(text == null ? "" : text)
                .build();
        CreateEmbeddingResponse response = client(c).embeddings().create(params);
        List<Embedding> data = response.data();
        return data.isEmpty() ? List.of() : toDoubles(data.getFirst());
    }

    /**
     * One request for the whole batch — the provider API is inherently batched, so calling
     * it per text would multiply latency and rate-limit pressure by the batch size.
     *
     * <p>Order is restored from each item's {@code index}, which the API defines as the
     * position of the corresponding input. Relying on response array order instead would
     * be an unstated assumption; a reordering would then misattribute every vector with no
     * error anywhere.
     */
    @Override
    public List<List<Double>> embedBatch(List<String> texts, LlmCredentials c) {
        if (texts.isEmpty()) {
            return List.of();
        }
        List<String> inputs = texts.stream().map(t -> t == null ? "" : t).toList();
        EmbeddingCreateParams params = EmbeddingCreateParams.builder()
                .model(EmbeddingModel.of(model(c)))
                .inputOfArrayOfStrings(inputs)
                .build();
        CreateEmbeddingResponse response = client(c).embeddings().create(params);

        List<Embedding> ordered = new ArrayList<>(response.data());
        ordered.sort(Comparator.comparingLong(Embedding::index));
        if (ordered.size() != inputs.size()) {
            throw new IllegalStateException(
                    "Embedding provider returned " + ordered.size() + " vectors for "
                    + inputs.size() + " inputs; refusing to guess which input each belongs to");
        }
        return ordered.stream().map(OpenAiCompatibleEmbeddingProvider::toDoubles).toList();
    }

    private static List<Double> toDoubles(Embedding embedding) {
        return embedding.embedding().stream().map(Float::doubleValue).toList();
    }

    /**
     * Known model ids answer from {@link #KNOWN_DIMENSIONS} with no network call; anything
     * else — a custom Azure deployment name, a self-hosted model — is probed live.
     *
     * <p>A zero is never returned. pgvector's {@code text}-column fallback mode has no
     * dimension constraint, so a wrong width would store cleanly and corrupt retrieval
     * silently; failing loudly here is the only place that mismatch is still detectable.
     */
    @Override
    public int dimensions(LlmCredentials c) {
        String model = model(c).toLowerCase(Locale.ROOT);
        Integer known = KNOWN_DIMENSIONS.get(model);
        if (known != null) {
            return known;
        }
        int probed = embed("dimension probe", c).size();
        if (probed == 0) {
            throw new IllegalStateException(
                    "Embedding model '" + model(c) + "' returned no vector for the dimension "
                    + "probe, so its width is unknown. Refusing to report 0 — a wrong width "
                    + "is stored without error and corrupts retrieval silently.");
        }
        return probed;
    }

    @Override
    public LlmErrorCategory classify(Throwable throwable) {
        for (Throwable t = throwable; t != null; t = t.getCause()) {
            // Transport failures often carry no useful message, so match by type first.
            if (t instanceof IOException || t instanceof UnresolvedAddressException) {
                return LlmErrorCategory.TRANSIENT;
            }
            String message = t.getMessage();
            if (message == null) {
                continue;
            }
            String m = message.toLowerCase(Locale.ROOT);
            if (m.contains("401") || m.contains("403")) {
                return LlmErrorCategory.AUTH;
            }
            if (m.contains("404") || m.contains("deploymentnotfound")) {
                return LlmErrorCategory.MODEL_NOT_FOUND;
            }
            if (m.contains("429")) {
                return LlmErrorCategory.RATE_LIMIT;
            }
            if (m.contains("maximum context length") || m.contains("context_length_exceeded")) {
                return LlmErrorCategory.CONTEXT_LENGTH;
            }
            if (m.contains("500") || m.contains("502") || m.contains("503")
                    || m.contains("504")) {
                return LlmErrorCategory.TRANSIENT;
            }
        }
        return LlmErrorCategory.UNKNOWN;
    }
}
