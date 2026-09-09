package com.dbaagent.config;

import com.dbaagent.llm.LlmConfigResolver;
import com.dbaagent.llm.LlmProviderRegistry;
import com.dbaagent.llm.api.LlmChatProvider;
import com.dbaagent.llm.api.LlmCredentials;
import com.dbaagent.llm.api.LlmErrorCategory;
import com.dbaagent.llm.api.LlmNotConfiguredException;
import com.dbaagent.llm.api.UnsupportedLlmProviderException;
import com.dbaagent.model.LlmUsageRole;
import com.dbaagent.service.llm.LlmUsageRecorder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.metadata.Usage;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * A {@link ChatModel} that resolves its credentials on every call through
 * {@link LlmConfigResolver} and asks {@link LlmProviderRegistry} for the matching
 * provider, so the onboarding wizard can switch provider or rotate keys with no restart.
 *
 * <p>The provider-supplied delegate is cached and rebuilt whenever the resolved
 * credential bundle changes. Thread-safe via a read-write lock.
 *
 * <p>There is deliberately no branching on which provider is active here — the registry
 * owns that dispatch.
 */
@Slf4j
public class RefreshableChatModel implements ChatModel {

    /**
     * A delegate together with the exact bundle it was built from.
     *
     * <p>One field, not two. Held as a pair so a reader cannot observe the pre-rebuild
     * model alongside the post-rebuild key and conclude the cache is warm — that torn
     * read would serve a delegate bound to superseded, possibly revoked credentials,
     * which is the precise failure this class exists to prevent.
     *
     * <p>The key is the whole {@link LlmCredentials} record, compared with its
     * secret-inclusive {@code equals}. {@link LlmCredentials#signature()} is the wrong
     * tool here: it omits secrets (so rotating only {@code api-key} would keep serving a
     * delegate bound to the revoked one) and it omits construction-time settings —
     * {@code temperature}, {@code use-responses-api}, {@code api-version} — that the
     * provider bakes into the model. {@code signature()} stays in use for logging, where
     * excluding secrets is exactly the point.
     */
    private record CachedDelegate(LlmCredentials key, ChatModel model) {}

    private final LlmConfigResolver resolver;
    private final LlmProviderRegistry registry;

    /**
     * Nullable so the accounting dependency stays optional. This class is constructed
     * directly in tests and in {@code LlmConfig}; a hard dependency would make every
     * existing construction site a compile error for a concern none of them care about.
     */
    private final LlmUsageRecorder usageRecorder;

    /** Serialises rebuilds only; the read path is a single lock-free volatile read. */
    private final ReentrantLock buildLock = new ReentrantLock();
    private volatile CachedDelegate cached;

    public RefreshableChatModel(LlmConfigResolver resolver, LlmProviderRegistry registry) {
        this(resolver, registry, null);
    }

    public RefreshableChatModel(LlmConfigResolver resolver, LlmProviderRegistry registry,
                                LlmUsageRecorder usageRecorder) {
        this.resolver = resolver;
        this.registry = registry;
        this.usageRecorder = usageRecorder;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        CachedDelegate active = resolveDelegate();
        try {
            return metered(active, () -> active.model().call(prompt));
        } catch (RuntimeException e) {
            if (shouldRetryWithEnvFallback(e, active.key())) {
                CachedDelegate retry = resolveDelegate();
                return metered(retry, () -> retry.model().call(prompt));
            }
            throw e;
        }
    }

    /**
     * Runs a chat call and records what it cost.
     *
     * <p>A failed call is recorded too. Providers bill for prompt tokens on responses that
     * error partway, and an operator investigating a spend spike caused by a retry loop
     * needs to see the failures — a ledger holding only successes hides exactly the
     * pathology it would be consulted about.
     */
    private ChatResponse metered(CachedDelegate active, Supplier<ChatResponse> call) {
        long startedAt = System.nanoTime();
        try {
            ChatResponse response = call.get();
            recordUsage(active, response, startedAt, null);
            return response;
        } catch (RuntimeException e) {
            recordUsage(active, null, startedAt, e);
            throw e;
        }
    }

