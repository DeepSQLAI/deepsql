package com.dbaagent.model;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of a connection test including privilege checks.
 */
@Data
@Builder
public class ConnectionTestResult {
    private boolean success;
    private boolean connectionSuccessful;
    private boolean sshTunnelSuccessful;
    private String errorMessage;

    @Builder.Default
    private List<PrivilegeCheck> privilegeChecks = new ArrayList<>();

    /**
     * Individual privilege check result.
     */
    @Data
    @Builder
    public static class PrivilegeCheck {
        private String name;
        private String scope;
        private String reason;
        private boolean granted;
        private String errorMessage;
    }

    /**
     * Check if all required privileges are granted.
     */
    public boolean allPrivilegesGranted() {
        return privilegeChecks.stream().allMatch(PrivilegeCheck::isGranted);
    }

    /**
     * Get list of missing privileges.
     */
    public List<PrivilegeCheck> getMissingPrivileges() {
        return privilegeChecks.stream()
            .filter(p -> !p.isGranted())
            .toList();
    }
}
