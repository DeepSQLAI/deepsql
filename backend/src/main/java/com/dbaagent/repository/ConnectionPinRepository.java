package com.dbaagent.repository;

import com.dbaagent.model.ConnectionPin;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface ConnectionPinRepository extends JpaRepository<ConnectionPin, Long> {

    /**
     * Case-insensitive, matching how {@code ConnectionAccessGrantRepository} resolves
     * usernames — a login that differs only in casing must not end up with a second pin
     * the unique constraint cannot see.
     */
    @Query("select p from ConnectionPin p where lower(p.username) = lower(?1)")
    Optional<ConnectionPin> findByUsernameIgnoreCase(String username);

    /**
     * Derived deletes need their own transaction. Annotating a self-invoked caller does
     * nothing — Spring proxies are bypassed by {@code this::} — which is the same trap
     * {@code McpTokenRepository.deleteByUserId} documents.
     */
    @Transactional
    void deleteByConnectionId(String connectionId);
}
