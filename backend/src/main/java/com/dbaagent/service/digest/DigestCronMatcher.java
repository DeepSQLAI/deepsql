package com.dbaagent.service.digest;

import org.springframework.scheduling.support.CronExpression;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * Helpers for deciding whether a Spring 6-field cron expression is due
 * within a recent lookback window, interpreted in a given timezone.
 *
 * <p>Used by the digest minute-tick scheduler so each
 * {@code UserDigestPreference.cronExpression} fires in that user's timezone
 * without registering a separate db-scheduler task per preference.
 */
public final class DigestCronMatcher {

    private DigestCronMatcher() {}

    /**
     * Resolve an IANA timezone id; blank/invalid values fall back to UTC.
     */
    public static ZoneId resolveZone(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return ZoneId.of("UTC");
        }
        try {
            return ZoneId.of(timezone.trim());
        } catch (Exception e) {
            return ZoneId.of("UTC");
        }
    }

    /**
     * Returns the fire instant for the cron if it falls in {@code (now - lookback, now]},
     * interpreted in {@code zone}. Empty when not due or the cron is invalid.
     *
     * @param cronExpression Spring 6-field cron (sec min hour dom month dow)
     * @param zone           timezone used to evaluate the cron
     * @param now            current instant (typically clock.instant())
     * @param lookback       how far back to search for a matching fire (e.g. 1 minute for a minute tick)
     */
    public static Optional<Instant> dueWindowStart(
            String cronExpression,
            ZoneId zone,
            Instant now,
            Duration lookback) {
        if (cronExpression == null || cronExpression.isBlank() || zone == null || now == null) {
            return Optional.empty();
        }
        if (lookback == null || lookback.isNegative() || lookback.isZero()) {
            lookback = Duration.ofMinutes(1);
        }

        final CronExpression cron;
        try {
            cron = CronExpression.parse(cronExpression.trim());
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }

        ZonedDateTime zonedNow = now.atZone(zone);
        ZonedDateTime from = zonedNow.minus(lookback);
        ZonedDateTime next = cron.next(from);
        if (next != null && !next.isAfter(zonedNow)) {
            return Optional.of(next.toInstant());
        }
        return Optional.empty();
    }

    /**
     * Convenience: true when {@link #dueWindowStart} is present.
     */
    public static boolean isDue(
            String cronExpression,
            ZoneId zone,
            Instant now,
            Duration lookback) {
        return dueWindowStart(cronExpression, zone, now, lookback).isPresent();
    }
}
