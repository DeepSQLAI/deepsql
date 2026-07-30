package com.dbaagent.llm.openai;

import com.dbaagent.llm.api.LlmCapability;
import com.dbaagent.llm.api.LlmCredentials;
import com.dbaagent.llm.api.LlmErrorCategory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiCompatibleChatProviderTest {

    private final OpenAiCompatibleChatProvider provider = new OpenAiCompatibleChatProvider();

    @Test
    void descriptorAdvertisesChatAndStreamingAndAzureAliases() {
        var d = provider.descriptor();
        assertThat(d.id()).isEqualTo("openai");
        assertThat(d.aliases()).contains("azure", "azure-openai", "self-hosted");
        assertThat(d.capabilities())
                .containsExactlyInAnyOrder(LlmCapability.CHAT, LlmCapability.STREAMING);
    }

    @Test
    void descriptorDeclaresTheCredentialFieldsTheWizardMustCollect() {
        var names = provider.descriptor().credentialFields().stream()
                .map(f -> f.name()).toList();
        assertThat(names).containsExactlyInAnyOrder(
                "api-key", "endpoint", "model", "api-version", "use-responses-api", "temperature");
        assertThat(provider.descriptor().credentialFields().stream()
                .filter(f -> f.name().equals("api-key"))
                .findFirst().orElseThrow().sensitive()).isTrue();
    }

    @Test
    void classifiesAuthFailures() {
        assertThat(provider.classify(new RuntimeException("Azure OpenAI error (401): denied")))
                .isEqualTo(LlmErrorCategory.AUTH);
        assertThat(provider.classify(new RuntimeException("OpenAI error (403): forbidden")))
                .isEqualTo(LlmErrorCategory.AUTH);
    }

    @Test
    void classifiesMissingDeploymentAsModelNotFound() {
        assertThat(provider.classify(new RuntimeException("DeploymentNotFound")))
                .isEqualTo(LlmErrorCategory.MODEL_NOT_FOUND);
        assertThat(provider.classify(new RuntimeException("error (404): no such model")))
                .isEqualTo(LlmErrorCategory.MODEL_NOT_FOUND);
    }

    @Test
    void classifiesThrottlingAndServerErrors() {
        assertThat(provider.classify(new RuntimeException("error (429): slow down")))
                .isEqualTo(LlmErrorCategory.RATE_LIMIT);
        assertThat(provider.classify(new RuntimeException("error (503): unavailable")))
                .isEqualTo(LlmErrorCategory.TRANSIENT);
    }

    @Test
    void classifiesNetworkFailuresAsTransientIncludingWhenNested() {
        assertThat(provider.classify(new ConnectException("refused")))
                .isEqualTo(LlmErrorCategory.TRANSIENT);
        assertThat(provider.classify(
                new RuntimeException("wrapped", new UnknownHostException("nope"))))
                .isEqualTo(LlmErrorCategory.TRANSIENT);
    }

    @Test
    void classifiesWrappedHttpTimeoutAsTransientNotUnknown() {
        // ResponsesApiChatModel.call() wraps any non-RuntimeException failure —
        // including an HttpTimeoutException from the 8-minute request timeout — in a
        // generic RuntimeException("Failed to call Azure OpenAI", cause). The old
        // RefreshableChatModel string-matching treated that generic wrapper as
        // recoverable; classify() must reach the same outcome by cause type, since the
        // wrapper message here carries no status code or recognisable keyword.
        assertThat(provider.classify(
                new RuntimeException("Failed to call Azure OpenAI",
                        new HttpTimeoutException("request timed out"))))
                .isEqualTo(LlmErrorCategory.TRANSIENT);
    }

    @Test
    void classifiesParseFailuresAsUnknownNotTransient() {
        // ResponsesApiChatModel.parseResponse() catches Exception and throws
        // RuntimeException("Failed to parse Azure OpenAI response: " + body, e) — a
        // *distinct* message from the generic call-path wrapper. Because that's already
        // a RuntimeException, call()'s first catch clause rethrows it verbatim; it never
        // reaches the generic catch(Exception e) that produces "Failed to call Azure
        // OpenAI". Under the old RefreshableChatModel string-matching this matched
        // neither the message nor any recognised type, so it was a hard, non-recoverable
        // failure. A JsonProcessingException is-a IOException, so a blanket IOException
        // check would wrongly reclassify this as TRANSIENT — classify() must carve out
        // this specific family.
        JsonProcessingException parseFailure = null;
        try {
            new ObjectMapper().readTree("{not valid json");
        } catch (JsonProcessingException e) {
            parseFailure = e;
        }
        assertThat(parseFailure).as("readTree must actually fail to parse").isNotNull();

        assertThat(provider.classify(new RuntimeException(
                "Failed to parse Azure OpenAI response: {not valid json", parseFailure)))
                .isEqualTo(LlmErrorCategory.UNKNOWN);
    }

    @Test
    void classifiesOtherWrappedIOExceptionsAsTransient() {
        // The generic call-path wrapper ("Failed to call Azure OpenAI") catches *any*
        // non-RuntimeException, not just HttpTimeoutException — a SocketException from a
        // connection reset arrives the same way and was equally recoverable under the
        // old string-matching. A four-type allowlist would miss this; classify() must
        // reach it too.
        assertThat(provider.classify(new RuntimeException("Failed to call Azure OpenAI",
                new SocketException("connection reset"))))
                .isEqualTo(LlmErrorCategory.TRANSIENT);
    }

    @Test
    void classifiesContextOverflow() {
        assertThat(provider.classify(
                new RuntimeException("This model's maximum context length is 128000 tokens")))
                .isEqualTo(LlmErrorCategory.CONTEXT_LENGTH);
    }

    @Test
    void unrecognisedFailuresAreUnknownAndNotRetried() {
        LlmErrorCategory category = provider.classify(new RuntimeException("kettle boiled over"));
        assertThat(category).isEqualTo(LlmErrorCategory.UNKNOWN);
        assertThat(category.isRetryable()).isFalse();
    }

    @Test
    void buildsADelegateBoundToTheSuppliedCredentials() {
        var credentials = new LlmCredentials("openai", Map.of(
                "api-key", "test-key",
                "endpoint", "https://example.invalid/",
                "model", "gpt-4o"));

        assertThat(provider.delegate(credentials)).isInstanceOf(ResponsesApiChatModel.class);
    }

    @Test
    void fallsBackToDefaultTemperatureOnUnparsableValue() {
        var credentials = new LlmCredentials("openai", Map.of(
                "api-key", "test-key",
                "endpoint", "https://example.invalid/",
                "model", "gpt-4o",
                "temperature", "not-a-number"));

        var delegate = provider.delegate(credentials);

        assertThat(delegate.getDefaultOptions().getTemperature()).isEqualTo(1.0);
    }
}
