package com.dbaagent.service;

import com.dbaagent.llm.LlmConfigResolver;
import com.dbaagent.llm.LlmProviderRegistry;
import com.dbaagent.llm.api.LlmCredentials;
import com.dbaagent.llm.api.LlmEmbeddingProvider;
import com.dbaagent.llm.api.LlmErrorCategory;
import com.dbaagent.llm.api.LlmNotConfiguredException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Creates text embeddings by delegating to whichever {@link LlmEmbeddingProvider} the
 * operator has configured. Holds no HTTP client and no provider-specific knowledge.
 *
 * <p>Provider and credentials are resolved per call rather than at construction, so a
 * credential rotation or a provider switch through the setup wizard takes effect without
 * a restart.
 */
@Service
@Slf4j
public class EmbeddingService {

    /** Retry budget: 2 retries after the first try, matching the pre-delegation service. */
    private static final int MAX_RETRY_ATTEMPTS = 2;
    private static final long RETRY_BACKOFF_MS = 750L;

    /**
     * Ceiling on a provider-supplied {@code Retry-After}. Brain init embeds documents on
     * a bounded worker pool, so a long sleep here stalls the whole stage; 30s is longer
     * than any real rate-limit cooldown observed and short enough to stay a hiccup.
     */
    private static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(30);

    private final LlmConfigResolver resolver;
    private final LlmProviderRegistry registry;
    private final int maxChars;
    private final boolean failOpen;
    private final long retryBackoffMs;

    /** Ceiling applied to a provider-supplied {@code Retry-After}. See {@link #MAX_RETRY_AFTER}. */
    private final Duration maxRetryAfter;

    /**
     * Memoised vector widths, keyed by the non-secret credential signature. Bounded in
     * practice by the number of distinct provider/endpoint/model combinations an operator
     * configures, which is one in every deployment seen so far.
     */
    private final ConcurrentHashMap<String, Integer> dimensionsBySignature =
            new ConcurrentHashMap<>();

    @Autowired
    public EmbeddingService(
            LlmConfigResolver resolver,
            LlmProviderRegistry registry,
            @Value("${app.embedding.max-chars:30000}") int maxChars,
            @Value("${app.embedding.fail-open:true}") boolean failOpen) {
        this(resolver, registry, maxChars, failOpen, RETRY_BACKOFF_MS);
    }

    /** Test seam: lets the retry tests run with no real sleeping. */
    EmbeddingService(LlmConfigResolver resolver, LlmProviderRegistry registry,
                     int maxChars, boolean failOpen, long retryBackoffMs) {
        this(resolver, registry, maxChars, failOpen, retryBackoffMs, MAX_RETRY_AFTER);
    }

    /**
     * Test seam: also lowers the {@code Retry-After} ceiling, so the cap can be asserted
     * without a test that actually sleeps for it.
     */
    EmbeddingService(LlmConfigResolver resolver, LlmProviderRegistry registry,
                     int maxChars, boolean failOpen, long retryBackoffMs,
                     Duration maxRetryAfter) {
        this.resolver = resolver;
        this.registry = registry;
        this.maxChars = maxChars;
        this.failOpen = failOpen;
        this.retryBackoffMs = retryBackoffMs;
        this.maxRetryAfter = maxRetryAfter;
    }

    /** text-embedding-3-large accepts 8192 tokens, roughly 4 chars per token. */
    private String truncate(String text) {
        return (text != null && text.length() > maxChars) ? text.substring(0, maxChars) : text;
    }

    /**
     * Embed one text. Returns an empty list when fail-open is on and the provider failed.
     */
    public List<Double> createEmbedding(String text) {
        LlmCredentials credentials = requireCredentials();
        LlmEmbeddingProvider provider = registry.embeddingProvider(credentials.providerId());
        return attempt(provider, credentials,
                () -> provider.embed(truncate(text), credentials), List.of());
    }

