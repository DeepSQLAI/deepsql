package com.dbaagent.controller;

import com.dbaagent.service.SlackUserLinkService;
import com.dbaagent.service.security.AccessControlService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/slack/link")
@RequiredArgsConstructor
public class SlackLinkController {

    private final SlackUserLinkService slackUserLinkService;
    private final AccessControlService accessControlService;

    @PostMapping("/code")
    public ResponseEntity<?> createLinkCode() {
        String username = accessControlService.getCurrentUsername();
        if (username == null || username.isBlank()) {
            return ResponseEntity.status(403).body(Map.of("message", "Authentication required"));
        }
        return ResponseEntity.ok(slackUserLinkService.createLinkCode(username));
    }

    @GetMapping("/code")
    public ResponseEntity<?> getCurrentLinkCode() {
        String username = accessControlService.getCurrentUsername();
        if (username == null || username.isBlank()) {
            return ResponseEntity.status(403).body(Map.of("message", "Authentication required"));
        }
        return ResponseEntity.ok(slackUserLinkService.getCurrentLinkCode(username));
    }

    @GetMapping("/connections")
    public ResponseEntity<?> listCurrentUserConnections() {
        String username = accessControlService.getCurrentUsername();
        if (username == null || username.isBlank()) {
            return ResponseEntity.status(403).body(Map.of("message", "Authentication required"));
        }
        return ResponseEntity.ok(slackUserLinkService.listVisibleConnections(
            username,
            accessControlService.isCurrentUserAdmin()
        ));
    }
}
