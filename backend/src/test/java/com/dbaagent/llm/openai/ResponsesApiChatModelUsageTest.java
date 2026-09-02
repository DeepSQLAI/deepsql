package com.dbaagent.llm.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Token counts parsed off a provider response.
 *
 * <p>{@code buildMetadata} previously discarded the {@code usage} block entirely. That was
 * invisible while nothing read it and became a silent zero the moment usage accounting
 * started summing it: real, billed calls recorded 0 tokens and $0.00. These cases pin both
 * vendor dialects so the next refactor cannot quietly restore the stub.
 */
class ResponsesApiChatModelUsageTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ResponsesApiChatModel model(boolean useResponsesApi) {
        return new ResponsesApiChatModel("https://api.openai.com/v1", "test-key", "gpt-4o",
                "2025-03-01-preview", 1.0, useResponsesApi);
    }

    private static ChatResponseMetadata metadataFrom(String usageJson) throws Exception {
        return model(false).buildMetadata(MAPPER.readTree(usageJson));
    }

    @Test
    void readsChatCompletionsTokenNames() throws Exception {
        ChatResponseMetadata metadata = metadataFrom("""
                {"prompt_tokens": 1200, "completion_tokens": 300, "total_tokens": 1500}
                """);

        assertThat(metadata.getUsage().getPromptTokens()).isEqualTo(1200);
        assertThat(metadata.getUsage().getCompletionTokens()).isEqualTo(300);
        assertThat(metadata.getUsage().getTotalTokens()).isEqualTo(1500);
    }

    /** The Responses API names the same numbers differently; both must work. */
    @Test
    void readsResponsesApiTokenNames() throws Exception {
        ChatResponseMetadata metadata = metadataFrom("""
                {"input_tokens": 800, "output_tokens": 120, "total_tokens": 920}
                """);

        assertThat(metadata.getUsage().getPromptTokens()).isEqualTo(800);
        assertThat(metadata.getUsage().getCompletionTokens()).isEqualTo(120);
        assertThat(metadata.getUsage().getTotalTokens()).isEqualTo(920);
    }

    @Test
    void derivesTotalWhenTheProviderOmitsIt() throws Exception {
        ChatResponseMetadata metadata = metadataFrom("""
                {"prompt_tokens": 40, "completion_tokens": 60}
                """);

        assertThat(metadata.getUsage().getTotalTokens()).isEqualTo(100);
    }

    /** Cached input bills below fresh input, so it has to survive parsing. */
    @Test
    void readsCachedPromptTokensFromEitherParent() throws Exception {
        assertThat(metadataFrom("""
                {"prompt_tokens": 1000, "completion_tokens": 0,
                 "prompt_tokens_details": {"cached_tokens": 600}}
                """).getUsage().getCacheReadInputTokens()).isEqualTo(600L);

        assertThat(metadataFrom("""
                {"input_tokens": 1000, "output_tokens": 0,
                 "input_tokens_details": {"cached_tokens": 250}}
                """).getUsage().getCacheReadInputTokens()).isEqualTo(250L);
    }

    @Test
    void missingUsageBlockYieldsEmptyMetadataRatherThanThrowing() throws Exception {
        assertThat(model(false).buildMetadata(MAPPER.readTree("{}").path("usage")).getUsage())
                .satisfiesAnyOf(
                        usage -> assertThat(usage).isNull(),
                        usage -> assertThat(usage.getTotalTokens()).isEqualTo(0));
    }

    // ── Streaming ─────────────────────────────────────────────────────────────

    /**
     * Chat Completions omits usage from a stream unless asked. Without this flag a
     * streamed turn is unbillable — the tokens are spent and nothing reports them.
     */
    @Test
    void streamingRequestsUsageOnChatCompletions() {
        String body = model(false).buildRequestBody(new Prompt("hi"), true);

        assertThat(body).contains("\"stream_options\"").contains("\"include_usage\":true");
    }

    /** A non-streaming call reports usage anyway, so the flag would be noise. */
    @Test
    void nonStreamingRequestDoesNotAskForStreamUsage() {
        assertThat(model(false).buildRequestBody(new Prompt("hi"), false))
                .doesNotContain("stream_options");
    }

    @Test
    void extractsUsageFromAFinalChatCompletionsChunk() {
        ChatResponseMetadata metadata = model(false).extractStreamUsage("""
                {"choices": [], "usage": {"prompt_tokens": 900, "completion_tokens": 120,
                 "total_tokens": 1020}}
                """);

        assertThat(metadata).isNotNull();
        assertThat(metadata.getUsage().getTotalTokens()).isEqualTo(1020);
    }

    /** The Responses API nests it under the completion event's response object. */
    @Test
    void extractsUsageFromAResponsesCompletedEvent() {
        ChatResponseMetadata metadata = model(true).extractStreamUsage("""
                {"type": "response.completed",
                 "response": {"usage": {"input_tokens": 500, "output_tokens": 25,
                                        "total_tokens": 525}}}
                """);

        assertThat(metadata).isNotNull();
        assertThat(metadata.getUsage().getTotalTokens()).isEqualTo(525);
    }

    /** An ordinary text delta carries no usage and must not produce a phantom row. */
    @Test
    void ordinaryDeltaEventYieldsNoUsage() {
        assertThat(model(false).extractStreamUsage("""
                {"choices": [{"delta": {"content": "Hel"}}]}
                """)).isNull();
    }

    /** A malformed event must cost the usage row, never the user's answer. */
    @Test
    void malformedEventYieldsNullRatherThanThrowing() {
        assertThat(model(false).extractStreamUsage("not json at all")).isNull();
        assertThat(model(false).extractStreamUsage("")).isNull();
    }

    /** A zero-filled usage block is not worth emitting as a chunk. */
    @Test
    void zeroUsageIsTreatedAsNoUsage() {
        assertThat(model(false).extractStreamUsage("""
                {"usage": {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0}}
                """)).isNull();
    }
}
