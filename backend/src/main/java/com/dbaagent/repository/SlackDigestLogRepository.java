package com.dbaagent.repository;

import com.dbaagent.model.SlackDigestLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository for digest delivery logs.
 *
 * <p>Supports both legacy (channel-broadcast) and per-user digest queries.
 */
@Repository
public interface SlackDigestLogRepository extends JpaRepository<SlackDigestLog, Long> {

    // ==================== LEGACY QUERIES (channel-broadcast digests) ====================

    Page<SlackDigestLog> findAllByChannelIdIsNullOrderBySentAtDesc(Pageable pageable);

    Page<SlackDigestLog> findByConnectionIdAndChannelIdIsNullOrderBySentAtDesc(String connectionId, Pageable pageable);

    Optional<SlackDigestLog> findTopByConnectionIdAndChannelIdIsNullOrderBySentAtDesc(String connectionId);

    long countByConnectionIdAndChannelIdIsNull(String connectionId);

    // ==================== PER-USER DIGEST QUERIES ====================

    /**
     * Find all digests sent to a specific user, most recent first.
     */
    Page<SlackDigestLog> findByRecipientUsernameOrderBySentAtDesc(String recipientUsername, Pageable pageable);

    /**
     * Find the most recent digest sent to a user for a connection.
     */
    Optional<SlackDigestLog> findTopByConnectionIdAndRecipientUsernameOrderBySentAtDesc(
        String connectionId,
        String recipientUsername
    );

    /**
     * Find all digests sent to a user for a connection.
     */
    Page<SlackDigestLog> findByConnectionIdAndRecipientUsernameOrderBySentAtDesc(
        String connectionId,
        String recipientUsername,
        Pageable pageable
    );

    /**
     * Count digests sent to a user.
     */
    long countByRecipientUsername(String recipientUsername);

    /**
     * Find recent failed deliveries for a user.
     */
    @Query("""
        SELECT l FROM SlackDigestLog l
        WHERE l.recipientUsername = :username
          AND l.status = 'FAILED'
          AND l.sentAt > :since
        ORDER BY l.sentAt DESC
        """)
    List<SlackDigestLog> findRecentFailedForUser(
        @Param("username") String username,
        @Param("since") LocalDateTime since
    );

    /**
     * Find the most recent digest for each user on a connection.
     * Useful for showing "last digest" status in admin views.
     */
    @Query(value = """
        SELECT DISTINCT ON (recipient_username) *
        FROM slack_digest_log
        WHERE connection_id = :connectionId
          AND recipient_username IS NOT NULL
        ORDER BY recipient_username, sent_at DESC
        """, nativeQuery = true)
    List<SlackDigestLog> findLatestPerUserForConnection(@Param("connectionId") String connectionId);

    /**
     * Count personalized vs non-personalized digests since a date.
     */
    long countByPersonalizedAndSentAtAfter(boolean personalized, LocalDateTime since);
}
