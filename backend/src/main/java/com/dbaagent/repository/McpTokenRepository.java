package com.dbaagent.repository;

import com.dbaagent.model.McpToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface McpTokenRepository extends JpaRepository<McpToken, Long> {
    Optional<McpToken> findByPublicIdAndStatus(String publicId, McpToken.Status status);
    List<McpToken> findByUserIdOrderByCreatedAtDesc(Long userId);
    Optional<McpToken> findByIdAndUserId(Long id, Long userId);
    boolean existsByPublicId(String publicId);
}
