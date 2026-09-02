package com.dbaagent.config;

import com.dbaagent.llm.LlmConfigResolver;
import com.dbaagent.llm.LlmProviderRegistry;
import com.dbaagent.llm.api.LlmChatProvider;
import com.dbaagent.llm.api.LlmCredentials;
import com.dbaagent.llm.api.LlmErrorCategory;
import com.dbaagent.model.LlmUsageRole;
import com.dbaagent.service.llm.LlmUsageRecorder;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Token accounting on the chat funnel.
 *
 * <p>The streaming cases are the point of this file. Usage on a stream arrives on one late
 * chunk while every earlier chunk carries none, so the two ways to get it wrong — a row
 * per chunk, or summing partials into a running total — are both easy to write and
 * invisible in production until the invoice disagrees.
 */
class RefreshableChatModelUsageTest {

    private static final Map<String, String> BASE =
            Map.of("endpoint", "https://x.invalid/", "model", "gpt-4o");

    private final LlmConfigResolver resolver = mock(LlmConfigResolver.class);
    private final LlmProviderRegistry registry = mock(LlmProviderRegistry.class);
    private final LlmChatProvider provider = mock(LlmChatProvider.class);
    private final ChatModel delegate = mock(ChatModel.class);
    private final LlmUsageRecorder recorder = mock(LlmUsageRecorder.class);

    private RefreshableChatModel model() {
        when(resolver.resolveChat()).thenReturn(new LlmCredentials("openai", BASE));
        when(registry.chatProvider("openai")).thenReturn(provider);
        when(provider.delegate(any())).thenReturn(delegate);
        return new RefreshableChatModel(resolver, registry, recorder);
    }

    private static ChatResponse withUsage(String text, int prompt, int completion) {
        return new ChatResponse(
                List.of(new Generation(new AssistantMessage(text))),
                ChatResponseMetadata.builder()
                        .usage(new DefaultUsage(prompt, completion))
                        .model("gpt-4o-2024-11-20")
                        .build());
    }

