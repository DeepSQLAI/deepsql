package com.dbaagent.service;

import com.dbaagent.model.ResourceLimits;
import com.dbaagent.repository.ResourceLimitsRepository;
import com.dbaagent.repository.SlowQueryCustomerDayRepository;
import com.dbaagent.repository.SlowQueryRunRepository;
import com.dbaagent.repository.SlowQuerySampleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * Time-based retention for the slow-query analytics tables.
 *
 * Replaces the old "keep 20 entries per connection" cap with a configurable
 * window ({@link ResourceLimits#getSlowQueryHistoryRetentionDays()}, default
 * 30 days). Kept as its own bean so the {@code @Modifying} bulk deletes run
 * inside a real transaction (a {@code @Transactional} method invoked from the
 * same bean would bypass the Spring proxy).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SlowQueryRetentionService {

    /** Used when a connection has no ResourceLimits row of its own. */
    public static final int DEFAULT_RETENTION_DAYS = 30;

    private final SlowQueryRunRepository runRepository;
    private final SlowQuerySampleRepository sampleRepository;
    private final SlowQueryCustomerDayRepository customerDayRepository;
    private final ResourceLimitsRepository resourceLimitsRepository;

    /** Resolve the retention window for a connection (days). */
    public int retentionDaysFor(String connectionId) {
        return resourceLimitsRepository.findByConnectionId(connectionId)
            .map(ResourceLimits::getSlowQueryHistoryRetentionDays)
            .filter(d -> d != null && d > 0)
            .orElse(DEFAULT_RETENTION_DAYS);
    }

    /**
     * Delete slow-query analytics rows older than the connection's retention
     * window across all three fact tables. Safe to call repeatedly.
     */
    @Transactional
    public void purge(String connectionId) {
        int days = retentionDaysFor(connectionId);
        LocalDate cutoff = LocalDate.now().minusDays(days);

        int runs = runRepository.deleteByConnectionIdAndAnalyzedOnBefore(connectionId, cutoff);
        int customerDays = customerDayRepository.deleteByConnectionIdAndDayBefore(connectionId, cutoff);
        int samples = sampleRepository.deleteByConnectionIdAndCapturedAtBefore(
            connectionId, cutoff.atStartOfDay());

        if (runs > 0 || customerDays > 0 || samples > 0) {
            log.info("Slow-query retention purge for connection {} (>{}d): "
                    + "{} runs, {} customer-days, {} samples removed",
                connectionId, days, runs, customerDays, samples);
        }
    }
}
