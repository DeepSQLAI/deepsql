package com.dbaagent.util;

/**
 * Lightweight token count estimator using chars/4 heuristic.
 * Suitable for budget enforcement before sending to LLM APIs.
 */
public final class TokenEstimator {

    private static final int CHARS_PER_TOKEN = 4;

    private TokenEstimator() {
    }

    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (text.length() + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN;
    }
}
