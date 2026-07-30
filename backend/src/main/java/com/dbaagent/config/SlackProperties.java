package com.dbaagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "slack")
@Data
public class SlackProperties {
    private boolean enabled;
    private boolean socketModeEnabled;
    private String appToken;
    private String botToken;
    private String signingSecret;
    private String deepsqlBotUsername;
}
