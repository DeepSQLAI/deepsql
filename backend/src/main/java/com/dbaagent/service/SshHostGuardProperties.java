package com.dbaagent.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Binds the SSH bastion host guard.
 *
 * Off by default: bastions legitimately live on RFC1918 networks, so enabling
 * this would break existing self-hosted installs on upgrade. Operators who want
 * the protection set {@code deepsql.ssh.host-guard.enabled=true} and allowlist
 * their own bastion. Disabled means the SSRF surface is open — see the SSH
 * Tunnel SSRF Guard section in CLAUDE.md.
 */
@Component
@ConfigurationProperties(prefix = "deepsql.ssh.host-guard")
@Data
public class SshHostGuardProperties {

    private boolean enabled = false;

    /**
     * Hosts exempt from the private/link-local block. Matched case-insensitively
     * against the literal value, and against the resolved IP for hostnames.
     * A leading "." denotes a domain suffix match (".corp.example.com").
     */
    private List<String> allowedHosts = new ArrayList<>();
}