    /**
     * Embed a batch. The returned list is positionally aligned with {@code texts}; on a
     * fail-open failure every position holds an empty vector, so callers that zip the two
     * lists together stay aligned.
     */
    public List<List<Double>> createEmbeddings(List<String> texts) {
        LlmCredentials credentials = requireCredentials();
        LlmEmbeddingProvider provider = registry.embeddingProvider(credentials.providerId());
        List<String> truncated = texts.stream().map(this::truncate).toList();
        return attempt(provider, credentials,
                () -> provider.embedBatch(truncated, credentials),
                Collections.nCopies(texts.size(), List.of()));
    }

    /**
     * The active provider's vector width.
     *
     * <p>Spring AI's vector stores ask for this on every add and every search, so the
     * answer is memoised. The key is {@link LlmCredentials#signature()} — provider,
     * endpoint and model, no secret — so switching model through the wizard re-resolves
     * while a key rotation, which cannot change the width, does not.
     *
     * <p>Never guesses. {@link LlmEmbeddingProvider#dimensions} answers known models from
     * a table and probes anything else live; a wrong width is stored without error and
     * corrupts retrieval silently, so a failure here propagates rather than defaulting —
     * this is the one embedding call that ignores fail-open.
     *
     * <p>It does not skip the <em>retry</em>, though. For an unrecognised model id the
     * provider probes with a live request, and a failure is not memoised, so without a
     * retry a single socket blip would make the next RAG search throw on its first attempt.
     */
    public int dimensions() {
        LlmCredentials credentials = requireCredentials();
        Integer cached = dimensionsBySignature.get(credentials.signature());
        if (cached != null) {
            return cached;
        }
        LlmEmbeddingProvider provider = registry.embeddingProvider(credentials.providerId());
        int width = attempt(provider, credentials,
                () -> provider.dimensions(credentials), 0, false);
        if (width <= 0) {
            // Unreachable while mayFailOpen is false above, and deliberately guarded so it
            // stays that way: memoising a zero width would pin the corruption in place for
            // the life of the process.
            throw new IllegalStateException(
                    "Embedding provider reported a width of " + width + " for model "
                    + credentials.getOrDefault("model", "<unset>")
                    + "; refusing to memoise a width that cannot index anything");
        }
        dimensionsBySignature.put(credentials.signature(), width);
        return width;
    }

    /**
     * Runs {@code call}, retrying while the provider classifies the failure as retryable.
     *
     * <p>The SDK client is built with {@code maxRetries(0)}, so this is the only retry in
     * the embedding path. Without it a single socket timeout on the RAG retrieval path
     * reaches the user in prod, where fail-open is off — a regression against the service
     * this replaced, which survived three attempts.
     *
     * <p>The decision is {@link LlmErrorCategory#isRetryable()}, not a message substring:
     * that taxonomy exists precisely so retry policy stops being provider-specific.
     */
    private <T> T attempt(LlmEmbeddingProvider provider, LlmCredentials credentials,
                          Supplier<T> call, T failOpenValue) {
        return attempt(provider, credentials, call, failOpenValue, failOpen);
    }

    /**
     * As above, but with fail-open decided per call site rather than by configuration.
     *
     * <p>{@link #dimensions()} passes {@code false}: degrading a vector <em>width</em> to a
     * fallback is not degradation, it is corruption, and no operator setting should be able
     * to ask for it.
     */
    private <T> T attempt(LlmEmbeddingProvider provider, LlmCredentials credentials,
                          Supplier<T> call, T failOpenValue, boolean mayFailOpen) {
        for (int retries = 0; ; retries++) {
            try {
                return call.get();
            } catch (RuntimeException e) {
                LlmErrorCategory category = provider.classify(e);
                if (retries >= MAX_RETRY_ATTEMPTS || !category.isRetryable()
                        || !backoff(retries, category, credentials, retryAfterHint(provider, e))) {
                    return handleFailure(category, credentials, e, failOpenValue, mayFailOpen);
                }
            }
        }
    }

