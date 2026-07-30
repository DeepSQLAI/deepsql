package com.dbaagent.llm.api;

/**
 * One credential input a provider declares. The setup wizard renders its form from these
 * and the backend validates against them, so adding a provider requires no controller or
 * UI change.
 *
 * @param name        config-key suffix, e.g. "api-key"
 * @param label       human label, e.g. "API key"
 * @param sensitive   true to AES-GCM encrypt at rest and mask on read
 * @param required    true if the provider cannot operate without it
 * @param pattern     regex the value must match, or null for no constraint
 * @param placeholder example shown in the UI; must never be a real credential
 */
public record LlmCredentialField(
        String name,
        String label,
        boolean sensitive,
        boolean required,
        String pattern,
        String placeholder
) {
    public static LlmCredentialField secret(String name, String label) {
        return new LlmCredentialField(name, label, true, true, null, null);
    }

    public static LlmCredentialField required(String name, String label, String placeholder) {
        return new LlmCredentialField(name, label, false, true, null, placeholder);
    }

    public static LlmCredentialField optional(String name, String label, String placeholder) {
        return new LlmCredentialField(name, label, false, false, null, placeholder);
    }
}
