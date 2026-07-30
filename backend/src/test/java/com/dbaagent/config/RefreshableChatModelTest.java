package com.dbaagent.config;

import com.dbaagent.llm.LlmConfigResolver;
import com.dbaagent.llm.LlmProviderRegistry;
import com.dbaagent.llm.api.LlmChatProvider;
import com.dbaagent.llm.api.LlmCredentials;
import com.dbaagent.llm.api.LlmErrorCategory;
import com.dbaagent.llm.api.LlmNotConfiguredException;
import com.dbaagent.llm.api.UnsupportedLlmProviderException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Behaviour of the provider-agnostic {@link RefreshableChatModel}.
 *
 * <p>Every assertion here is about observable behaviour — which delegate got built, which
 * one served a call, whether a failure was retried — rather than about internal fields.
 */
class RefreshableChatModelTest {

    private static final Map<String, String> BASE =
            Map.of("endpoint", "https://x.invalid/", "model", "m");

    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    // ── Delegate caching ──────────────────────────────────────────────────────

    @Test
    void rebuildsTheDelegateWhenOnlyTheProviderChanges() {
        var resolver = mock(LlmConfigResolver.class);
        var registry = mock(LlmProviderRegistry.class);
        var providerA = mock(LlmChatProvider.class);
        var providerB = mock(LlmChatProvider.class);

        when(registry.chatProvider("openai")).thenReturn(providerA);
        when(registry.chatProvider("anthropic")).thenReturn(providerB);
        when(providerA.delegate(any())).thenReturn(mock(ChatModel.class));
        when(providerB.delegate(any())).thenReturn(mock(ChatModel.class));

        // Same endpoint and model; only the provider differs. A cache key that omitted
        // the provider would have served a stale delegate here.
        var credsA = new LlmCredentials("openai", BASE);
        var credsB = new LlmCredentials("anthropic", BASE);
        when(resolver.resolveChat()).thenReturn(credsA, credsA, credsB);

        var refreshable = new RefreshableChatModel(resolver, registry);
        var prompt = new Prompt("hi");
        refreshable.call(prompt);
        refreshable.call(prompt);
        refreshable.call(prompt);

        verify(providerA, times(1)).delegate(any());  // cached across the first two calls
        verify(providerB, times(1)).delegate(any());  // rebuilt when the provider changed
    }

    @Test
    void reusesTheCachedDelegateWhenNothingChanged() {
        var resolver = mock(LlmConfigResolver.class);
        var registry = mock(LlmProviderRegistry.class);
        var provider = mock(LlmChatProvider.class);

        when(registry.chatProvider("openai")).thenReturn(provider);
        when(provider.delegate(any())).thenReturn(mock(ChatModel.class));
        when(resolver.resolveChat()).thenReturn(new LlmCredentials("openai", BASE));

        var refreshable = new RefreshableChatModel(resolver, registry);
        refreshable.call(new Prompt("a"));
        refreshable.call(new Prompt("b"));

        verify(provider, times(1)).delegate(any());
    }

    @Test
    void rebuildsTheDelegateWhenOnlyTheSecretRotates() {
        var resolver = mock(LlmConfigResolver.class);
        var registry = mock(LlmProviderRegistry.class);
        var provider = mock(LlmChatProvider.class);

        when(registry.chatProvider("openai")).thenReturn(provider);
        when(provider.delegate(any())).thenReturn(mock(ChatModel.class));

        // signature() excludes secrets by design, so a signature-only cache key would
        // have kept serving the delegate bound to the revoked key.
        var before = new LlmCredentials("openai",
                Map.of("endpoint", "https://x.invalid/", "model", "m", "api-key", "old"));
        var after = new LlmCredentials("openai",
                Map.of("endpoint", "https://x.invalid/", "model", "m", "api-key", "new"));
        when(resolver.resolveChat()).thenReturn(before, after);

        var refreshable = new RefreshableChatModel(resolver, registry);
        refreshable.call(new Prompt("a"));
        refreshable.call(new Prompt("b"));

        verify(provider, times(2)).delegate(any());
    }

