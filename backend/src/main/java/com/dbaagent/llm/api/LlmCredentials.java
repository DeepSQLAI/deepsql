package com.dbaagent.llm.api;

import java.util.Map;
import java.util.Objects;

/**
 * Resolved credential values for one provider. Immutable.
 *
 * <p>{@link #signature()} deliberately excludes secrets, so it is safe to log and to use
 * as a cache-key component.
 */
public record LlmCredentials(String providerId, Map<String, String> values) {

    public LlmCredentials {
        Objects.requireNonNull(providerId, "providerId");
        values = Map.copyOf(values);   // defensive copy keeps the record immutable
    }

    public String get(String field) {
        return values.get(field);
    }

    public String getOrDefault(String field, String fallback) {
        String v = values.get(field);
        return (v == null || v.isBlank()) ? fallback : v;
    }

    public boolean has(String field) {
        String v = values.get(field);
        return v != null && !v.isBlank();
    }

    /** Non-secret identity of this configuration. Safe to log. */
    public String signature() {
        return providerId + "|" + getOrDefault("endpoint", "") + "|"
                + getOrDefault("model", "") + "|" + getOrDefault("region", "");
    }
}
