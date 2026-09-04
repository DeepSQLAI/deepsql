package com.dbaagent.dto;

import java.math.BigDecimal;

/** One row of a "by feature" / "by user" / "by model" breakdown. */
public record LlmUsageGroup(
        String key,
        long calls,
        long totalTokens,
        BigDecimal costUsd) {
}
