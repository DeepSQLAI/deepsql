package com.dbaagent.service;

import com.dbaagent.llm.LlmConfigResolver;
import com.dbaagent.llm.LlmProviderRegistry;
import com.dbaagent.llm.api.LlmCredentials;
import com.dbaagent.llm.api.LlmEmbeddingProvider;
import com.dbaagent.llm.api.LlmErrorCategory;
import com.dbaagent.llm.api.LlmNotConfiguredException;
import com.dbaagent.model.LlmUsageRole;
import com.dbaagent.service.llm.LlmUsageRecorder;
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
    /** Halvings allowed before giving up: 30,000 chars reaches ~1,875 in four. */
    private static final int MAX_SHRINK_ATTEMPTS = 4;
    /** Floor for shrinking. Below this the document is too small to be worth indexing. */
    private static final int MIN_EMBEDDING_CHARS = 1_000;
    private static final long RETRY_BACKOFF_MS = 750L;

    /** See {@link #recordEmbeddingUsage}: schema text is denser than prose. */
    private static final int ESTIMATED_CHARS_PER_TOKEN = 3;

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

    /**
     * Usage accounting. Injected through a setter rather than the constructor so the three
     * existing test seams below keep working unchanged, and so a null recorder (any test
     * constructing this directly) simply means "do not account".
     */
    private LlmUsageRecorder usageRecorder;

    @Autowired(required = false)
    public void setUsageRecorder(LlmUsageRecorder usageRecorder) {
        this.usageRecorder = usageRecorder;
    }

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

    /**
     * Cut {@code text} to a character budget.
     *
     * <p>The budget is a <em>guess</em> at the model's token window, and it is wrong often
     * enough to matter. The old default assumed "roughly 4 chars per token", which holds
     * for prose but not for what this service actually embeds: schema and relationship
     * documents are dense identifiers — {@code ORDERS.customer_id}, underscores,
     * punctuation, repeated scaffolding — that tokenize closer to 2-3 chars per token. At
     * that density the 30,000-char default is 10,000-15,000 tokens, well past the 8,192 a
     * text-embedding-3-large call accepts, and the provider rejects the request outright.
     *
     * <p>No fixed ratio is safe across content, so the budget is not trusted to be right.
     * {@link #embedWithShrink} lets the provider's own CONTEXT_LENGTH rejection drive the
     * budget down until the call fits.
     */
    private String truncate(String text, int budget) {
        return (text != null && text.length() > budget) ? text.substring(0, budget) : text;
    }

    /**
     * Embed one text. Returns an empty list when fail-open is on and the provider failed.
     */
    public List<Double> createEmbedding(String text) {
        LlmCredentials credentials = requireCredentials();
        LlmEmbeddingProvider provider = registry.embeddingProvider(credentials.providerId());
        long startedAt = System.nanoTime();
        try {
            List<Double> result = embedWithShrink(provider, credentials,
                    budget -> provider.embed(truncate(text, budget), credentials),
                    List.of(),
                    text == null ? 0 : text.length());
            // Fail-open returns an empty vector for a call that actually failed. Recording
            // that as a success would put phantom tokens on the ledger for work the
            // provider never did.
            recordEmbeddingUsage(credentials, charsOf(text), startedAt, !result.isEmpty(), null);
            return result;
        } catch (RuntimeException e) {
            recordEmbeddingUsage(credentials, charsOf(text), startedAt, false, e);
            throw e;
        }
    }

    /**
     * Run an embedding call, halving the character budget each time the provider says the
     * input is too long.
     *
     * <p>{@link LlmErrorCategory#CONTEXT_LENGTH} already documents itself as "never retry;
     * the caller may trim" — but until now no caller trimmed. The rejection fell through to
     * fail-open, the document was skipped, and because the input is deterministic it was
     * skipped again on every subsequent rebuild. That is a permanent hole in the index
     * wearing the costume of a transient blip.
     *
     * <p>Shrinking is deliberately driven by the provider rather than by counting tokens
     * locally: it needs no tokenizer dependency, and it stays correct for any content and
     * any model window, including ones whose ratio we have never measured.
     *
     * <p>Only CONTEXT_LENGTH shrinks. Every other category keeps its existing behaviour —
     * retries and fail-open are unchanged — because shrinking the input answers nothing
     * about a rate limit or a bad credential.
     */
    private <T> T embedWithShrink(LlmEmbeddingProvider provider, LlmCredentials credentials,
                                  java.util.function.IntFunction<T> call, T failOpenValue,
                                  int longestInput) {
        // Anchor to what is actually being sent, not to the configured ceiling. Seeding
        // from maxChars makes the first halvings no-ops whenever the input is already
        // under it — the same bytes, resent and rejected — and leaves anything below
        // maxChars/2^MAX_SHRINK_ATTEMPTS unable to shrink at all, which is the very
        // document this method exists to rescue.
        int budget = Math.min(maxChars, Math.max(longestInput, 1));
        for (int shrink = 0; ; shrink++) {
            final int attemptBudget = budget;
            try {
                // attemptOrThrow, not attempt: a CONTEXT_LENGTH rejection has to reach us
                // unlogged. Fail-open would turn it into an empty vector indistinguishable
                // from a real one, and the logging path would report a failure for a call
                // that is about to succeed.
                return attemptOrThrow(provider, credentials, () -> call.apply(attemptBudget));
            } catch (RuntimeException e) {
                LlmErrorCategory category = provider.classify(e);
                if (category != LlmErrorCategory.CONTEXT_LENGTH
                        || shrink >= MAX_SHRINK_ATTEMPTS
                        || budget <= MIN_EMBEDDING_CHARS) {
                    // Out of room to shrink, or not a length problem: honour the operator's
                    // fail-open setting exactly as before this method existed. This is the
                    // one place a terminal failure is logged.
                    return handleFailure(category, credentials, e, failOpenValue, failOpen);
                }
                int next = Math.max(MIN_EMBEDDING_CHARS, budget / 2);
                log.warn("Embedding rejected as too long at {} chars sent; retrying at {}", budget, next);
                budget = next;
            }
        }
    }

    /**
     * Embed a batch. The returned list is positionally aligned with {@code texts}; on a
     * fail-open failure every position holds an empty vector, so callers that zip the two
     * lists together stay aligned.
     */
    public List<List<Double>> createEmbeddings(List<String> texts) {
        LlmCredentials credentials = requireCredentials();
        LlmEmbeddingProvider provider = registry.embeddingProvider(credentials.providerId());
        // The budget is per-request, not per-member, so a halving forced by one oversized
        // text also trims every other member above the new budget. That is a real cost and
        // not something this method can avoid: the provider rejects the request, not a
        // document, and it does not say which member was at fault. Seeding from the
        // longest member keeps the first halving meaningful; callers that cannot afford
        // collateral truncation should embed individually.
        //
        // Note also that a provider may reject on the request's AGGREGATE token count, in
        // which case trimming members is the right lever but the floor may be reached
        // before the batch fits.
        int longest = texts.stream().filter(java.util.Objects::nonNull)
                .mapToInt(String::length).max().orElse(0);
        long totalChars = texts.stream().filter(java.util.Objects::nonNull)
                .mapToLong(String::length).sum();
        long startedAt = System.nanoTime();
        try {
            List<List<Double>> result = embedWithShrink(provider, credentials,
                    budget -> provider.embedBatch(
                            texts.stream().map(t -> truncate(t, budget)).toList(), credentials),
                    Collections.nCopies(texts.size(), List.of()),
                    longest);
            boolean succeeded = result.stream().anyMatch(v -> !v.isEmpty());
            recordEmbeddingUsage(credentials, totalChars, startedAt, succeeded, null);
            return result;
        } catch (RuntimeException e) {
            recordEmbeddingUsage(credentials, totalChars, startedAt, false, e);
            throw e;
        }
    }

    private static long charsOf(String text) {
        return text == null ? 0L : text.length();
    }

    /**
     * Records one embedding call with <em>estimated</em> token counts.
     *
     * <p>{@link LlmEmbeddingProvider} returns vectors and nothing else — no usage block —
     * so unlike the chat path there is no metered figure to store. Rather than leave
     * embeddings out of the ledger (they are a real and, during a Brain rebuild, large
     * cost) the count is derived from input length and the row is flagged
     * {@code estimated}, so a reconciliation against the vendor invoice can tell the two
     * halves apart.
     *
     * <p>The divisor is deliberately 3, not the widely-quoted 4. This service embeds
     * schema and relationship documents — dense identifiers, underscores, punctuation —
     * which tokenize closer to 2-3 characters per token, a fact {@link #truncate} already
     * documents from the same content. Four would understate the bill on exactly the
     * workload that dominates it.
     *
     * <p>Never throws: accounting must not be able to fail an indexing run.
     */
    private void recordEmbeddingUsage(LlmCredentials credentials, long chars,
                                      long startedAt, boolean succeeded, RuntimeException failure) {
        if (usageRecorder == null) {
            return;
        }
        try {
            long promptTokens = succeeded ? Math.max(0, chars) / ESTIMATED_CHARS_PER_TOKEN : 0;
            String errorCategory = failure == null ? null
                    : String.valueOf(registry.embeddingProvider(credentials.providerId())
                            .classify(failure));

            usageRecorder.record(new LlmUsageRecorder.Call(
                    LlmUsageRole.EMBEDDING,
                    credentials.providerId(),
                    credentials.getOrDefault("model", "unknown"),
                    promptTokens,
                    0,
                    promptTokens,
                    0,
                    true,
                    (System.nanoTime() - startedAt) / 1_000_000L,
                    succeeded,
                    errorCategory));
        } catch (RuntimeException e) {
            log.warn("Could not record embedding usage; the embedding itself was unaffected", e);
        }
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

    /**
     * As above, but with fail-open decided per call site rather than by configuration.
     *
     * <p>{@link #dimensions()} passes {@code false}: degrading a vector <em>width</em> to a
     * fallback is not degradation, it is corruption, and no operator setting should be able
     * to ask for it.
     */
    private <T> T attempt(LlmEmbeddingProvider provider, LlmCredentials credentials,
                          Supplier<T> call, T failOpenValue, boolean mayFailOpen) {
        try {
            return attemptOrThrow(provider, credentials, call);
        } catch (RuntimeException e) {
            return handleFailure(provider.classify(e), credentials, e, failOpenValue, mayFailOpen);
        }
    }

    /**
     * The retry loop with no opinion about failure: exhausted retries rethrow.
     *
     * <p>Separating this from {@link #handleFailure} is what lets {@link #embedWithShrink}
     * treat a CONTEXT_LENGTH rejection as a step in a working algorithm rather than an
     * incident. Routing intermediate attempts through the logging path made every
     * successful shrink emit "Embedding failed" and a stack trace for a call that then
     * succeeded — noise that would fire any alert keyed on that string, and log a terminal
     * failure twice with contradictory failOpen values.
     */
    private <T> T attemptOrThrow(LlmEmbeddingProvider provider, LlmCredentials credentials,
                                 Supplier<T> call) {
        for (int retries = 0; ; retries++) {
            try {
                return call.get();
            } catch (RuntimeException e) {
                LlmErrorCategory category = provider.classify(e);
                if (retries >= MAX_RETRY_ATTEMPTS || !category.isRetryable()
                        || !backoff(retries, category, credentials, retryAfterHint(provider, e))) {
                    throw e;
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
