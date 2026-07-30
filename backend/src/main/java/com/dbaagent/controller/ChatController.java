package com.dbaagent.controller;

import com.dbaagent.model.ChatRequest;
import com.dbaagent.model.ChatResponse;
import com.dbaagent.service.ChatService;
import com.dbaagent.service.security.AccessControlService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/chat")
@RequiredArgsConstructor
public class ChatController {
    private final ChatService chatService;
    private final AccessControlService accessControlService;

    @PostMapping
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest request) {
        try {
            if (request.getConnectionId() == null || request.getConnectionId().isEmpty()) {
                return ResponseEntity.badRequest()
                    .body(new ChatResponse("Please select a database connection first.", false));
            }

            if (request.getMessage() == null || request.getMessage().trim().isEmpty()) {
                return ResponseEntity.badRequest()
                    .body(new ChatResponse("Please provide a message.", false));
            }
            accessControlService.assertCanUseChatEditor(request.getConnectionId());
            accessControlService.assertChatBelongsToConnectionIfPresent(request.getChatId(), request.getConnectionId());

            ChatResponse response = chatService.processMessage(
                request.getConnectionId(),
                request.getMessage(),
                request.getThreadId(),
                accessControlService.requireCurrentUsername(),
                request.getChatId()
            );

            return ResponseEntity.ok(response);

        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof ResponseStatusException responseStatusException) {
                return ResponseEntity.status(responseStatusException.getStatusCode())
                    .body(new ChatResponse(responseStatusException.getReason(), false));
            }
            return ResponseEntity.internalServerError()
                .body(new ChatResponse("Error processing request: " + e.getMessage(), false));
        }
    }

    /**
     * Stream chat response using Server-Sent Events (SSE).
     * Provides real-time token streaming for better UX during long responses.
     *
     * @param request Chat request with connectionId and message
     * @return Server-Sent Events stream of response tokens
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamChat(@RequestBody ChatRequest request) {
        if (request.getConnectionId() == null || request.getConnectionId().isEmpty()) {
            return Flux.just(ServerSentEvent.<String>builder()
                .data("Error: Please select a database connection first.").build());
        }

        if (request.getMessage() == null || request.getMessage().trim().isEmpty()) {
            return Flux.just(ServerSentEvent.<String>builder()
                .data("Error: Please provide a message.").build());
        }
        try {
            accessControlService.assertCanUseChatEditor(request.getConnectionId());
            accessControlService.assertChatBelongsToConnectionIfPresent(request.getChatId(), request.getConnectionId());
        } catch (ResponseStatusException e) {
            return Flux.just(ServerSentEvent.<String>builder()
                .data("Error: " + e.getReason()).build());
        }

        var streamResult = chatService.streamProcessMessage(
            request.getConnectionId(),
            request.getMessage(),
            accessControlService.requireCurrentUsername(),
            request.getChatId()
        );

        // 1. Metadata event: send chatId so frontend can persist chat continuity
        Flux<ServerSentEvent<String>> metadataFlux = streamResult.metadataStream()
            .filter(json -> json != null && !json.isBlank())
            .map(json -> ServerSentEvent.<String>builder()
                .event("metadata")
                .data(json)
                .build());

        // 2. Status events: compact progress for all users, detailed trace only when admins expand
        Flux<ServerSentEvent<String>> progressFlux = streamResult.progressStream()
            .map(json -> ServerSentEvent.<String>builder()
                .event("status")
                .data(json)
                .build());

        // 3. Structured final result
        Flux<ServerSentEvent<String>> resultFlux = streamResult.resultStream()
            .filter(json -> json != null && !json.isBlank())
            .map(json -> ServerSentEvent.<String>builder()
                .event("final")
                .data(json)
                .build());

        // 4. Content tokens: default SSE type (backward compatible)
        // Prefix a sentinel space so the SSE-spec "strip one leading space" rule
        // never eats a real space token.  Client must strip this prefix.
        Flux<ServerSentEvent<String>> contentFlux = streamResult.tokenStream()
            .map(token -> ServerSentEvent.<String>builder()
                .data(" " + token.replace("\n", "\\n"))
                .build());

        // 5. Done marker
        Flux<ServerSentEvent<String>> doneFlux = Flux.just(
            ServerSentEvent.<String>builder().data("[DONE]").build());

        return metadataFlux
            .concatWith(progressFlux)
            .concatWith(resultFlux)
            .concatWith(contentFlux)
            .onErrorResume(e -> Flux.just(ServerSentEvent.<String>builder()
                .data("Error: " + e.getMessage()).build()))
            .concatWith(doneFlux);
    }
}
