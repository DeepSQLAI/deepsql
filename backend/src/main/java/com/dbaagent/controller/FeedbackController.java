package com.dbaagent.controller;

import com.dbaagent.model.ChatFeedback;
import com.dbaagent.service.FeedbackService;
import com.dbaagent.service.security.AccessControlService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for user feedback on AI responses.
 *
 * Endpoints for:
 * - Thumbs up/down rating
 * - Corrections ("use 'active' not 'enabled'")
 * - Teachings ("status values are: confirmed, pending, cancelled")
 * - Column value specifications
 */
@RestController
@RequestMapping("/feedback")
@RequiredArgsConstructor
@Slf4j
public class FeedbackController {

    private final FeedbackService feedbackService;
    private final AccessControlService accessControlService;

    /**
     * Record thumbs up feedback for an AI response.
     */
    @PostMapping("/thumbs-up")
    public ResponseEntity<ChatFeedback> recordThumbsUp(@RequestBody ThumbsFeedbackRequest request) {
        accessControlService.assertCanUseChatEditor(request.connectionId());
        accessControlService.assertChatBelongsToConnection(request.chatId(), request.connectionId());
        log.info("Recording thumbs up for connection: {}, message: {}", request.connectionId(), request.messageId());

        ChatFeedback feedback = feedbackService.recordThumbsUp(
            request.connectionId(),
            request.chatId(),
            request.messageId(),
            request.userMessage(),
            request.aiResponse(),
            request.sql(),
            request.agentRunId()
        );

        return ResponseEntity.ok(feedback);
    }

    /**
     * Record thumbs down feedback for an AI response.
     */
    @PostMapping("/thumbs-down")
    public ResponseEntity<ChatFeedback> recordThumbsDown(@RequestBody ThumbsDownRequest request) {
        accessControlService.assertCanUseChatEditor(request.connectionId());
        accessControlService.assertChatBelongsToConnection(request.chatId(), request.connectionId());
        log.info("Recording thumbs down for connection: {}, message: {}", request.connectionId(), request.messageId());

        ChatFeedback feedback = feedbackService.recordThumbsDown(
            request.connectionId(),
            request.chatId(),
            request.messageId(),
            request.userMessage(),
            request.aiResponse(),
            request.sql(),
            request.feedbackText()
        );

        return ResponseEntity.ok(feedback);
    }

    /**
     * Record a user correction.
     * Example: "For status column, use 'active' not 'enabled'"
     */
    @PostMapping("/correction")
    public ResponseEntity<ChatFeedback> recordCorrection(@RequestBody CorrectionRequest request) {
        accessControlService.assertCanUseChatEditor(request.connectionId());
        accessControlService.assertChatBelongsToConnection(request.chatId(), request.connectionId());
        log.info("Recording correction for connection: {}, table: {}.{}",
            request.connectionId(), request.tableName(), request.columnName());

        ChatFeedback feedback = feedbackService.recordCorrection(
            request.connectionId(),
            request.chatId(),
            request.messageId(),
            request.userMessage(),
            request.aiResponse(),
            request.sql(),
            request.correction(),
            request.tableName(),
            request.columnName()
        );

        return ResponseEntity.ok(feedback);
    }

    /**
     * Record a user teaching.
     * Example: "The bookings.status values are: confirmed, cancelled, pending"
     */
    @PostMapping("/teaching")
    public ResponseEntity<ChatFeedback> recordTeaching(@RequestBody TeachingRequest request) {
        accessControlService.assertCanUseChatEditor(request.connectionId());
        accessControlService.assertChatBelongsToConnection(request.chatId(), request.connectionId());
        log.info("Recording teaching for connection: {}", request.connectionId());

        ChatFeedback feedback = feedbackService.recordTeaching(
            request.connectionId(),
            request.chatId(),
            request.messageId(),
            request.userMessage(),
            request.teaching(),
            request.tableName(),
            request.columnName()
        );

        return ResponseEntity.ok(feedback);
    }

