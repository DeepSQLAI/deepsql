package com.dbaagent.repository;

import com.dbaagent.model.AuthLoginChallenge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface AuthLoginChallengeRepository extends JpaRepository<AuthLoginChallenge, String> {
    List<AuthLoginChallenge> findAllByEmailIgnoreCaseAndCreatedAtAfter(String email, LocalDateTime createdAt);
    List<AuthLoginChallenge> findAllByClientIpAndCreatedAtAfter(String clientIp, LocalDateTime createdAt);

    /**
     * Count failed (and expired) auth challenges since a given timestamp.
     * Used by the daily digest's security section to surface auth anomalies.
     */
    @Query("SELECT COUNT(c) FROM AuthLoginChallenge c " +
           "WHERE c.createdAt >= :since " +
           "AND c.challengeState IN ('FAILED', 'EXPIRED')")
    long countFailedSince(@Param("since") LocalDateTime since);

    /**
     * Recent failed/expired auth challenges (most recent first), capped by Pageable in callers.
     */
    @Query("SELECT c FROM AuthLoginChallenge c " +
           "WHERE c.createdAt >= :since " +
           "AND c.challengeState IN ('FAILED', 'EXPIRED') " +
           "ORDER BY c.createdAt DESC")
    List<AuthLoginChallenge> findRecentFailures(@Param("since") LocalDateTime since);

    /** Delete challenges whose expiry date is before the given cutoff. */
    @Modifying
    @Query("DELETE FROM AuthLoginChallenge c WHERE c.expiresAt < :before")
    int deleteByExpiresAtBefore(@Param("before") LocalDateTime before);
}
