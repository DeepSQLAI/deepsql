package com.dbaagent.repository;

import com.dbaagent.model.McpToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface McpTokenRepository extends JpaRepository<McpToken, Long> {
    Optional<McpToken> findByPublicIdAndStatus(String publicId, McpToken.Status status);
    List<McpToken> findByUserIdOrderByCreatedAtDesc(Long userId);
    Optional<McpToken> findByIdAndUserId(Long id, Long userId);
    boolean existsByPublicId(String publicId);

    /**
     * Remove every token belonging to a user. {@code mcp_tokens.user_id} is a
     * non-null FK with no cascade, so a user holding tokens cannot be deleted —
     * the delete fails with
     * {@code ConstraintViolationException: update or delete on table "users"
     * violates foreign key constraint on table "mcp_tokens"}.
     *
     * <p>This bit self-host in practice: {@code setup-agent.sh} mints a token for
     * the admin on every run, so merely setting up the agent made
     * {@code POST /users/admin/reset} return 500 from then on.
     *
     * <p>Tokens are credentials owned by the user, so removing them with the user
     * is the correct semantics rather than a workaround — and leaving them behind
     * would orphan live credentials.
     *
     * <p>{@code @Transactional} is required here, not decorative: a derived delete
     * query needs an active transaction, and Spring Data's repository proxy is the
     * only place that reliably supplies one. Annotating the caller instead does
     * nothing when the caller reaches this through self-invocation.
     */
    @Transactional
    long deleteByUserId(Long userId);
}
