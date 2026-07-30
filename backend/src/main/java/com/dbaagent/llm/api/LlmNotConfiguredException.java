package com.dbaagent.llm.api;

import java.util.Locale;

/**
 * No LLM provider is configured. Distinct from a provider failure: the operator has not
 * finished setup, so the message points at the wizard rather than reporting a fault.
 */
public class LlmNotConfiguredException extends RuntimeException {

    public LlmNotConfiguredException(String role) {
        super("No " + role + " provider is configured. Complete setup at /onboarding, "
                + "or set the DEEPSQL_" + role.toUpperCase(Locale.ROOT)
                + "_* environment variables.");
    }
}
