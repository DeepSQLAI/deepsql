package com.dbaagent.llm.openai;

import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResponsesApiChatModelTest {

    private static ResponsesApiChatModel model(String endpoint) {
        return new ResponsesApiChatModel(endpoint, "test-key", "gpt-4o",
                "2025-03-01-preview", 1.0, false);
    }

    // ── Authentication header selection ──────────────────────────────────────
    // Azure authenticates with an `api-key` header; every other OpenAI-compatible
    // endpoint expects `Authorization: Bearer`. Before this was fixed the model sent
    // `api-key` unconditionally, so the exact configuration the README documents
    // (https://api.openai.com/v1 + an sk-... key) was rejected with a 401.

    @Test
    void openAiEndpointUsesAuthorizationBearer() {
        HttpRequest request = model("https://api.openai.com/v1")
                .buildHttpRequest("{}", Duration.ofSeconds(1));

        assertEquals("Bearer test-key",
                request.headers().firstValue("Authorization").orElse(null));
        assertFalse(request.headers().firstValue("api-key").isPresent(),
                "OpenAI must not receive Azure's api-key header");
    }

    @Test
    void azureEndpointUsesApiKeyHeader() {
        HttpRequest request = model("https://example.openai.azure.com")
                .buildHttpRequest("{}", Duration.ofSeconds(1));

        assertEquals("test-key", request.headers().firstValue("api-key").orElse(null));
        assertFalse(request.headers().firstValue("Authorization").isPresent(),
                "Azure must not receive a bearer token");
    }

    @Test
    void azureEndpointWithV1PathStillUsesApiKeyHeader() {
        // Regression guard: `/v1` selects the *API shape*, not the auth scheme. An Azure
        // v1-style endpoint is still Azure and must keep api-key auth.
        HttpRequest request = model("https://example.openai.azure.com/openai/v1")
                .buildHttpRequest("{}", Duration.ofSeconds(1));

        assertEquals("test-key", request.headers().firstValue("api-key").orElse(null));
        assertFalse(request.headers().firstValue("Authorization").isPresent());
    }

    @Test
    void azureApiManagementEndpointUsesApiKeyHeader() {
        HttpRequest request = model("https://example.azure-api.net/openai/v1")
                .buildHttpRequest("{}", Duration.ofSeconds(1));

        assertEquals("test-key", request.headers().firstValue("api-key").orElse(null));
    }

    @Test
    void selfHostedEndpointUsesAuthorizationBearer() {
        // vLLM / Ollama / LM Studio / LiteLLM all expect the OpenAI bearer scheme.
        HttpRequest request = model("http://localhost:11434/v1")
                .buildHttpRequest("{}", Duration.ofSeconds(1));

        assertEquals("Bearer test-key",
                request.headers().firstValue("Authorization").orElse(null));
    }

    @Test
    void authSchemeIsCaseInsensitiveOnHost() {
        HttpRequest request = model("https://EXAMPLE.OpenAI.Azure.COM")
                .buildHttpRequest("{}", Duration.ofSeconds(1));

        assertEquals("test-key", request.headers().firstValue("api-key").orElse(null));
    }

    @Test
    void everyRequestCarriesJsonContentTypeAndBody() {
        HttpRequest request = model("https://api.openai.com/v1")
                .buildHttpRequest("{}", Duration.ofSeconds(7));

        assertEquals("application/json",
                request.headers().firstValue("Content-Type").orElse(null));
        assertEquals("POST", request.method());
        assertTrue(request.timeout().isPresent());
        assertEquals(Duration.ofSeconds(7), request.timeout().get());
    }

    @Test
    void resolveApiUrlUsesResponsesPathForOpenAiCompatibleEndpoints() {
        String apiUrl = ResponsesApiChatModel.resolveApiUrl(
                "https://example.openai.azure.com/openai/v1",
                "gpt-5.4-pro",
                "2025-03-01-preview",
                true
        );

        assertEquals("https://example.openai.azure.com/openai/v1/responses", apiUrl);
    }

    @Test
    void resolveApiUrlUsesAzureResponsesPathForClassicAzureEndpoints() {
        String apiUrl = ResponsesApiChatModel.resolveApiUrl(
                "https://example.openai.azure.com",
                "gpt-5.4-pro",
                "2025-03-01-preview",
                true
        );

        assertEquals("https://example.openai.azure.com/openai/responses?api-version=2025-03-01-preview", apiUrl);
    }

    @Test
    void resolveApiUrlUsesChatCompletionsPathForOpenAiCompatibleEndpoints() {
        String apiUrl = ResponsesApiChatModel.resolveApiUrl(
                "https://example.openai.azure.com/openai/v1/",
                "gpt-5.2",
                "2025-03-01-preview",
                false
        );

        assertEquals("https://example.openai.azure.com/openai/v1/chat/completions", apiUrl);
    }

    // ── Sovereign Azure clouds ───────────────────────────────────────────────
    // Azure Government (.azure.us) and Azure China (.azure.cn) speak the same api-key
    // protocol as .azure.com. They used to take the Bearer branch and 401 with no
    // override available anywhere in the configuration.

    @Test
    void azureGovernmentEndpointUsesApiKeyHeader() {
        HttpRequest request = model("https://example.openai.azure.us/openai/v1")
                .buildHttpRequest("{}", Duration.ofSeconds(1));

        assertEquals("test-key", request.headers().firstValue("api-key").orElse(null));
        assertFalse(request.headers().firstValue("Authorization").isPresent());
    }

    @Test
    void azureChinaEndpointUsesApiKeyHeader() {
        HttpRequest request = model("https://example.openai.azure.cn/openai/v1")
                .buildHttpRequest("{}", Duration.ofSeconds(1));

        assertEquals("test-key", request.headers().firstValue("api-key").orElse(null));
        assertFalse(request.headers().firstValue("Authorization").isPresent());
    }

    // ── Retry classification ─────────────────────────────────────────────────

    /**
     * 5xx used to fall straight through to the error throw. It was survivable only
     * because LlmErrorCategory.TRANSIENT justified swapping the whole install onto the
     * environment credential bundle — a permanent configuration change in response to a
     * momentary fault. With that removed, retrying here is what makes a 503 recoverable.
     */
    @Test
    void serverErrorsAndThrottlingAreRetriedButClientErrorsAreNot() {
        assertTrue(ResponsesApiChatModel.isRetryableStatus(429));
        assertTrue(ResponsesApiChatModel.isRetryableStatus(500));
        assertTrue(ResponsesApiChatModel.isRetryableStatus(502));
        assertTrue(ResponsesApiChatModel.isRetryableStatus(503));
        assertTrue(ResponsesApiChatModel.isRetryableStatus(504));

        assertFalse(ResponsesApiChatModel.isRetryableStatus(200));
        assertFalse(ResponsesApiChatModel.isRetryableStatus(400));
        assertFalse(ResponsesApiChatModel.isRetryableStatus(401),
                "a rejected credential must never be retried");
        assertFalse(ResponsesApiChatModel.isRetryableStatus(404));
    }

    // ------------------------------------------------------------------
    // Retry backoff: exponential, jittered, floored by Retry-After, capped.
    // ------------------------------------------------------------------

    /**
     * The agent calls the model several times in parallel. A burst limit rejects those
     * calls together, so a deterministic backoff would wake them together and recreate
     * the burst. Repeated draws for the same attempt must not all be identical.
     */
    @Test
    void backoffIsRandomisedSoParallelCallersDoNotRetryInLockstep() {
        Set<Long> observed = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            observed.add(ResponsesApiChatModel.backoffMillis(0, 0));
        }

        assertTrue(observed.size() > 1,
                "a deterministic backoff makes concurrent callers collide on every retry");
    }

    /** Jitter must not weaken the schedule: the wait still grows with each attempt. */
    @Test
    void backoffStaysWithinItsExponentialBandAndGrows() {
        for (int i = 0; i < 200; i++) {
            long first = ResponsesApiChatModel.backoffMillis(0, 0);
            long fourth = ResponsesApiChatModel.backoffMillis(3, 0);

            // attempt 0 -> 2000ms exponential, half of it fixed; attempt 3 -> 16000ms.
            assertTrue(first >= 1000 && first <= 2000, "attempt 0 out of band: " + first);
            assertTrue(fourth >= 8000 && fourth <= 16000, "attempt 3 out of band: " + fourth);
            assertTrue(fourth > first, "backoff must still escalate across attempts");
        }
    }

    /**
     * {@code Retry-After} is a floor, not a replacement — retrying sooner than the
     * service asked is what the rate limiter is counting.
     */
    @Test
    void aRetryAfterInstructionRaisesTheWaitButNeverLowersIt() {
        assertEquals(5000, ResponsesApiChatModel.backoffMillis(0, 5000),
                "a longer Retry-After must win over our own schedule");

        long withTinyHint = ResponsesApiChatModel.backoffMillis(3, 50);
        assertTrue(withTinyHint >= 8000,
                "a shorter Retry-After must not shrink our backoff, got " + withTinyHint);
    }

    /**
     * The delay is a number from a remote server, and the request timeout is 8 minutes:
     * uncapped, one header could hold a request thread and a browser tab open for it.
     */
    @Test
    void anAbsurdRetryAfterIsCapped() {
        assertEquals(30_000, ResponsesApiChatModel.backoffMillis(0, 3_600_000));
    }

    /** A negative or nonsense instruction falls back to our own schedule. */
    @Test
    void aNegativeRetryAfterIsIgnored() {
        long backoff = ResponsesApiChatModel.backoffMillis(0, -5000);
        assertTrue(backoff >= 1000 && backoff <= 2000, "got " + backoff);
    }
}
