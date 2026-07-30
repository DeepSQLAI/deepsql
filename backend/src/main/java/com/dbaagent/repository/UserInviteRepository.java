package com.dbaagent.repository;

import com.dbaagent.model.UserInvite;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface UserInviteRepository extends JpaRepository<UserInvite, Long> {
    Optional<UserInvite> findByTokenHash(String tokenHash);
    List<UserInvite> findAllByEmailIgnoreCaseOrderByCreatedAtDesc(String email);
    List<UserInvite> findAllByExpiresAtBeforeAndAcceptedAtIsNull(LocalDateTime expiresAt);
    Optional<UserInvite> findTopByUserIdAndAcceptedAtIsNullAndRevokedAtIsNullOrderByCreatedAtDesc(Long userId);
}
