package com.dbaagent.service;

import com.dbaagent.model.ChatFeedback;
import com.dbaagent.model.ChatFeedback.FeedbackType;
import com.dbaagent.model.ApprovedAgentWorkflow;
import com.dbaagent.model.SchemaMetadata;
import com.dbaagent.repository.ChatFeedbackRepository;
import com.dbaagent.service.agent.ApprovedWorkflowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for capturing and storing user feedback.
 *
 * Handles:
 * - Thumbs up/down on AI responses
 * - User corrections ("the status should be 'active' not 'enabled'")
 * - User teachings ("for this table, status values are: confirmed, pending, cancelled")
 * - Column value specifications
 *
 * Feedback is stored in the database for persistence and can be retrieved
 * to enhance future chat responses.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FeedbackService {

    private static final int MAX_FEEDBACK_ITEMS = 20;
    private static final int MAX_FEEDBACK_CONTEXT_CHARS = 4000;

    private final ChatFeedbackRepository feedbackRepository;
    private final BusinessRuleMemoryService businessRuleMemoryService;
    private final TrainingService trainingService;
    private final SchemaScannerService schemaScannerService;
    private final ApprovedWorkflowService approvedWorkflowService;

    @Value("${app.chat.feedback.recency-days:90}")
    private int feedbackRecencyDays;

    @Value("${app.chat.feedback.train-on-thumbs-up:true}")
    private boolean trainOnThumbsUp;

    /**
     * Record thumbs up feedback for an AI response.
     */
    @Transactional
    public ChatFeedback recordThumbsUp(String connectionId, String chatId, String messageId,
                                        String userMessage, String aiResponse, String sql,
                                        String agentRunId) {
        ChatFeedback saved = recordFeedback(connectionId, chatId, messageId, userMessage, aiResponse, sql,
            FeedbackType.THUMBS_UP, null, null, null);
        Optional<ApprovedAgentWorkflow> approvedWorkflow = Optional.empty();

        if (agentRunId != null && !agentRunId.isBlank()) {
            try {
                approvedWorkflow = approvedWorkflowService.approveRun(connectionId, agentRunId);
            } catch (Exception e) {
                log.debug("Failed to approve agent workflow on thumbs-up: {}", e.getMessage());
            }
        }

        String trainingQuestion = approvedWorkflow
            .map(ApprovedAgentWorkflow::getExampleQuestion)
            .filter(question -> question != null && !question.isBlank())
            .orElse(userMessage);

        // Store thumbs-up SQL in RAG when enabled, so similar questions can retrieve it
        if (trainOnThumbsUp && sql != null && !sql.isBlank()
            && trainingQuestion != null && !trainingQuestion.isBlank()
            && isReadOnlySql(sql)) {
            try {
                trainingService.trainWithQueryExampleIfNotExists(
                    connectionId, trainingQuestion, sql, null, null);
            } catch (Exception e) {
                log.debug("Failed to train on thumbs-up: {}", e.getMessage());
            }
        }

        // Promote matching query examples to verified status after training so newly
        // created examples from this thumbs-up are verified immediately.
        if (sql != null && !sql.isBlank()) {
            try {
                trainingService.verifyQueryExamplesBySql(connectionId, sql);
            } catch (Exception e) {
                log.debug("Failed to verify query examples on thumbs-up: {}", e.getMessage());
            }
        }

        return saved;
    }

    private static boolean isReadOnlySql(String sql) {
        if (sql == null || sql.isBlank()) return false;
        String s = sql.trim().toLowerCase();
        return s.startsWith("select") || s.startsWith("show");
    }

    /**
     * Record thumbs down feedback for an AI response.
     */
    @Transactional
    public ChatFeedback recordThumbsDown(String connectionId, String chatId, String messageId,
                                          String userMessage, String aiResponse, String sql,
                                          String feedbackText) {
        ChatFeedback saved = recordFeedback(connectionId, chatId, messageId, userMessage, aiResponse, sql,
            FeedbackType.THUMBS_DOWN, feedbackText, null, null);

        // Explicit negative feedback should actively demote matching auto/verified examples
        // so they stop influencing future retrieval.
        if (sql != null && !sql.isBlank()) {
            try {
                trainingService.rejectQueryExamplesBySql(connectionId, sql);
            } catch (Exception e) {
                log.debug("Failed to reject query examples on thumbs-down: {}", e.getMessage());
            }
        }
        return saved;
    }

    /**
     * Record a user correction.
     * Example: "For status column, use 'active' not 'enabled'"
     */
    @Transactional
    public ChatFeedback recordCorrection(String connectionId, String chatId, String messageId,
                                          String userMessage, String aiResponse, String sql,
                                          String correction, String tableName, String columnName) {
        return recordFeedback(connectionId, chatId, messageId, userMessage, aiResponse, sql,
            FeedbackType.CORRECTION, correction, tableName, columnName);
    }

    /**
     * Record a user teaching about column values or business rules.
     * Example: "The bookings.status values are: confirmed, cancelled, pending"
     */
    @Transactional
    public ChatFeedback recordTeaching(String connectionId, String chatId, String messageId,
                                        String userMessage, String teaching, String tableName, String columnName) {
        return recordFeedback(connectionId, chatId, messageId, userMessage, null, null,
            FeedbackType.TEACHING, teaching, tableName, columnName);
    }

    /**
     * Record column value specification.
     * Example: "payment_method values: 'cash', 'card', 'upi', 'bank_transfer'"
     */
    @Transactional
    public ChatFeedback recordColumnValues(String connectionId, String tableName, String columnName,
                                            List<String> values) {
        String teaching = String.format("Valid values for %s.%s: %s",
            tableName, columnName, String.join(", ", values.stream().map(v -> "'" + v + "'").toList()));

        ChatFeedback feedback = new ChatFeedback();
        feedback.setId(UUID.randomUUID().toString());
        feedback.setConnectionId(connectionId);
        feedback.setFeedbackType(FeedbackType.COLUMN_VALUES);
        feedback.setFeedbackText(teaching);
        feedback.setTableName(tableName);
        feedback.setColumnName(columnName);
        feedback.setCreatedAt(LocalDateTime.now());
        feedback.setEmbedded(false);
        ChatFeedback saved = feedbackRepository.save(feedback);
        try {
            // Column value feedback is also fed into guardrail learning so later SQL generation
            // can enforce deterministic filters for the same connection.
            SchemaMetadata schema = loadSchemaQuietly(connectionId);
            businessRuleMemoryService.learnFromFeedback(
                connectionId,
                teaching,
                tableName,
                columnName,
                "feedback-column-values",
                schema
            );
        } catch (Exception e) {
            log.debug("Failed to learn SQL guardrails from column values feedback {}", saved.getId(), e);
        }
        return saved;
    }

    private ChatFeedback recordFeedback(String connectionId, String chatId, String messageId,
                                         String userMessage, String aiResponse, String sql,
                                         FeedbackType feedbackType, String feedbackText,
                                         String tableName, String columnName) {
        ChatFeedback feedback = new ChatFeedback();
        feedback.setId(UUID.randomUUID().toString());
        feedback.setConnectionId(connectionId);
        feedback.setChatId(chatId);
        feedback.setMessageId(messageId);
        feedback.setUserMessage(userMessage);
        feedback.setAiResponse(aiResponse);
        feedback.setSql(sql);
        feedback.setFeedbackType(feedbackType);
        feedback.setFeedbackText(feedbackText);
        feedback.setTableName(tableName);
        feedback.setColumnName(columnName);
        feedback.setCreatedAt(LocalDateTime.now());
        feedback.setEmbedded(false);

        ChatFeedback saved = feedbackRepository.save(feedback);

        if (feedbackText != null && !feedbackText.isBlank() &&
            (feedbackType == FeedbackType.CORRECTION ||
             feedbackType == FeedbackType.TEACHING ||
             feedbackType == FeedbackType.COLUMN_VALUES)) {
            try {
                // Persist learned business logic as SQL guardrails alongside raw feedback text.
                SchemaMetadata schema = loadSchemaQuietly(connectionId);
                businessRuleMemoryService.learnFromFeedback(
                    connectionId,
                    feedbackText,
                    tableName,
                    columnName,
                    "feedback-" + feedbackType.name().toLowerCase(),
                    schema
                );
            } catch (Exception e) {
                log.debug("Failed to learn SQL guardrails from feedback {}", saved.getId(), e);
            }
        }

        log.info("Recorded {} feedback for connection {}", feedbackType, connectionId);
        return saved;
    }

    /**
     * Get learnable feedback (corrections, teachings) for a connection.
     * Applies recency cutoff and item cap.
     */
    public List<ChatFeedback> getLearnableFeedback(String connectionId) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(feedbackRecencyDays);
        List<ChatFeedback> all = feedbackRepository.findLearnableFeedback(connectionId, cutoff);
        if (all.size() > MAX_FEEDBACK_ITEMS) {
            return all.subList(0, MAX_FEEDBACK_ITEMS);
        }
        return all;
    }

    /**
     * Build context string from learnable feedback for inclusion in chat prompts.
     * Enforces a character cap to prevent system prompt bloat.
     */
    public String buildFeedbackContext(String connectionId) {
        List<ChatFeedback> learnings = getLearnableFeedback(connectionId);
        if (learnings.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n\n=== LEARNED FROM PREVIOUS SESSIONS ===\n");
        sb.append("The user previously taught the following (apply this knowledge):\n\n");

        for (ChatFeedback learning : learnings) {
            String entry = formatFeedbackEntry(learning);
            if (sb.length() + entry.length() > MAX_FEEDBACK_CONTEXT_CHARS) {
                break;
            }
            sb.append(entry);
        }

        return sb.toString();
    }

    private SchemaMetadata loadSchemaQuietly(String connectionId) {
        try {
            return schemaScannerService.scanSchema(connectionId);
        } catch (Exception e) {
            log.debug("Could not load schema for feedback guardrail learning (connection={}): {}",
                connectionId, e.getMessage());
            return null;
        }
    }

    private String formatFeedbackEntry(ChatFeedback learning) {
        StringBuilder entry = new StringBuilder("- ");
        if (learning.getTableName() != null && learning.getColumnName() != null) {
            entry.append("[").append(learning.getTableName()).append(".").append(learning.getColumnName()).append("] ");
        }
        entry.append(learning.getFeedbackText());
        entry.append("\n");
        return entry.toString();
    }

    /**
     * Get recent feedback for a connection.
     */
    public List<ChatFeedback> getRecentFeedback(String connectionId, int limit) {
        return feedbackRepository.findByConnectionIdOrderByCreatedAtDesc(connectionId)
            .stream()
            .limit(limit)
            .toList();
    }

    /**
     * Get feedback by type for a connection.
     */
    public List<ChatFeedback> getFeedbackByType(String connectionId, FeedbackType type) {
        return feedbackRepository.findByConnectionIdAndFeedbackTypeOrderByCreatedAtDesc(connectionId, type);
    }

    /**
     * Get feedback for a specific chat.
     */
    public List<ChatFeedback> getFeedbackForChat(String chatId) {
        return feedbackRepository.findByChatIdOrderByCreatedAtDesc(chatId);
    }

    /**
     * Get feedback for a specific table.
     */
    public List<ChatFeedback> getFeedbackForTable(String connectionId, String tableName) {
        return feedbackRepository.findByConnectionIdAndTableNameOrderByCreatedAtDesc(connectionId, tableName);
    }

    /**
     * Delete feedback by ID.
     */
    @Transactional
    public void deleteFeedback(String feedbackId) {
        Optional<ChatFeedback> feedbackOpt = feedbackRepository.findById(feedbackId);
        if (feedbackOpt.isPresent()) {
            feedbackRepository.delete(feedbackOpt.get());
            log.info("Deleted feedback {}", feedbackId);
        }
    }

    /**
     * Re-embed all unembedded feedback for a connection.
     * Currently marks them as "ready" since we use database-based retrieval
     * instead of vector store embedding.
     *
     * @return count of feedback items processed
     */
    @Transactional
    public int reembedUnembeddedFeedback(String connectionId) {
        List<ChatFeedback> unembedded = feedbackRepository.findByConnectionIdAndEmbeddedFalse(connectionId);

        for (ChatFeedback feedback : unembedded) {
            // For learnable feedback types, mark as ready for retrieval
            if (feedback.getFeedbackType() == FeedbackType.CORRECTION ||
                feedback.getFeedbackType() == FeedbackType.TEACHING ||
                feedback.getFeedbackType() == FeedbackType.COLUMN_VALUES) {
                feedback.setEmbedded(true);
                feedbackRepository.save(feedback);
            }
        }

        log.info("Processed {} unembedded feedback items for connection {}", unembedded.size(), connectionId);
        return unembedded.size();
    }

    /**
     * Get feedback statistics for a connection.
     */
    public FeedbackStats getStats(String connectionId) {
        long thumbsUp = feedbackRepository.countByConnectionIdAndFeedbackType(connectionId, FeedbackType.THUMBS_UP);
        long thumbsDown = feedbackRepository.countByConnectionIdAndFeedbackType(connectionId, FeedbackType.THUMBS_DOWN);
        long corrections = feedbackRepository.countByConnectionIdAndFeedbackType(connectionId, FeedbackType.CORRECTION);
        long teachings = feedbackRepository.countByConnectionIdAndFeedbackType(connectionId, FeedbackType.TEACHING);
        long columnValues = feedbackRepository.countByConnectionIdAndFeedbackType(connectionId, FeedbackType.COLUMN_VALUES);

        return new FeedbackStats(thumbsUp, thumbsDown, corrections, teachings, columnValues);
    }

    public record FeedbackStats(
        long thumbsUp,
        long thumbsDown,
        long corrections,
        long teachings,
        long columnValues
    ) {
        public long total() {
            return thumbsUp + thumbsDown + corrections + teachings + columnValues;
        }

        public double positiveRate() {
            long total = thumbsUp + thumbsDown;
            return total > 0 ? (thumbsUp * 100.0 / total) : 0;
        }
    }
}
