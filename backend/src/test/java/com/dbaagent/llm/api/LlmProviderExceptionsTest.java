package com.dbaagent.llm.api;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmProviderExceptionsTest {

    @Test
    void unsupportedLlmProviderExceptionBuildsMessageWithRequestedAndSupported() {
        UnsupportedLlmProviderException exception =
                new UnsupportedLlmProviderException("bedrock", Set.of("openai", "azure-openai"));

        assertEquals(
                "Unsupported LLM provider 'bedrock'. Supported: " + Set.of("openai", "azure-openai"),
                exception.getMessage());
    }

    @Test
    void llmNotConfiguredExceptionBuildsMessagePointingAtOnboardingAndEnvVars() {
        LlmNotConfiguredException exception = new LlmNotConfiguredException("chat");

        assertEquals(
                "No chat provider is configured. Complete setup at /onboarding, "
                        + "or set the DEEPSQL_CHAT_* environment variables.",
                exception.getMessage());
    }

    @Test
    void llmNotConfiguredExceptionUppercasesTheRoleInTheEnvVarPrefix() {
        LlmNotConfiguredException exception = new LlmNotConfiguredException("embedding");

        assertTrue(exception.getMessage().contains("DEEPSQL_EMBEDDING_* environment variables"));
    }
}