    @Test
    void rebuildsTheDelegateWhenANonSignatureTuningFieldChanges() {
        var resolver = mock(LlmConfigResolver.class);
        var registry = mock(LlmProviderRegistry.class);
        var provider = mock(LlmChatProvider.class);

        when(registry.chatProvider("openai")).thenReturn(provider);
        when(provider.delegate(any())).thenReturn(mock(ChatModel.class));

        // temperature is baked into the delegate at construction but is absent from
        // signature(), so the cache key has to be the whole credential bundle.
        var cool = new LlmCredentials("openai",
                Map.of("endpoint", "https://x.invalid/", "model", "m", "temperature", "0.0"));
        var warm = new LlmCredentials("openai",
                Map.of("endpoint", "https://x.invalid/", "model", "m", "temperature", "1.0"));
        when(resolver.resolveChat()).thenReturn(cool, warm);

        var refreshable = new RefreshableChatModel(resolver, registry);
        refreshable.call(new Prompt("a"));
        refreshable.call(new Prompt("b"));

        verify(provider, times(2)).delegate(any());
    }

    @Test
    void routesCallsThroughTheResolvedDelegate() {
        var resolver = mock(LlmConfigResolver.class);
        var registry = mock(LlmProviderRegistry.class);
        var provider = mock(LlmChatProvider.class);
        var delegate = mock(ChatModel.class);
        var expected = response("ok");
        var options = mock(ChatOptions.class);

        when(registry.chatProvider("openai")).thenReturn(provider);
        when(provider.delegate(any())).thenReturn(delegate);
        when(delegate.call(any(Prompt.class))).thenReturn(expected);
        when(delegate.getDefaultOptions()).thenReturn(options);
        when(resolver.resolveChat()).thenReturn(new LlmCredentials("openai", BASE));

        var refreshable = new RefreshableChatModel(resolver, registry);

        assertThat(refreshable.call(new Prompt("hi"))).isSameAs(expected);
        assertThat(refreshable.getDefaultOptions()).isSameAs(options);
    }

    // ── Not configured ────────────────────────────────────────────────────────

    @Test
    void surfacesAFriendlyErrorWhenNoProviderIsConfigured() {
        var resolver = mock(LlmConfigResolver.class);
        var registry = mock(LlmProviderRegistry.class);
        when(resolver.resolveChat()).thenReturn(null);

        var refreshable = new RefreshableChatModel(resolver, registry);

        assertThatThrownBy(() -> refreshable.call(new Prompt("hi")))
                .isInstanceOf(LlmNotConfiguredException.class)
                .hasMessageContaining("/onboarding");
    }

    @Test
    void reportsNeutralDefaultOptionsWhenNoProviderIsConfigured() {
        var resolver = mock(LlmConfigResolver.class);
        var registry = mock(LlmProviderRegistry.class);
        when(resolver.resolveChat()).thenReturn(null);

        var refreshable = new RefreshableChatModel(resolver, registry);

        // Spring AI's ChatClient auto-configuration reads default options while the
        // context is still building. A fresh self-host has no LLM configured yet and must
        // still boot far enough to serve the onboarding wizard it is being pointed at, so
        // this call reports "unset" instead of raising.
        assertThat(refreshable.getDefaultOptions()).isNotNull();
        assertThat(refreshable.getDefaultOptions().getModel()).isNull();
    }

    @Test
    void reportsNeutralDefaultOptionsWhenTheConfiguredProviderIsNotRegistered() {
        var resolver = mock(LlmConfigResolver.class);
        var registry = mock(LlmProviderRegistry.class);

        // An operator typo — DEEPSQL_CHAT_PROVIDER=anthropic plus one other DEEPSQL_CHAT_*
        // var — yields a resolvable bundle naming an id no provider claims. If that
        // escaped getDefaultOptions(), context refresh would fail inside
        // ChatClient.builder(...) and the operator could never reach /onboarding to
        // correct it: a restart loop with no way out.
        var creds = new LlmCredentials("anthropic", BASE);
        when(resolver.resolveChat()).thenReturn(creds);
        when(registry.chatProvider("anthropic"))
                .thenThrow(new UnsupportedLlmProviderException("anthropic", Set.of("openai")));

        var refreshable = new RefreshableChatModel(resolver, registry);

        assertThat(refreshable.getDefaultOptions()).isNotNull();

        // The loud failure stays loud where it actually blocks a caller.
        assertThatThrownBy(() -> refreshable.call(new Prompt("hi")))
                .isInstanceOf(UnsupportedLlmProviderException.class)
                .hasMessageContaining("openai");
    }

