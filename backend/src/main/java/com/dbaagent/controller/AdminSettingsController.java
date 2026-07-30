package com.dbaagent.controller;

import com.dbaagent.model.User;
import com.dbaagent.repository.UserRepository;
import com.dbaagent.service.AdminSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/admin/settings")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminSettingsController {

    private final AdminSettingsService adminSettingsService;
    private final UserRepository userRepository;

    @GetMapping("/email")
    public ResponseEntity<?> getEmailSettings() {
        return ResponseEntity.ok(adminSettingsService.getEmailSettings());
    }

    @PutMapping("/email")
    public ResponseEntity<?> updateEmailSettings(
        @RequestBody AdminSettingsService.EmailSettingsRequest request,
        @RequestHeader(value = "X-Request-Id", required = false) String requestId
    ) {
        try {
            return ResponseEntity.ok(adminSettingsService.updateEmailSettings(request, currentUser(), requestId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/email/test")
    public ResponseEntity<?> testEmailSettings(
        @RequestBody Map<String, String> request,
        @RequestHeader(value = "X-Request-Id", required = false) String requestId
    ) {
        String recipient = request.get("recipient");
        if (recipient == null || recipient.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Recipient email is required"));
        }
        try {
            adminSettingsService.testEmailSettings(recipient.trim(), currentUser(), requestId);
            return ResponseEntity.ok(Map.of("message", "Test email sent"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @GetMapping("/security")
    public ResponseEntity<?> getSecuritySettings() {
        return ResponseEntity.ok(adminSettingsService.getSecuritySettings());
    }

    @PutMapping("/security")
    public ResponseEntity<?> updateSecuritySettings(
        @RequestBody AdminSettingsService.SecuritySettingsRequest request,
        @RequestHeader(value = "X-Request-Id", required = false) String requestId
    ) {
        try {
            return ResponseEntity.ok(adminSettingsService.updateSecuritySettings(request, currentUser(), requestId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @GetMapping("/slack")
    public ResponseEntity<?> getSlackSettings() {
        return ResponseEntity.ok(adminSettingsService.getSlackSettings());
    }

    @PutMapping("/slack")
    public ResponseEntity<?> updateSlackSettings(
        @RequestBody AdminSettingsService.SlackSettingsRequest request,
        @RequestHeader(value = "X-Request-Id", required = false) String requestId
    ) {
        return ResponseEntity.ok(adminSettingsService.updateSlackSettings(request, currentUser(), requestId));
    }

    private User currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null) {
            throw new IllegalStateException("No authenticated user");
        }
        return userRepository.findByUsername(authentication.getName())
            .orElseThrow(() -> new IllegalStateException("Authenticated user not found"));
    }
}
