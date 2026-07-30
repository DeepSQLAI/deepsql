package com.dbaagent.controller;

import com.dbaagent.model.SlackDigestConfig;
import com.dbaagent.model.SlackDigestLog;
import com.dbaagent.repository.SlackDigestConfigRepository;
import com.dbaagent.repository.SlackDigestLogRepository;
import com.dbaagent.service.SlackBotService;
import com.dbaagent.service.SlackDailyDigestService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/admin/slack")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class SlackAdminController {

    private final SlackBotService slackBotService;
    private final SlackDailyDigestService slackDailyDigestService;
    private final SlackDigestLogRepository digestLogRepository;
    private final SlackDigestConfigRepository digestConfigRepository;

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        SlackBotService.StatusSnapshot status = slackBotService.status();
        return ResponseEntity.ok(Map.of(
            "started", status.started(),
            "running", status.running(),
            "lastError", status.lastError() != null ? status.lastError() : "",
            "botUserId", status.botUserId() != null ? status.botUserId() : "",
            "config", status.config()
        ));
    }

    @PostMapping("/digest/trigger")
    public ResponseEntity<Map<String, Object>> triggerDigest() {
        SlackDailyDigestService.TriggerResult result = slackDailyDigestService.triggerDigest();
        return ResponseEntity.ok(Map.of("triggered", result.triggered(), "message", result.message()));
    }

    @GetMapping("/digests")
    public ResponseEntity<Page<SlackDigestLog>> listDigests(
            @RequestParam(required = false) String connectionId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = PageRequest.of(page, size);
        Page<SlackDigestLog> result = connectionId != null && !connectionId.isBlank()
            ? digestLogRepository.findByConnectionIdAndChannelIdIsNullOrderBySentAtDesc(connectionId, pageable)
            : digestLogRepository.findAllByChannelIdIsNullOrderBySentAtDesc(pageable);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/digest/config")
    public ResponseEntity<SlackDigestConfig> getConfig() {
        SlackDigestConfig config = digestConfigRepository.findById(1L)
            .orElseGet(() -> {
                SlackDigestConfig def = new SlackDigestConfig();
                return digestConfigRepository.save(def);
            });
        return ResponseEntity.ok(config);
    }

    @PutMapping("/digest/config")
    public ResponseEntity<SlackDigestConfig> updateConfig(@RequestBody Map<String, String> body) {
        SlackDigestConfig config = digestConfigRepository.findById(1L)
            .orElseGet(SlackDigestConfig::new);
        if (body.containsKey("cronExpression")) {
            config.setCronExpression(body.get("cronExpression"));
        }
        config.setUpdatedAt(LocalDateTime.now());
        return ResponseEntity.ok(digestConfigRepository.save(config));
    }
}
