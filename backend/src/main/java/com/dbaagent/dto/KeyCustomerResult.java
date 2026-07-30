package com.dbaagent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Response envelope for the key-customers endpoint.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KeyCustomerResult {

    private String connectionId;
    private Instant analyzedAt;
    private int totalKeyCustomers;
    private int totalSlowQueriesAnalyzed;

    /** True when the raw analysis had more queries than the cap limit. */
    private boolean queriesCapped;

    /** Sorted by totalDbTimeMs desc. */
    private List<KeyCustomerInfo> keyCustomers;
}
