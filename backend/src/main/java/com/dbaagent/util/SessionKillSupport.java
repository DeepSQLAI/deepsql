package com.dbaagent.util;

/**
 * Shared helpers for terminating DB backends/sessions without SQL injection.
 * PID/session ids must be numeric; never concatenate unvalidated strings into SQL.
 */
public final class SessionKillSupport {

    private SessionKillSupport() {}

    /**
     * Parse a session/backend id. Rejects null, empty, signed, hex, or any non-digit input.
     */
    public static long requireNumericPid(String pid) {
        // ASCII digits only — avoid Unicode numeric characters that Long.parseLong accepts.
        if (pid == null || pid.isBlank() || !pid.matches("[0-9]+")) {
            throw new IllegalArgumentException("Invalid session id: must be a non-negative integer");
        }
        try {
            return Long.parseLong(pid);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid session id: value out of range", e);
        }
    }
}