    /**
     * Record column value specification.
     * Example: "payment_method values: cash, card, upi, bank_transfer"
     */
    @PostMapping("/column-values")
    public ResponseEntity<ChatFeedback> recordColumnValues(@RequestBody ColumnValuesRequest request) {
        accessControlService.assertCanUseChatEditor(request.connectionId());
        log.info("Recording column values for {}.{}: {} values",
            request.tableName(), request.columnName(), request.values().size());

        ChatFeedback feedback = feedbackService.recordColumnValues(
            request.connectionId(),
            request.tableName(),
            request.columnName(),
            request.values()
        );

        return ResponseEntity.ok(feedback);
    }

    /**
     * Get recent feedback for a connection.
     */
    @GetMapping("/connection/{connectionId}")
    public ResponseEntity<List<ChatFeedback>> getRecentFeedback(
            @PathVariable String connectionId,
            @RequestParam(defaultValue = "50") int limit) {
        accessControlService.assertCanUseChatEditor(connectionId);
        return ResponseEntity.ok(feedbackService.getRecentFeedback(connectionId, limit));
    }

    /**
     * Get feedback by type for a connection.
     */
    @GetMapping("/connection/{connectionId}/type/{type}")
    public ResponseEntity<List<ChatFeedback>> getFeedbackByType(
            @PathVariable String connectionId,
            @PathVariable ChatFeedback.FeedbackType type) {
        accessControlService.assertCanUseChatEditor(connectionId);
        return ResponseEntity.ok(feedbackService.getFeedbackByType(connectionId, type));
    }

    /**
     * Get feedback for a specific chat.
     */
    @GetMapping("/chat/{chatId}")
    public ResponseEntity<List<ChatFeedback>> getFeedbackForChat(@PathVariable String chatId) {
        accessControlService.assertCanAccessChat(chatId);
        return ResponseEntity.ok(feedbackService.getFeedbackForChat(chatId));
    }

    /**
     * Get feedback for a specific table.
     */
    @GetMapping("/connection/{connectionId}/table/{tableName}")
    public ResponseEntity<List<ChatFeedback>> getFeedbackForTable(
            @PathVariable String connectionId,
            @PathVariable String tableName) {
        accessControlService.assertCanUseChatEditor(connectionId);
        return ResponseEntity.ok(feedbackService.getFeedbackForTable(connectionId, tableName));
    }

    /**
     * Get feedback statistics for a connection.
     */
    @GetMapping("/connection/{connectionId}/stats")
    public ResponseEntity<FeedbackService.FeedbackStats> getStats(@PathVariable String connectionId) {
        accessControlService.assertCanUseChatEditor(connectionId);
        return ResponseEntity.ok(feedbackService.getStats(connectionId));
    }

    /**
     * Delete feedback by ID.
     */
    @DeleteMapping("/{feedbackId}")
    public ResponseEntity<Map<String, String>> deleteFeedback(@PathVariable String feedbackId) {
        accessControlService.assertCanAccessFeedback(feedbackId);
        feedbackService.deleteFeedback(feedbackId);
        return ResponseEntity.ok(Map.of("status", "deleted", "feedbackId", feedbackId));
    }

    /**
     * Re-embed all unembedded feedback (useful for recovery).
     */
    @PostMapping("/connection/{connectionId}/reembed")
    public ResponseEntity<Map<String, Object>> reembedFeedback(@PathVariable String connectionId) {
        accessControlService.assertCanUseChatEditor(connectionId);
        int count = feedbackService.reembedUnembeddedFeedback(connectionId);
        return ResponseEntity.ok(Map.of("status", "completed", "reembeddedCount", count));
    }

    // Request DTOs

    public record ThumbsFeedbackRequest(
        String connectionId,
        String chatId,
        String messageId,
        String userMessage,
        String aiResponse,
        String sql,
        String agentRunId
    ) {}

    public record ThumbsDownRequest(
        String connectionId,
        String chatId,
        String messageId,
        String userMessage,
        String aiResponse,
        String sql,
        String feedbackText
    ) {}

    public record CorrectionRequest(
        String connectionId,
        String chatId,
        String messageId,
        String userMessage,
        String aiResponse,
        String sql,
        String correction,
        String tableName,
        String columnName
    ) {}

    public record TeachingRequest(
        String connectionId,
        String chatId,
        String messageId,
        String userMessage,
        String teaching,
        String tableName,
        String columnName
    ) {}

    public record ColumnValuesRequest(
        String connectionId,
        String tableName,
        String columnName,
        List<String> values
    ) {}
}
