package com.dbaagent.repository;

import com.dbaagent.model.ChatFeedback;
import com.dbaagent.model.ChatFeedback.FeedbackType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Repository for ChatFeedback entity.
 * Provides queries for feedback management and embedding status tracking.
 */
@Repository
public interface ChatFeedbackRepository extends JpaRepository<ChatFeedback, String> {

    /**
     * Find all feedback for a connection ordered by creation date.
     */
    List<ChatFeedback> findByConnectionIdOrderByCreatedAtDesc(String connectionId);

    /**
     * Find feedback by connection and type.
     */
    List<ChatFeedback> findByConnectionIdAndFeedbackTypeOrderByCreatedAtDesc(
        String connectionId, FeedbackType feedbackType);

    /**
     * Find feedback that hasn't been embedded yet.
     */
    List<ChatFeedback> findByConnectionIdAndEmbeddedFalse(String connectionId);

    /**
     * Find all unembedded feedback (for batch embedding job).
     */
    List<ChatFeedback> findByEmbeddedFalseOrderByCreatedAtAsc();

    /**
     * Find feedback for a specific chat session.
     */
    List<ChatFeedback> findByChatIdOrderByCreatedAtDesc(String chatId);

    /**
     * Find feedback for a specific message.
     */
    List<ChatFeedback> findByMessageId(String messageId);

    /**
     * Find feedback for a specific table.
     */
    List<ChatFeedback> findByConnectionIdAndTableNameOrderByCreatedAtDesc(
        String connectionId, String tableName);

    /**
     * Count feedback by type for a connection.
     */
    long countByConnectionIdAndFeedbackType(String connectionId, FeedbackType feedbackType);

    /**
     * Count feedback by type for a connection (for analytics - grouped).
     */
    @Query("SELECT f.feedbackType, COUNT(f) FROM ChatFeedback f " +
           "WHERE f.connectionId = :connectionId " +
           "GROUP BY f.feedbackType")
    List<Object[]> countByConnectionIdGroupByFeedbackType(@Param("connectionId") String connectionId);

    /**
     * Find learnable feedback (corrections, teachings, column values) for chat context.
     * Returns all corrections, teachings, and column value specs regardless of embedding status.
     */
    @Query("SELECT f FROM ChatFeedback f " +
           "WHERE f.connectionId = :connectionId " +
           "AND f.feedbackType IN ('CORRECTION', 'TEACHING', 'COLUMN_VALUES') " +
           "ORDER BY f.createdAt DESC")
    List<ChatFeedback> findLearnableFeedback(@Param("connectionId") String connectionId);

    /**
     * Find learnable feedback with a recency cutoff and item limit.
     */
    @Query("SELECT f FROM ChatFeedback f " +
           "WHERE f.connectionId = :connectionId " +
           "AND f.feedbackType IN ('CORRECTION', 'TEACHING', 'COLUMN_VALUES') " +
           "AND f.createdAt >= :cutoff " +
           "ORDER BY f.createdAt DESC")
    List<ChatFeedback> findLearnableFeedback(
        @Param("connectionId") String connectionId,
        @Param("cutoff") LocalDateTime cutoff
    );

    /**
     * Find recent feedback within a time window.
     */
    List<ChatFeedback> findByConnectionIdAndCreatedAtAfterOrderByCreatedAtDesc(
        String connectionId, LocalDateTime since);

    /**
     * Count thumbs up for a message.
     */
    long countByMessageIdAndFeedbackType(String messageId, FeedbackType feedbackType);

    /**
     * Check if a message has any feedback of a specific type.
     */
    boolean existsByMessageIdAndFeedbackType(String messageId, FeedbackType feedbackType);

    /**
     * Delete all feedback for a connection.
     */
    void deleteByConnectionId(String connectionId);
}