    /**
     * Streaming delegates report failures asynchronously through the sink rather than by
     * throwing, so the fallback hangs off the error signal as well as off a synchronous
     * throw. It is not attempted once tokens have reached the subscriber: restarting then
     * would replay text the caller already has, and a stream that got as far as emitting
     * is evidence the credentials were accepted.
     */
    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.defer(() -> {
            CachedDelegate active = resolveDelegate();
            AtomicBoolean emitted = new AtomicBoolean(false);
            try {
                return meteredStream(active, active.model().stream(prompt)
                        .doOnNext(chunk -> emitted.set(true))
                        .onErrorResume(e -> resumeStream(prompt, e, active.key(), emitted.get())));
            } catch (RuntimeException e) {
                return meteredStream(active, resumeStream(prompt, e, active.key(), false));
            }
        });
    }

    /**
     * Records one row for a whole stream, not one per chunk.
     *
     * <p>Usage on a stream arrives on a single late chunk — typically the last, after the
     * provider has finished counting — while every earlier chunk carries either no
     * metadata or a zero-filled {@link Usage}. Recording per chunk would write hundreds of
     * rows for one call and inflate the call count enormously; summing across chunks would
     * be worse, because providers that report cumulative running totals would have their
     * final figure added on top of every partial. So the last non-zero usage seen wins, and
     * exactly one row is written when the stream terminates.
     *
     * <p>{@code doFinally} rather than {@code doOnComplete}: a stream that errors or is
     * cancelled partway still consumed prompt tokens, and a cancelled dashboard build is
     * precisely the kind of silent spend an operator wants on the ledger.
     */
    private Flux<ChatResponse> meteredStream(CachedDelegate active, Flux<ChatResponse> source) {
        if (usageRecorder == null) {
            return source;
        }
        long startedAt = System.nanoTime();
        AtomicReference<ChatResponse> lastWithUsage = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean recorded = new AtomicBoolean(false);

        return source
                .doOnNext(chunk -> {
                    if (hasUsage(chunk)) {
                        lastWithUsage.set(chunk);
                    }
                })
                .doOnError(failure::set)
                .doFinally(signal -> {
                    // doFinally can fire once per subscription; a Flux that is retried or
                    // resubscribed must not double-bill the same logical call.
                    if (recorded.compareAndSet(false, true)) {
                        Throwable error = failure.get();
                        recordUsage(active, lastWithUsage.get(), startedAt,
                                error instanceof RuntimeException re ? re : null);
                    }
                });
    }

    private static boolean hasUsage(ChatResponse chunk) {
        if (chunk == null || chunk.getMetadata() == null) {
            return false;
        }
        Usage usage = chunk.getMetadata().getUsage();
        return usage != null && zeroIfNull(usage.getTotalTokens()) > 0;
    }

    /**
     * Reports the active delegate's options, or neutral ones when nothing is configured.
     *
     * <p>A misconfiguration is not an error here either. Spring AI's
     * {@code ChatClientAutoConfiguration} reads default options while the context is
     * still building, so anything raised from this method aborts context refresh — and an
     * operator who cannot start the application cannot reach the onboarding wizard to fix
     * the very setting that stopped it. Both "nothing configured" and "configured
     * provider is not registered" (a typo in {@code DEEPSQL_CHAT_PROVIDER}) are states an
     * operator recovers from through that wizard, so both must let the context come up.
     *
     * <p>{@link #call} and {@link #stream} still raise, because that is where the problem
     * actually blocks a caller.
     */
    @Override
    public ChatOptions getDefaultOptions() {
        try {
            return resolveDelegate().model().getDefaultOptions();
        } catch (LlmNotConfiguredException e) {
            log.debug("RefreshableChatModel: no chat provider configured yet; "
                    + "reporting neutral default options", e);
            return ChatOptions.builder().build();
        } catch (UnsupportedLlmProviderException e) {
            // Warn, not debug: the message names the supported ids, which is exactly the
            // correction the operator needs, and unlike "not configured" this state is
            // always a mistake rather than a legitimate fresh install.
            log.warn("RefreshableChatModel: reporting neutral default options so the "
                    + "context can start — {}", e.getMessage());
            return ChatOptions.builder().build();
        }
    }

    /**
     * Records one chat call, taking token counts from the response the provider returned.
     *
     * <p>The model name comes from the response metadata when the provider reports it and
     * falls back to the configured model. Those can legitimately differ — an alias that
     * resolves to a dated snapshot, for instance — and the served model is the one that
     * was actually billed, so it wins.
     *
     * <p>Never throws. It runs inside the call path of every chat turn in the product;
     * a defect here must not become a failed conversation.
     */
    private void recordUsage(CachedDelegate active, ChatResponse response,
                             long startedAt, RuntimeException failure) {
        if (usageRecorder == null) {
            return;
        }
        try {
            long latencyMs = (System.nanoTime() - startedAt) / 1_000_000L;
            LlmCredentials key = active.key();

            long prompt = 0;
            long completion = 0;
            long total = 0;
            long cached = 0;
            String model = key.getOrDefault("model", "unknown");

            if (response != null && response.getMetadata() != null) {
                Usage usage = response.getMetadata().getUsage();
                if (usage != null) {
                    prompt = zeroIfNull(usage.getPromptTokens());
                    completion = zeroIfNull(usage.getCompletionTokens());
                    total = zeroIfNull(usage.getTotalTokens());
                    cached = zeroIfNullLong(usage.getCacheReadInputTokens());
                }
                String served = response.getMetadata().getModel();
                if (served != null && !served.isBlank()) {
                    model = served;
                }
            }

            String errorCategory = failure == null ? null
                    : String.valueOf(registry.chatProvider(key.providerId()).classify(failure));

            usageRecorder.record(new LlmUsageRecorder.Call(
                    LlmUsageRole.CHAT,
                    key.providerId(),
                    model,
                    prompt,
                    completion,
                    total,
                    cached,
                    false,
                    latencyMs,
                    failure == null,
                    errorCategory));
        } catch (RuntimeException e) {
            log.warn("RefreshableChatModel: could not record usage; the call was unaffected", e);
        }
    }

    private static long zeroIfNull(Integer value) {
        return value == null ? 0L : value.longValue();
    }

    /** Cache token accessors are {@code Long} in Spring AI 2.0, unlike prompt/completion. */
    private static long zeroIfNullLong(Long value) {
        return value == null ? 0L : value;
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private CachedDelegate resolveDelegate() {
        LlmCredentials credentials = resolver.resolveChat();
        if (credentials == null) {
            throw new LlmNotConfiguredException("chat");
        }

        CachedDelegate current = cached;   // single volatile read — key and model agree
        if (current != null && credentials.equals(current.key())) {
            return current;
        }

        buildLock.lock();
        try {
            current = cached;
            if (current == null || !credentials.equals(current.key())) {
                LlmChatProvider provider = registry.chatProvider(credentials.providerId());
                log.info("RefreshableChatModel: building delegate — {}", credentials.signature());
                current = new CachedDelegate(credentials, provider.delegate(credentials));
                cached = current;
            }
            return current;
        } finally {
            buildLock.unlock();
        }
    }

    private Flux<ChatResponse> resumeStream(
            Prompt prompt, Throwable error, LlmCredentials failedKey, boolean alreadyEmitted) {
        if (alreadyEmitted) {
            log.warn("RefreshableChatModel: stream failed after emitting; not restarting", error);
            return Flux.error(error);
        }
        if (shouldRetryWithEnvFallback(error, failedKey)) {
            return resolveDelegate().model().stream(prompt);
        }
        return Flux.error(error);
    }

    /**
     * Decides whether {@code failure} justifies rebuilding from the environment bundle.
     *
     * <p>A failure inside this handler must never replace the failure it was called to
     * diagnose: it runs from inside a catch block, so anything it throws would surface to
     * the caller with no record of what actually went wrong.
     */
    private boolean shouldRetryWithEnvFallback(Throwable failure, LlmCredentials failedKey) {
        try {
            return evaluateEnvFallback(failure, failedKey);
        } catch (RuntimeException handlerFailure) {
            log.error("RefreshableChatModel: could not evaluate environment fallback for {}; "
                    + "propagating the original failure instead",
                    failedKey.signature(), handlerFailure);
            return false;
        }
    }

    /**
     * Classifies through the provider that built the failing delegate — never by matching
     * message substrings such as {@code "Azure OpenAI error (401)"}, which do not survive
     * a second provider.
     *
     * <p>Keyed off {@code failedKey}, the bundle the delegate was actually built from,
     * rather than re-resolving. Re-resolving would blame whatever the wizard happened to
     * write in the meantime — a bundle that never failed — and costs a second lookup.
     */
    private boolean evaluateEnvFallback(Throwable failure, LlmCredentials failedKey) {
        LlmErrorCategory category =
                registry.chatProvider(failedKey.providerId()).classify(failure);
        if (!category.justifiesEnvFallback()) {
            return false;
        }
        boolean switched = resolver.markChatConfigInvalid(failedKey);
        if (switched) {
            log.warn("RefreshableChatModel: {} for {}; falling back to environment config",
                    category, failedKey.signature(), failure);
        }
        return switched;
    }
}
