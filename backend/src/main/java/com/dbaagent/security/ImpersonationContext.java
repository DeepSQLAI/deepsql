package com.dbaagent.security;

import com.dbaagent.model.User;

import java.util.Optional;

/**
 * Request-scoped impersonation overlay. The admin JWT stays on the session;
 * {@link JwtAuthenticationFilter} swaps the SecurityContext principal to the
 * target user and records both identities here so {@code /auth/me} can show a
 * banner and {@code AccessControlService} can honour the target even when
 * {@code security.auth.enabled} is false.
 */
public final class ImpersonationContext {

    public record State(User impersonator, User target) {
        public String impersonatorUsername() {
            return impersonator != null ? impersonator.getUsername() : null;
        }

        public String impersonatorEmail() {
            return impersonator != null ? impersonator.getEmail() : null;
        }

        public String targetUsername() {
            return target != null ? target.getUsername() : null;
        }
    }

    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();

    private ImpersonationContext() {
    }

    public static void enter(State state) {
        CURRENT.set(state);
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static Optional<State> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static boolean isActive() {
        return CURRENT.get() != null;
    }
}
