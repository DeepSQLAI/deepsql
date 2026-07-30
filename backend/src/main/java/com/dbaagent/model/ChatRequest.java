package com.dbaagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequest {
    private String connectionId;
    private String message;
    private String threadId; // Legacy: use chatId instead
    private String chatId;
    private String userId;
}
