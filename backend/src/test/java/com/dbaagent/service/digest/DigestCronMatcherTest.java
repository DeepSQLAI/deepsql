package com.dbaagent.service.digest;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DigestCronMatcherTest {

    private static final Duration ONE_MINUTE = Duration.ofMinutes(1);
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final ZoneId NY = ZoneId.of("America/New_York");

    @Test
    void isDue_whenNowMatchesCronMinute_returnsTrue() {
        // 09:00:00 UTC on a weekday — matches "0 0 9 * * *"
        Instant now = ZonedDateTime.of(2026, 9, 8, 9, 0, 0, 0, UTC).toInstant();

        assertThat(DigestCronMatcher.isDue("0 0 9 * * *", UTC, now, ONE_MINUTE)).isTrue();
        Optional<Instant> window = DigestCronMatcher.dueWindowStart("0 0 9 * * *", UTC, now, ONE_MINUTE);
        assertThat(window).isPresent();
        assertThat(window.get()).isEqualTo(now);
    }

    @Test
    void isDue_oneMinuteAfterFire_returnsFalse() {
        Instant now = ZonedDateTime.of(2026, 9, 8, 9, 1, 0, 0, UTC).toInstant();

        assertThat(DigestCronMatcher.isDue("0 0 9 * * *", UTC, now, ONE_MINUTE)).isFalse();
    }

    @Test
    void isDue_differentCron_onlyMatchesItsOwnHour() {
        Instant atEight = ZonedDateTime.of(2026, 9, 8, 8, 0, 0, 0, UTC).toInstant();
        Instant atNine = ZonedDateTime.of(2026, 9, 8, 9, 0, 0, 0, UTC).toInstant();

        assertThat(DigestCronMatcher.isDue("0 0 8 * * *", UTC, atEight, ONE_MINUTE)).isTrue();
        assertThat(DigestCronMatcher.isDue("0 0 9 * * *", UTC, atEight, ONE_MINUTE)).isFalse();

        assertThat(DigestCronMatcher.isDue("0 0 8 * * *", UTC, atNine, ONE_MINUTE)).isFalse();
        assertThat(DigestCronMatcher.isDue("0 0 9 * * *", UTC, atNine, ONE_MINUTE)).isTrue();
    }

    @Test
    void isDue_honorsTimezone() {
        // 09:00 America/New_York in September = 13:00 UTC
        Instant utcThirteen = ZonedDateTime.of(2026, 9, 8, 13, 0, 0, 0, UTC).toInstant();
        Instant utcNine = ZonedDateTime.of(2026, 9, 8, 9, 0, 0, 0, UTC).toInstant();

        assertThat(DigestCronMatcher.isDue("0 0 9 * * *", NY, utcThirteen, ONE_MINUTE)).isTrue();
        assertThat(DigestCronMatcher.isDue("0 0 9 * * *", NY, utcNine, ONE_MINUTE)).isFalse();
        assertThat(DigestCronMatcher.isDue("0 0 9 * * *", UTC, utcNine, ONE_MINUTE)).isTrue();
    }

    @Test
    void resolveZone_blankOrInvalid_fallsBackToUtc() {
        assertThat(DigestCronMatcher.resolveZone(null)).isEqualTo(UTC);
        assertThat(DigestCronMatcher.resolveZone("")).isEqualTo(UTC);
        assertThat(DigestCronMatcher.resolveZone("Not/AZone")).isEqualTo(UTC);
        assertThat(DigestCronMatcher.resolveZone("Asia/Kolkata")).isEqualTo(ZoneId.of("Asia/Kolkata"));
    }

    @Test
    void dueWindowStart_invalidCron_returnsEmpty() {
        Instant now = Instant.parse("2026-09-08T09:00:00Z");
        assertThat(DigestCronMatcher.dueWindowStart("not a cron", UTC, now, ONE_MINUTE)).isEmpty();
        assertThat(DigestCronMatcher.dueWindowStart(null, UTC, now, ONE_MINUTE)).isEmpty();
    }

    @Test
    void dueWindowStart_withinLookback_capturesFireTime() {
        // Tick at 09:00:30 — fire was at 09:00:00, still within 1-minute lookback
        ZonedDateTime fire = ZonedDateTime.of(2026, 9, 8, 9, 0, 0, 0, UTC);
        Instant tick = fire.plusSeconds(30).toInstant();

        Optional<Instant> window = DigestCronMatcher.dueWindowStart(
            "0 0 9 * * *", UTC, tick, ONE_MINUTE);
        assertThat(window).contains(fire.toInstant());
    }
}
