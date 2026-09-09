package com.dbaagent.service.llm;

import java.util.function.Supplier;

/**
 * Which feature is currently calling the model, and on whose connection.
 *
 * <p>The recording funnels sit at the provider boundary, where the caller is no longer
 * visible — {@code RefreshableChatModel} sees a {@code Prompt} and nothing else. Rather
 * than thread a parameter through every intermediate signature (Spring AI's
 * {@code ChatModel} contract has no room for one anyway), features declare themselves
 * around the call, exactly as {@code QueryActorContextHolder} already does for the SQL
 * actor.
 *
 * <p>Attribution is best-effort by construction: an un-annotated caller records
 * {@code unknown} rather than failing. Accounting must never be able to break the feature
 * it is measuring.
 */
public final class LlmUsageContext {

    public static final String UNKNOWN_FEATURE = "unknown";

    public record Scope(String feature, String connectionId) {}

    /**
     * Inheritable, and that is not a detail. Chat fans its work out across
     * {@code CompletableFuture.supplyAsync} and reactive schedulers, so a plain
     * {@link ThreadLocal} is invisible by the time the model is actually called — verified
     * live, not reasoned about: every row landed with {@code feature = 'unknown'} while a
     * single-threaded unit test of the same filter passed. An
     * {@link InheritableThreadLocal} is copied into threads created from the request
     * thread, which is what the async fan-out does.
     *
     * <p>It is still not total. A thread taken from a pool that was created *before* this
     * scope was set inherits nothing, so some background work will read {@code unknown} —
     * acceptable for attribution metadata, which is why {@link #currentFeature} degrades
     * instead of failing. Anything that must be labelled exactly declares its own scope,
     * as {@code DashboardAlertService} does.
     */
    private static final ThreadLocal<Scope> CURRENT = new InheritableThreadLocal<>();

    private LlmUsageContext() {
    }

    public static Scope current() {
        return CURRENT.get();
    }

    public static String currentFeature() {
        Scope scope = CURRENT.get();
        return scope == null || scope.feature() == null ? UNKNOWN_FEATURE : scope.feature();
    }

    public static String currentConnectionId() {
        Scope scope = CURRENT.get();
        return scope == null ? null : scope.connectionId();
    }

    public static <T> T with(String feature, String connectionId, Supplier<T> supplier) {
        Scope previous = CURRENT.get();
        try {
            CURRENT.set(new Scope(feature, connectionId));
            return supplier.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    public static void with(String feature, String connectionId, Runnable runnable) {
        with(feature, connectionId, () -> {
            runnable.run();
            return null;
        });
    }
}
