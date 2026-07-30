package com.dbaagent.model;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class ChatHistoryResponse {
    private String id;
    private String projectId;
    private String connectionId;
    private String title;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime lastMessageAt;
    private List<MessageDTO> messages;

    @Data
    public static class MessageDTO {
        private String id;
        private String role;
        private String content;
        private String sql;
        private String metadata;
        private LocalDateTime createdAt;
    }
}
