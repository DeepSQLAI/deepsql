package com.dbaagent.repository;

import com.dbaagent.model.DigestDeliveryMethod;
import com.dbaagent.model.UserDigestPreference;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for per-user digest preferences.
 *
 * <p>Provides queries for resolving digest preferences with fallback logic:
 * <ol>
 *   <li>User + connection-specific preference</li>
 *   <li>User-wide preference (connectionId is null)</li>
 *   <li>Global singleton fallback (handled in service layer)</li>
 * </ol>
 */
@Repository
public interface UserDigestPreferenceRepository extends JpaRepository<UserDigestPreference, Long> {

    /**
     * Find all enabled preferences for a user.
     */
    List<UserDigestPreference> findByUsernameAndEnabledTrue(String username);

    /**
     * Find all enabled preferences for a user and specific connection.
     * Includes both connection-specific and user-wide (connectionId = null) preferences.
     */
    @Query("""
        SELECT p FROM UserDigestPreference p
        WHERE p.username = :username
          AND p.enabled = true
          AND (p.connectionId = :connectionId OR p.connectionId IS NULL)
        ORDER BY p.connectionId DESC NULLS LAST
        """)
    List<UserDigestPreference> findEnabledForUserAndConnection(
        @Param("username") String username,
        @Param("connectionId") String connectionId
    );

    /**
     * Find a specific preference by user, connection, and delivery method.
     */
    Optional<UserDigestPreference> findByUsernameAndConnectionIdAndDeliveryMethod(
        String username,
        String connectionId,
        DigestDeliveryMethod deliveryMethod
    );

    /**
     * Find all preferences for a user (enabled or not).
     */
    List<UserDigestPreference> findByUsernameOrderByConnectionIdAscCreatedAtDesc(String username);

    /**
     * Find all enabled preferences for a specific delivery method.
     * Used to batch-send digests (e.g., all Slack DM recipients).
     */
    List<UserDigestPreference> findByEnabledTrueAndDeliveryMethod(DigestDeliveryMethod deliveryMethod);

    /**
     * Find all enabled preferences for a connection.
     * Returns users who should receive a digest for this connection.
     */
    @Query("""
        SELECT p FROM UserDigestPreference p
        WHERE p.enabled = true
          AND (p.connectionId = :connectionId OR p.connectionId IS NULL)
        ORDER BY p.username
        """)
    List<UserDigestPreference> findEnabledForConnection(@Param("connectionId") String connectionId);

    /**
     * Find all distinct usernames with at least one enabled preference.
     */
    @Query("SELECT DISTINCT p.username FROM UserDigestPreference p WHERE p.enabled = true")
    List<String> findDistinctUsernamesWithEnabledPreferences();

    /**
     * Check if a user has any digest preferences configured.
     * Used to determine whether to fall back to singleton behavior.
     */
    boolean existsByUsername(String username);

    /**
     * Check if any per-user preferences exist at all.
     * When false, the system operates entirely in singleton mode.
     */
    @Query("SELECT CASE WHEN COUNT(p) > 0 THEN true ELSE false END FROM UserDigestPreference p")
    boolean hasAnyPreferences();

    /**
     * Delete all preferences for a user.
     */
    void deleteByUsername(String username);

    /**
     * Count enabled preferences.
     */
    long countByEnabledTrue();
}
