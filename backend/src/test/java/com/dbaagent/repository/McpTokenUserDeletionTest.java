package com.dbaagent.repository;

import com.dbaagent.model.McpToken;
import com.dbaagent.model.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression cover for the FK that made {@code POST /users/admin/reset} return 500.
 *
 * <p>{@code mcp_tokens.user_id} is a non-null FK with no cascade, so deleting a user
 * who holds a token failed with
 * {@code ConstraintViolationException: update or delete on table "users" violates
 * foreign key constraint "fkhm5walli9xtthjoek1ia4paqg" on table "mcp_tokens"}.
 *
 * <p>Reachable on every self-host install: {@code setup-agent.sh} mints an MCP token
 * for the admin on each run, so setting up the agent was enough to permanently break
 * the admin-reset escape hatch — exactly when an operator locked out of the UI needs
 * it most. {@code UserController} now clears a user's tokens before deleting them.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class McpTokenUserDeletionTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private McpTokenRepository mcpTokenRepository;

    private User persistUser(String username) {
        User user = new User();
        user.setUsername(username);
        user.setPassword("not-a-real-hash");
        user.setEmail(username + "@example.invalid");
        user.setRole("ADMIN");
        return userRepository.saveAndFlush(user);
    }

    private void persistToken(User user, String publicId) {
        McpToken token = new McpToken();
        token.setUser(user);
        token.setName("self-host-agent");
        token.setPublicId(publicId);
        token.setTokenPrefix("dsql_test");
        token.setTokenHash(new byte[] { 1, 2, 3, 4 });
        token.setStatus(McpToken.Status.ACTIVE);
        token.setCreatedAt(LocalDateTime.now());
        mcpTokenRepository.saveAndFlush(token);
    }

    @Test
    void deleteByUserIdClearsTheWayForUserDeletion() {
        User user = persistUser("admin-reset-ok");
        persistToken(user, "pub-reset-1");
        persistToken(user, "pub-reset-2");
        Long userId = user.getId();

        assertEquals(2, mcpTokenRepository.findByUserIdOrderByCreatedAtDesc(userId).size());

        long revoked = mcpTokenRepository.deleteByUserId(userId);
        assertEquals(2, revoked, "both of the user's tokens must be removed");

        // The delete that used to throw a FK ConstraintViolationException.
        userRepository.delete(user);
        userRepository.flush();

        assertTrue(userRepository.findById(userId).isEmpty(), "user must be gone");
        assertTrue(mcpTokenRepository.findByUserIdOrderByCreatedAtDesc(userId).isEmpty(),
            "the user's tokens must not outlive them");
    }

    @Test
    void deleteByUserIdLeavesOtherUsersTokensAlone() {
        User keep = persistUser("keeper");
        User drop = persistUser("dropped");
        persistToken(keep, "pub-keep");
        persistToken(drop, "pub-drop");

        long revoked = mcpTokenRepository.deleteByUserId(drop.getId());
        assertEquals(1, revoked);

        assertEquals(1, mcpTokenRepository.findByUserIdOrderByCreatedAtDesc(keep.getId()).size(),
            "deleting one user's tokens must not touch another's");
        assertTrue(mcpTokenRepository.findByUserIdOrderByCreatedAtDesc(drop.getId()).isEmpty());
    }

    @Test
    void deleteByUserIdIsSafeForAUserWithNoTokens() {
        User user = persistUser("tokenless");
        assertEquals(0, mcpTokenRepository.deleteByUserId(user.getId()));

        userRepository.delete(user);
        userRepository.flush();
        assertTrue(userRepository.findById(user.getId()).isEmpty());
    }
}
