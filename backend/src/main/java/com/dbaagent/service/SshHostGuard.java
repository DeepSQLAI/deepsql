package com.dbaagent.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetAddress;

/**
 * Rejects SSH bastion hosts that point back into infrastructure the caller
 * should not reach through DeepSQL — loopback, link-local (including the cloud
 * metadata endpoint), and RFC1918 ranges.
 *
 * Address classification lives in {@link OutboundHostGuard}, shared with the
 * database-host and presigned-URL guards.
 */
@Component
@Slf4j
public class SshHostGuard {

    private final SshHostGuardProperties properties;

    public SshHostGuard(SshHostGuardProperties properties) {
        this.properties = properties;
    }

    public void assertAllowed(String sshHost) {
        if (sshHost == null || sshHost.isBlank()) {
            throw new IllegalArgumentException("SSH host is required");
        }

        String host = OutboundHostGuard.normalize(sshHost);

        if (!properties.isEnabled() || OutboundHostGuard.isAllowlisted(host, properties.getAllowedHosts())) {
            return;
        }

        InetAddress blocked;
        try {
            blocked = OutboundHostGuard.findBlockedAddress(host);
        } catch (OutboundHostGuard.BlockedHostException e) {
            throw new IllegalArgumentException("SSH host could not be resolved: " + sshHost);
        }

        if (blocked != null) {
            log.warn("Blocked SSH tunnel to restricted host {} (resolved to {})",
                    sshHost, blocked.getHostAddress());
            throw new IllegalArgumentException(
                    "SSH host '" + sshHost + "' resolves to a restricted address ("
                            + blocked.getHostAddress() + "). Add it to "
                            + "deepsql.ssh.host-guard.allowed-hosts if this is intentional.");
        }
    }
}
