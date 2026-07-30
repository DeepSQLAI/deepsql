package com.dbaagent.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SlackRuntimeSettingsService {

    private final SystemConfigService systemConfigService;

    public SlackRuntimeConfig current() {
        return new SlackRuntimeConfig(
            systemConfigService.getBoolean("slack.enabled"),
            systemConfigService.getBoolean("slack.socketModeEnabled"),
            systemConfigService.getOrDefault("slack.appToken", ""),
            systemConfigService.getOrDefault("slack.botToken", ""),
            systemConfigService.getOrDefault("slack.signingSecret", ""),
            systemConfigService.getOrDefault("slack.deepsqlBotUsername", "")
        );
    }

    public record SlackRuntimeConfig(
        boolean enabled,
        boolean socketModeEnabled,
        String appToken,
        String botToken,
        String signingSecret,
        String deepsqlBotUsername
    ) {
    }
}