    /**
     * The provider's stated retry delay, or zero when it has no opinion.
     *
     * <p>A provider that throws while reporting an error must not replace the error:
     * the original failure is what the caller needs to see, so any secondary failure
     * here degrades to "no hint" rather than propagating.
     */
    private Duration retryAfterHint(LlmEmbeddingProvider provider, RuntimeException failure) {
        try {
            return provider.retryAfter(failure).orElse(Duration.ZERO);
        } catch (RuntimeException ignored) {
            return Duration.ZERO;
        }
    }

    /**
     * Sleeps before the next attempt. Returns false if interrupted, so the caller stops.
     *
     * <p>Waits for whichever is longer: our own escalating schedule, or the delay the
     * provider asked for. Retrying before a rate limiter's cooldown expires does not
     * merely waste the attempt — the attempt itself is counted, so an impatient client
     * extends the very window it is waiting on. The provider's number is remote input,
     * so it is capped by {@link #MAX_RETRY_AFTER}; without that cap a single header
     * could park a brain-init worker thread indefinitely.
     */
    private boolean backoff(int retries, LlmErrorCategory category, LlmCredentials credentials,
                            Duration providerHint) {
        long ownScheduleMs = retryBackoffMs * (retries + 1);
        long hintMs = Math.min(Math.max(providerHint.toMillis(), 0L), maxRetryAfter.toMillis());
        long delayMs = Math.max(ownScheduleMs, hintMs);
        log.warn("Embedding attempt {}/{} failed — provider={} category={} model={}; retrying in {}ms{}",
                retries + 1, MAX_RETRY_ATTEMPTS + 1, credentials.providerId(), category,
                credentials.getOrDefault("model", "<unset>"), delayMs,
                hintMs > ownScheduleMs ? " (honouring provider Retry-After)" : "");
        if (delayMs <= 0) {
            return true;
        }
        try {
            Thread.sleep(delayMs);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * The resolver reports absence by returning null; raising the onboarding error is this
     * service's job, so callers get a message pointing at setup rather than an NPE.
     */
    private LlmCredentials requireCredentials() {
        LlmCredentials credentials = resolver.resolveEmbedding();
        if (credentials == null) {
            throw new LlmNotConfiguredException("embedding");
        }
        return credentials;
    }

    /**
     * Fail-open still logs in full. Silent degradation is the failure mode to avoid: an
     * empty vector produces no error anywhere downstream, only worse retrieval.
     *
     * <p>Only the provider id, model and error category are logged — never a credential.
     */
    private <T> T handleFailure(LlmErrorCategory category, LlmCredentials credentials,
                                RuntimeException e, T failOpenValue, boolean mayFailOpen) {
        log.warn("Embedding failed — provider={} category={} model={} failOpen={}",
                credentials.providerId(), category,
                credentials.getOrDefault("model", "<unset>"), mayFailOpen, e);
        if (mayFailOpen) {
            return failOpenValue;
        }
        throw e;
    }

    /** Cosine similarity of two embeddings; 0.0 for null, empty or mismatched widths. */
    public double cosineSimilarity(List<Double> embedding1, List<Double> embedding2) {
        if (embedding1 == null || embedding2 == null
                || embedding1.isEmpty() || embedding2.isEmpty()
                || embedding1.size() != embedding2.size()) {
            return 0.0;
        }

        double dotProduct = 0.0;
        double norm1 = 0.0;
        double norm2 = 0.0;

        for (int i = 0; i < embedding1.size(); i++) {
            dotProduct += embedding1.get(i) * embedding2.get(i);
            norm1 += embedding1.get(i) * embedding1.get(i);
            norm2 += embedding2.get(i) * embedding2.get(i);
        }

        if (norm1 == 0.0 || norm2 == 0.0) {
            return 0.0;
        }

        return dotProduct / (Math.sqrt(norm1) * Math.sqrt(norm2));
    }
}
