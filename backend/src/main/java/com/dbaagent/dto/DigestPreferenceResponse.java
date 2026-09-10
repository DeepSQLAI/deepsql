package com.dbaagent.dto;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

/**
 * API view of a {@code UserDigestPreference} with a human-readable connection name.
 */
@Value
@Builder
public class DigestPreferenceResponse {
    Long id;
    String username;
    String connectionId;
    /** Display name for {@link #connectionId}; null when unknown or preference is connection-wide. */
    String connectionName;
    boolean enabled;
    String personaTag;
    String cronExpression;
    String deliveryMethod;
    String timezone;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;
}
