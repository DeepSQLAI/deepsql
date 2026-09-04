package com.dbaagent.dto;

import java.math.BigDecimal;

/**
 * Window totals. {@code unpricedCalls} is reported alongside cost so the UI can say
 * "$12.40 across 900 calls, 40 of them unpriced" rather than presenting a partial sum as
 * if it were complete.
 */
public record LlmUsageTotals(
        long calls,
        long promptTokens,
        long completionTokens,
        long totalTokens,
        BigDecimal costUsd,
        long unpricedCalls,
        long failedCalls) {

    public static LlmUsageTotals empty() {
        return new LlmUsageTotals(0, 0, 0, 0, BigDecimal.ZERO, 0, 0);
    }
}
