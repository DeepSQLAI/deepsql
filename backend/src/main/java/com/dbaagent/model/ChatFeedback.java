package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Entity for storing user feedback on chat responses.
 * Enables cross-session learning by persisting corrections and teachings
 * which can be retrieved to enhance future chat context.
 */
@Entity
@Table(name = "chat_feedback", indexes = {
    @Index(name = "idx_feedback_connection", columnList = "connection_id"),
    @Index(name = "idx_feedback_embedded", columnList = "connection_id,embedded"),
    @Index(name = "idx_feedback_type", columnList = "connection_id,type"),
    @Index(name = "idx_feedback_chat", columnList = "chat_id"),
    @Index(name = "idx_feedback_created", columnList = "created_at"),
    @Index(name = "idx_feedback_table", columnList = "connection_id,table_name"),
    @Index(name = "idx_feedback_column", columnList = "connection_id,table_name,column_name")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatFeedback {

    @Id
    private String id;

    @Column(name = "connection_id", nullable = false)
    private String connectionId;

    @Column(name = "chat_id")
    private String chatId;

    @Column(name = "message_id")
    private String messageId;

    /**
     * Type of feedback provided by the user.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 50)
    private FeedbackType feedbackType;

    /**
     * The original user message/question.
     */
    @Column(name = "user_message", columnDefinition = "TEXT")
    private String userMessage;

    /**
     * The AI response that the user is providing feedback on.
     */
    @Column(name = "ai_response", columnDefinition = "TEXT")
    private String aiResponse;

    /**
     * The SQL query that was generated (if any).
     */
    @Column(name = "sql", columnDefinition = "TEXT")
    private String sql;

    /**
     * The user's feedback text (correction, teaching, or general feedback).
     */
    @Column(name = "feedback_text", columnDefinition = "TEXT")
    private String feedbackText;

    /**
     * Table name for table-specific feedback.
     */
    @Column(name = "table_name")
    private String tableName;

    /**
     * Column name for column-specific corrections/teachings.
     */
    @Column(name = "column_name")
    private String columnName;

    /**
     * Whether this feedback has been embedded into the vector store.
     */
    @Column(nullable = false)
    @Builder.Default
    private Boolean embedded = false;

    /**
     * ID of the document in the vector store (for updates/deletes).
     */
    @Column(name = "embedding_id")
    private String embeddingId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by")
    private String createdBy;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    /**
     * Types of feedback a user can provide.
     */
    public enum FeedbackType {
        /**
         * User indicated the response was good.
         * Used for analytics, not embedded.
         */
        THUMBS_UP,

        /**
         * User indicated the response was bad.
         * Used for analytics, may trigger review.
         */
        THUMBS_DOWN,

        /**
         * User corrected an incorrect response.
         * Will be embedded for future retrieval.
         */
        CORRECTION,

        /**
         * User explicitly taught new information.
         * Will be embedded for future retrieval.
         */
        TEACHING,

        /**
         * User specified valid column values.
         * Will be embedded with column metadata.
         */
        COLUMN_VALUES
    }
}
