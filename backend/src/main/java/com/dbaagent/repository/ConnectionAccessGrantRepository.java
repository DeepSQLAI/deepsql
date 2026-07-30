package com.dbaagent.repository;

import com.dbaagent.model.ConnectionAccessGrant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ConnectionAccessGrantRepository extends JpaRepository<ConnectionAccessGrant, Long> {
    @Query("select g from ConnectionAccessGrant g where lower(g.username) = lower(?1) order by g.updatedAt desc")
    List<ConnectionAccessGrant> findAllByUsernameIgnoreCaseOrderByUpdatedAtDesc(String username);

    @Query("select g from ConnectionAccessGrant g where lower(g.username) = lower(?1) and g.connectionId in ?2")
    List<ConnectionAccessGrant> findAllByUsernameIgnoreCaseAndConnectionIdIn(String username, Collection<String> connectionIds);

    @Query("select g from ConnectionAccessGrant g where g.connectionId = ?1 order by lower(g.username) asc")
    List<ConnectionAccessGrant> findAllByConnectionIdOrderByUsernameAsc(String connectionId);

    @Query("select g from ConnectionAccessGrant g where g.connectionId = ?1 and lower(g.username) = lower(?2)")
    Optional<ConnectionAccessGrant> findByConnectionIdAndUsernameIgnoreCase(String connectionId, String username);

    void deleteByConnectionId(String connectionId);

    void deleteByConnectionIdAndUsernameIgnoreCase(String connectionId, String username);

    /**
     * Grants created or updated since a given timestamp for a connection.
     * Used by the daily digest's security section to surface new access grants.
     */
    @Query("select g from ConnectionAccessGrant g " +
           "where g.connectionId = ?1 and (g.createdAt >= ?2 or g.updatedAt >= ?2) " +
           "order by g.createdAt desc, g.updatedAt desc")
    List<ConnectionAccessGrant> findRecentForDigest(String connectionId, java.time.LocalDateTime since);
}