    @Test
    void surfacesTheSameFriendlyErrorOnTheStreamingPath() {
        var resolver = mock(LlmConfigResolver.class);
        var registry = mock(LlmProviderRegistry.class);
        when(resolver.resolveChat()).thenReturn(null);

        var refreshable = new RefreshableChatModel(resolver, registry);

        assertThatThrownBy(() -> refreshable.stream(new Prompt("hi")).collectList().block())
                .isInstanceOf(LlmNotConfiguredException.class)
                .hasMessageContaining("/onboarding");
    }

    // ── Environment fallback, keyed off LlmErrorCategory ──────────────────────

    @Test
    void fallsBackToTheEnvironmentBundleWhenTheCategoryJustifiesIt() {
        var resolver = mock(LlmConfigResolver.class);
        var registry = mock(LlmProviderRegistry.class);
        var provider = mock(LlmChatProvider.class);
        var broken = mock(ChatModel.class);
        var healthy = mock(ChatModel.class);
        var expected = response("recovered");

        var dbCreds = new LlmCredentials("openai",
                Map.of("endpoint", "https://db.invalid/", "model", "m", "api-key", "bad"));
        var envCreds = new LlmCredentials("openai",
                Map.of("endpoint", "https://env.invalid/", "model", "m", "api-key", "good"));

        var failure = new RuntimeException("rejected");
        when(registry.chatProvider("openai")).thenReturn(provider);
        when(provider.delegate(dbCreds)).thenReturn(broken);
        when(provider.delegate(envCreds)).thenReturn(healthy);
        when(provider.classify(failure)).thenReturn(LlmErrorCategory.AUTH);
        when(broken.call(any(Prompt.class))).thenThrow(failure);
        when(healthy.call(any(Prompt.class))).thenReturn(expected);
        when(resolver.markChatConfigInvalid(dbCreds)).thenReturn(true);
        // One resolve per delegate build. Classification reuses the bundle the failing
        // delegate was built from rather than resolving again.
        when(resolver.resolveChat()).thenReturn(dbCreds, envCreds);

        var refreshable = new RefreshableChatModel(resolver, registry);

        assertThat(refreshable.call(new Prompt("hi"))).isSameAs(expected);
        verify(resolver).markChatConfigInvalid(dbCreds);
    }

    @Test
    void doesNotFallBackWhenTheCategoryDoesNotJustifyIt() {
        var resolver = mock(LlmConfigResolver.class);
        var registry = mock(LlmProviderRegistry.class);
        var provider = mock(LlmChatProvider.class);
        var delegate = mock(ChatModel.class);

        var creds = new LlmCredentials("openai", BASE);
        var failure = new RuntimeException("prompt too long");
        when(registry.chatProvider("openai")).thenReturn(provider);
        when(provider.delegate(any())).thenReturn(delegate);
        when(provider.classify(failure)).thenReturn(LlmErrorCategory.CONTEXT_LENGTH);
        when(delegate.call(any(Prompt.class))).thenThrow(failure);
        when(resolver.resolveChat()).thenReturn(creds);

        var refreshable = new RefreshableChatModel(resolver, registry);

        assertThatThrownBy(() -> refreshable.call(new Prompt("hi"))).isSameAs(failure);
        verify(resolver, never()).markChatConfigInvalid(any());
        verify(delegate, times(1)).call(any(Prompt.class));
    }

