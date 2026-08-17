package com.dbaagent.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Rejects SSH bastion hosts that point back into infrastructure the caller
 * should not reach through DeepSQL — loopback, link-local (including the cloud
 * metadata endpoint), and RFC1918 ranges.
 *
 * The host is resolved and every returned address is checked, so a public
 * hostname whose A record points at 10.x or 169.254.169.254 is still refused.
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

        String host = normalize(sshHost);

        if (!properties.isEnabled() || isExplicitlyAllowed(host)) {
            return;
        }

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("SSH host could not be resolved: " + sshHost);
        }

        for (InetAddress address : addresses) {
            if (isBlocked(address)) {
                log.warn("Blocked SSH tunnel to restricted host {} (resolved to {})",
                        sshHost, address.getHostAddress());
                throw new IllegalArgumentException(
                        "SSH host '" + sshHost + "' resolves to a restricted address ("
                                + address.getHostAddress() + "). Add it to "
                                + "deepsql.ssh.host-guard.allowed-hosts if this is intentional.");
            }
        }
    }

    private boolean isExplicitlyAllowed(String host) {
        for (String allowed : properties.getAllowedHosts()) {
            if (allowed == null || allowed.isBlank()) continue;
            String candidate = normalize(allowed);
            if (candidate.startsWith(".")) {
                if (host.endsWith(candidate)) return true;
            } else if (host.equals(candidate)) {
                return true;
            }
        }
        return false;
    }

    private boolean isBlocked(InetAddress address) {
        if (address.isLoopbackAddress()
                || address.isAnyLocalAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        if (address instanceof Inet4Address) {
            return isBlockedIpv4(address.getAddress());
        }
        if (address instanceof Inet6Address v6) {
            // Unique local addresses (fc00::/7) have no isSiteLocalAddress() mapping in Java.
            if ((v6.getAddress()[0] & 0xFE) == 0xFC) return true;
            // IPv4-mapped/compatible forms smuggle a blocked v4 address through a v6 literal.
            byte[] embedded = embeddedIpv4(v6);
            return embedded != null && isBlockedIpv4(embedded);
        }
        return false;
    }

    private boolean isBlockedIpv4(byte[] octets) {
        int first = octets[0] & 0xFF;
        int second = octets[1] & 0xFF;
        // 100.64.0.0/10 carrier-grade NAT, used for cloud-internal routing.
        if (first == 100 && second >= 64 && second <= 127) return true;
        // 192.0.0.0/24 IETF protocol assignments.
        if (first == 192 && second == 0 && (octets[2] & 0xFF) == 0) return true;
        // 0.0.0.0/8 "this network".
        return first == 0;
    }

    private byte[] embeddedIpv4(Inet6Address address) {
        byte[] bytes = address.getAddress();
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) return null;
        }
        boolean mapped = (bytes[10] & 0xFF) == 0xFF && (bytes[11] & 0xFF) == 0xFF;
        boolean compat = bytes[10] == 0 && bytes[11] == 0;
        if (!mapped && !compat) return null;
        return new byte[]{bytes[12], bytes[13], bytes[14], bytes[15]};
    }

    private String normalize(String host) {
        String trimmed = host.trim().toLowerCase(Locale.ROOT);
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }
}
