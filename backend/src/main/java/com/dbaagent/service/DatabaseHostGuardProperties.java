package com.dbaagent.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Binds the database-host guard (CodeQL java/ssrf on ConnectionService).
 *
 * Off by default, same reasoning as {@link SshHostGuardProperties}: databases
 * legitimately live on RFC1918 networks — more often than bastions do — so
 * enabling this would break most existing installs on upgrade.
 */
@Component
@ConfigurationProperties(prefix = "deepsql.database.host-guard")
@Data
public class DatabaseHostGuardProperties {

    private boolean enabled = false;

    private List<String> allowedHosts = new ArrayList<>();
}