    @Test
    void attributesTheFailureToTheBundleTheDelegateWasBuiltFrom() {
        var resolver = mock(LlmConfigResolver.class);
        var registry = mock(LlmProviderRegistry.class);
        var provider = mock(LlmChatProvider.class);
        var broken = mock(ChatModel.class);
        var healthy = mock(ChatModel.class);
        var expected = response("recovered");

        var failing = new LlmCredentials("openai",
                Map.of("endpoint", "https://db.invalid/", "model", "m", "api-key", "bad"));
        var rewritten = new LlmCredentials("openai",
                Map.of("endpoint", "https://new.invalid/", "model", "m", "api-key", "fresh"));

        var failure = new RuntimeException("rejected");
        when(registry.chatProvider("openai")).thenReturn(provider);
        when(provider.delegate(failing)).thenReturn(broken);
        when(provider.delegate(rewritten)).thenReturn(healthy);
        when(provider.classify(failure)).thenReturn(LlmErrorCategory.AUTH);
        when(broken.call(any(Prompt.class))).thenThrow(failure);
        when(healthy.call(any(Prompt.class))).thenReturn(expected);
        when(resolver.markChatConfigInvalid(failing)).thenReturn(true);

        // The wizard rewrites credentials between the failed call and the classification.
        // Re-resolving here would blame a bundle that never failed.
        when(resolver.resolveChat()).thenReturn(failing, rewritten);

        var refreshable = new RefreshableChatModel(resolver, registry);

        assertThat(refreshable.call(new Prompt("hi"))).isSameAs(expected);
        verify(resolver).markChatConfigInvalid(failing);
        verify(resolver, never()).markChatConfigInvalid(rewritten);
        verify(resolver, times(2)).resolveChat();   // one per delegate, none for classifying
    }

    @Test
    void keepsTheOriginalFailureWhenTheFallbackHandlerItselfFails() {
        var resolver = mock(LlmConfigResolver.class);
        var registry = mock(LlmProviderRegistry.class);
        var provider = mock(LlmChatProvider.class);
        var delegate = mock(ChatModel.class);

        var creds = new LlmCredentials("openai", BASE);
        var failure = new RuntimeException("the real problem");

        // The recovery path does its own registry lookup, which can throw. If it did so
        // from inside the catch block it would replace the diagnosis with its own noise.
        when(registry.chatProvider("openai"))
                .thenReturn(provider)
                .thenThrow(new UnsupportedLlmProviderException("openai", Set.of()));
        when(provider.delegate(any())).thenReturn(delegate);
        when(delegate.call(any(Prompt.class))).thenThrow(failure);
        when(resolver.resolveChat()).thenReturn(creds);

        var refreshable = new RefreshableChatModel(resolver, registry);

        assertThatThrownBy(() -> refreshable.call(new Prompt("hi"))).isSameAs(failure);
    }

    @Test
    void doesNotRetryWhenNoAlternativeBundleExists() {
        var resolver = mock(LlmConfigResolver.class);
        var registry = mock(LlmProviderRegistry.class);
        var provider = mock(LlmChatProvider.class);
        var delegate = mock(ChatModel.class);

        var creds = new LlmCredentials("openai", BASE);
        var failure = new RuntimeException("rejected");
        when(registry.chatProvider("openai")).thenReturn(provider);
        when(provider.delegate(any())).thenReturn(delegate);
        when(provider.classify(failure)).thenReturn(LlmErrorCategory.AUTH);
        when(delegate.call(any(Prompt.class))).thenThrow(failure);
        when(resolver.resolveChat()).thenReturn(creds);
        when(resolver.markChatConfigInvalid(creds)).thenReturn(false);

        var refreshable = new RefreshableChatModel(resolver, registry);

        assertThatThrownBy(() -> refreshable.call(new Prompt("hi"))).isSameAs(failure);
        verify(delegate, times(1)).call(any(Prompt.class));
    }

    // ── Streaming ─────────────────────────────────────────────────────────────

    @Test
    void fallsBackWhenAStreamFailsAsynchronouslyBeforeEmittingAnything() {
        var resolver = mock(LlmConfigResolver.class);
        var registry = mock(LlmProviderRegistry.class);
        var provider = mock(LlmChatProvider.class);
        var broken = mock(ChatModel.class);
        var healthy = mock(ChatModel.class);
        var expected = response("recovered");

        var dbCreds = new LlmCredentials("openai",
                Map.of("endpoint", "https://db.invalid/", "model", "m", "api-key", "bad"));
        var envCreds = new LlmCredentials("openai",
                Map.of("endpoint", "https://env.invalid/", "model", "m", "api-key", "good"));

        // ResponsesApiChatModel reports streaming failures through sink.error, never by
        // throwing synchronously — so the fallback has to hang off the error signal.
        var failure = new RuntimeException("Streaming failed");
        when(registry.chatProvider("openai")).thenReturn(provider);
        when(provider.delegate(dbCreds)).thenReturn(broken);
        when(provider.delegate(envCreds)).thenReturn(healthy);
        when(provider.classify(failure)).thenReturn(LlmErrorCategory.AUTH);
        when(broken.stream(any(Prompt.class))).thenReturn(Flux.error(failure));
        when(healthy.stream(any(Prompt.class))).thenReturn(Flux.just(expected));
        when(resolver.markChatConfigInvalid(dbCreds)).thenReturn(true);
        when(resolver.resolveChat()).thenReturn(dbCreds, envCreds);

        var refreshable = new RefreshableChatModel(resolver, registry);

        assertThat(refreshable.stream(new Prompt("hi")).collectList().block())
                .containsExactly(expected);
        verify(resolver).markChatConfigInvalid(dbCreds);
    }

