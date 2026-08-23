package com.dbaagent.service;

import com.dbaagent.model.Permission;
import com.dbaagent.model.Role;
import com.dbaagent.model.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression: a successful login by a user holding a <em>custom</em> role must be
 * recognisable as successful.
 *
 * <p>{@code AuthFlowResult.role()} is null for a custom role, because {@link Role} can
 * only represent built-ins. {@code AuthController.authResponse} gated its success branch
 * on that field, so a custom-role login fell through to the "challenge required" branch
 * and threw NullPointerException inside {@code Map.of} on a null challengeId — HTTP 500
 * on every custom-role sign-in. Verified against a running install before the fix.
 *
 * <p>The controller now gates on {@code roleCode()}, which this pins.
 */
class AuthFlowResultCustomRoleTest {

    private static User userWithRole(String role) {
        User user = new User();
        user.setUsername("analyst");
        user.setEmail("analyst@localhost");
        user.setRole(role);
        return user;
    }

    @Test
    @DisplayName("A custom-role login carries a roleCode even though role is null")
    void customRoleStillYieldsARoleCode() {
        User user = userWithRole("ANALYST");

        PasswordlessAuthService.AuthFlowResult result = PasswordlessAuthService.AuthFlowResult
            .authenticated(user, Role.fromString("ANALYST"), Set.of(Permission.VIEW_DASHBOARDS), null);

        assertThat(result.success()).isTrue();
        assertThat(result.role()).isNull();          // the trap
        assertThat(result.roleCode()).isEqualTo("ANALYST");
        // The controller's success branch keys on this; null here was the 500.
        assertThat(result.roleCode()).isNotNull();
    }

    @Test
    @DisplayName("A built-in role login still carries both role and roleCode")
    void builtInRoleUnchanged() {
        User user = userWithRole("DBA");

        PasswordlessAuthService.AuthFlowResult result = PasswordlessAuthService.AuthFlowResult
            .authenticated(user, Role.fromString("DBA"), Set.of(Permission.VIEW_BRAIN), null);

        assertThat(result.role()).isEqualTo(Role.DBA);
        assertThat(result.roleCode()).isEqualTo("DBA");
    }

    @Test
    @DisplayName("User.getRoleCode never returns null, so the success gate cannot be tripped by a blank role")
    void roleCodeNeverNull() {
        assertThat(userWithRole(null).getRoleCode()).isEqualTo("DEVELOPER");
        assertThat(userWithRole("").getRoleCode()).isEqualTo("DEVELOPER");
        assertThat(userWithRole("  analyst  ").getRoleCode()).isEqualTo("ANALYST");
    }
}