    private static ChatResponse noUsage(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private LlmUsageRecorder.Call captureOne() {
        ArgumentCaptor<LlmUsageRecorder.Call> captor =
                ArgumentCaptor.forClass(LlmUsageRecorder.Call.class);
        verify(recorder).record(captor.capture());
        return captor.getValue();
    }

    // ── Non-streaming ─────────────────────────────────────────────────────────

    @Test
    void recordsMeteredTokensFromTheResponse() {
        when(delegate.call(any(Prompt.class))).thenReturn(withUsage("hi", 1_200, 300));

        model().call(new Prompt("hi"));

        LlmUsageRecorder.Call call = captureOne();
        assertThat(call.role()).isEqualTo(LlmUsageRole.CHAT);
        assertThat(call.promptTokens()).isEqualTo(1_200);
        assertThat(call.completionTokens()).isEqualTo(300);
        assertThat(call.succeeded()).isTrue();
        // Chat counts come from the provider, so they are never flagged as estimates.
        assertThat(call.estimated()).isFalse();
    }

    /**
     * The served model wins over the configured one: an alias resolves to a dated
     * snapshot, and the snapshot is what was billed.
     */
    @Test
    void prefersTheServedModelOverTheConfiguredAlias() {
        when(delegate.call(any(Prompt.class))).thenReturn(withUsage("hi", 10, 10));

        model().call(new Prompt("hi"));

        assertThat(captureOne().model()).isEqualTo("gpt-4o-2024-11-20");
    }

    @Test
    void fallsBackToTheConfiguredModelWhenTheResponseNamesNone() {
        when(delegate.call(any(Prompt.class))).thenReturn(noUsage("hi"));

        model().call(new Prompt("hi"));

        assertThat(captureOne().model()).isEqualTo("gpt-4o");
    }

    @Test
    void recordsAFailedCallSoRetryLoopsStayVisible() {
        when(delegate.call(any(Prompt.class))).thenThrow(new RuntimeException("429 rate limited"));
        when(provider.classify(any())).thenReturn(LlmErrorCategory.RATE_LIMIT);

        assertThatThrownBy(() -> model().call(new Prompt("hi")))
                .isInstanceOf(RuntimeException.class);

        LlmUsageRecorder.Call call = captureOne();
        assertThat(call.succeeded()).isFalse();
        assertThat(call.errorCategory()).isEqualTo("RATE_LIMIT");
    }

    /** Accounting must never convert a working call into a failed one. */
    @Test
    void aRecorderFailureDoesNotBreakTheCall() {
        when(delegate.call(any(Prompt.class))).thenReturn(withUsage("hi", 10, 10));
        org.mockito.Mockito.doThrow(new RuntimeException("ledger down"))
                .when(recorder).record(any());

        assertThat(model().call(new Prompt("hi"))).isNotNull();
    }

    @Test
    void worksWithNoRecorderAtAll() {
        when(resolver.resolveChat()).thenReturn(new LlmCredentials("openai", BASE));
        when(registry.chatProvider("openai")).thenReturn(provider);
        when(provider.delegate(any())).thenReturn(delegate);
        when(delegate.call(any(Prompt.class))).thenReturn(noUsage("hi"));

        assertThat(new RefreshableChatModel(resolver, registry).call(new Prompt("hi")))
                .isNotNull();
    }

    // ── Streaming ─────────────────────────────────────────────────────────────

    /**
     * One row per stream, not one per chunk. A 200-chunk answer recorded per chunk would
     * report 200 calls and, if partials were summed, a wildly inflated token total.
     */
    @Test
    void recordsExactlyOneRowForAWholeStream() {
        when(delegate.stream(any(Prompt.class))).thenReturn(Flux.just(
                noUsage("Hel"), noUsage("lo"), noUsage(" wor"),
                withUsage("ld", 900, 120)));

        assertThat(model().stream(new Prompt("hi")).collectList().block()).hasSize(4);

        LlmUsageRecorder.Call call = captureOne();
        assertThat(call.promptTokens()).isEqualTo(900);
        assertThat(call.completionTokens()).isEqualTo(120);
    }

    /**
     * Providers that report a cumulative running total must not have their partials added
     * to the final figure.
     */
    @Test
    void takesTheLastReportedUsageRatherThanSummingChunks() {
        when(delegate.stream(any(Prompt.class))).thenReturn(Flux.just(
                withUsage("a", 900, 10),
                withUsage("b", 900, 60),
                withUsage("c", 900, 120)));

        assertThat(model().stream(new Prompt("hi")).collectList().block()).hasSize(3);

        LlmUsageRecorder.Call call = captureOne();
        assertThat(call.promptTokens()).isEqualTo(900);
        assertThat(call.completionTokens()).isEqualTo(120);
        verify(recorder, times(1)).record(any());
    }

    /** A stream that dies partway still consumed prompt tokens. */
    @Test
    void recordsAStreamThatFailsAfterEmitting() {
        when(delegate.stream(any(Prompt.class))).thenReturn(Flux.concat(
                Flux.just(withUsage("partial", 500, 5)),
                Flux.error(new RuntimeException("connection reset"))));
        when(provider.classify(any())).thenReturn(LlmErrorCategory.TRANSIENT);

        assertThatThrownBy(() -> model().stream(new Prompt("hi")).collectList().block())
                .isInstanceOf(RuntimeException.class);

        LlmUsageRecorder.Call call = captureOne();
        assertThat(call.promptTokens()).isEqualTo(500);
        assertThat(call.succeeded()).isFalse();
    }

    /** A cancelled dashboard build is exactly the silent spend an operator wants logged. */
    @Test
    void recordsACancelledStream() {
        when(delegate.stream(any(Prompt.class))).thenReturn(Flux.just(
                withUsage("a", 400, 10), noUsage("b"), noUsage("c")));

        // take(1) cancels the source after the first element, which is what a user
        // navigating away mid-answer does to the stream.
        assertThat(model().stream(new Prompt("hi")).take(1).collectList().block()).hasSize(1);

        assertThat(captureOne().promptTokens()).isEqualTo(400);
    }

    @Test
    void recordsAStreamThatReportsNoUsageAtAll() {
        when(delegate.stream(any(Prompt.class))).thenReturn(Flux.just(noUsage("a"), noUsage("b")));

        assertThat(model().stream(new Prompt("hi")).collectList().block()).hasSize(2);

        // Still one row: the call happened and is worth counting, even unmetered.
        LlmUsageRecorder.Call call = captureOne();
        assertThat(call.promptTokens()).isZero();
        assertThat(call.succeeded()).isTrue();
    }
}