    /**
     * A transient failure entitles you to a retry, never to a configuration change.
     * Invalidation is sticky, so one 503 used to move an install off its operator-chosen
     * provider permanently. Recovery from a 5xx belongs in the provider's retry loop
     * (see {@code ResponsesApiChatModel.isRetryableStatus}), not here.
     */
    @Test
    void aTransientFailureDoesNotSwitchTheInstallOntoTheEnvironmentBundle() {
        var resolver = mock(LlmConfigResolver.class);
        var registry = mock(LlmProviderRegistry.class);
        var provider = mock(LlmChatProvider.class);
        var delegate = mock(ChatModel.class);

        var creds = new LlmCredentials("openai", BASE);
        var failure = new RuntimeException("Chat provider error (503): upstream busy");
        when(registry.chatProvider("openai")).thenReturn(provider);
        when(provider.delegate(any())).thenReturn(delegate);
        when(provider.classify(failure)).thenReturn(LlmErrorCategory.TRANSIENT);
        when(delegate.call(any(Prompt.class))).thenThrow(failure);
        when(resolver.resolveChat()).thenReturn(creds);

        var refreshable = new RefreshableChatModel(resolver, registry);

        assertThatThrownBy(() -> refreshable.call(new Prompt("hi"))).isSameAs(failure);
        verify(resolver, never()).markChatConfigInvalid(any());
    }

    @Test
    void doesNotRestartAStreamThatAlreadyEmittedTokens() {
        var resolver = mock(LlmConfigResolver.class);
        var registry = mock(LlmProviderRegistry.class);
        var provider = mock(LlmChatProvider.class);
        var delegate = mock(ChatModel.class);
        var firstChunk = response("par");

        var creds = new LlmCredentials("openai", BASE);
        var failure = new RuntimeException("Streaming failed");
        when(registry.chatProvider("openai")).thenReturn(provider);
        when(provider.delegate(any())).thenReturn(delegate);
        when(provider.classify(failure)).thenReturn(LlmErrorCategory.TRANSIENT);
        when(delegate.stream(any(Prompt.class)))
                .thenReturn(Flux.concat(Flux.just(firstChunk), Flux.error(failure)));
        when(resolver.resolveChat()).thenReturn(creds);

        var refreshable = new RefreshableChatModel(resolver, registry);

        // Restarting mid-stream would replay tokens the caller already received, and a
        // stream that got as far as emitting is evidence the credentials were accepted.
        assertThatThrownBy(() -> refreshable.stream(new Prompt("hi")).collectList().block())
                .isSameAs(failure);
        verify(resolver, never()).markChatConfigInvalid(any());
        verify(delegate, times(1)).stream(any(Prompt.class));
    }

    @Test
    void propagatesStreamFailuresThatDoNotJustifyFallback() {
        var resolver = mock(LlmConfigResolver.class);
        var registry = mock(LlmProviderRegistry.class);
        var provider = mock(LlmChatProvider.class);
        var delegate = mock(ChatModel.class);

        var creds = new LlmCredentials("openai", BASE);
        var failure = new RuntimeException("prompt too long");
        when(registry.chatProvider("openai")).thenReturn(provider);
        when(provider.delegate(any())).thenReturn(delegate);
        when(provider.classify(failure)).thenReturn(LlmErrorCategory.CONTEXT_LENGTH);
        when(delegate.stream(any(Prompt.class))).thenReturn(Flux.error(failure));
        when(resolver.resolveChat()).thenReturn(creds);

        var refreshable = new RefreshableChatModel(resolver, registry);

        assertThatThrownBy(() -> refreshable.stream(new Prompt("hi")).collectList().block())
                .isSameAs(failure);
        verify(resolver, never()).markChatConfigInvalid(any());
    }
}
