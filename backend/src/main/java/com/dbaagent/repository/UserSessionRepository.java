package com.dbaagent.repository;

import com.dbaagent.model.UserSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface UserSessionRepository extends JpaRepository<UserSession, String> {
    Optional<UserSession> findByRefreshTokenHash(String refreshTokenHash);
    List<UserSession> findAllByUserIdOrderByCreatedAtDesc(Long userId);
    List<UserSession> findAllByUserIdAndRevokedAtIsNullOrderByCreatedAtDesc(Long userId);

    /** Delete sessions whose refresh token expired before the given cutoff. */
    @Modifying
    @Query("DELETE FROM UserSession s WHERE s.refreshExpiresAt < :before")
    int deleteByRefreshExpiresAtBefore(@Param("before") LocalDateTime before);
}
