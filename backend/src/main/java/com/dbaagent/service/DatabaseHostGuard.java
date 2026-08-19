package com.dbaagent.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetAddress;

/**
 * Screens the database host a user supplies before a JDBC connection is opened.
 *
 * Same class of exposure as {@link SshHostGuard} but a different code path: the
 * SSH guard sees only request.getSshHost(), and a direct (non-tunnelled)
 * connection never passes through it.
 */
@Component
@Slf4j
public class DatabaseHostGuard {

    private final DatabaseHostGuardProperties properties;

    public DatabaseHostGuard(DatabaseHostGuardProperties properties) {
        this.properties = properties;
    }

    public void assertAllowed(String databaseHost) {
        if (!properties.isEnabled() || databaseHost == null || databaseHost.isBlank()) {
            return;
        }

        String host = OutboundHostGuard.normalize(databaseHost);
        if (OutboundHostGuard.isAllowlisted(host, properties.getAllowedHosts())) {
            return;
        }

        InetAddress blocked;
        try {
            blocked = OutboundHostGuard.findBlockedAddress(host);
        } catch (OutboundHostGuard.BlockedHostException e) {
            throw new IllegalArgumentException("Database host could not be resolved: " + databaseHost);
        }

        if (blocked != null) {
            log.warn("Blocked database connection to restricted host {} (resolved to {})",
                    databaseHost, blocked.getHostAddress());
            throw new IllegalArgumentException(
                    "Database host '" + databaseHost + "' resolves to a restricted address ("
                            + blocked.getHostAddress() + "). Add it to "
                            + "deepsql.database.host-guard.allowed-hosts if this is intentional.");
        }
    }
}
