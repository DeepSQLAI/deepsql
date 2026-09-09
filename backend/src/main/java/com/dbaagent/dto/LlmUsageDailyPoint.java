package com.dbaagent.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One day of the spend trend. */
public record LlmUsageDailyPoint(
        LocalDate day,
        long calls,
        long totalTokens,
        BigDecimal costUsd) {
}
