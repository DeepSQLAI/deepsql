package com.dbaagent.repository;

import com.dbaagent.model.QueryOptimizationCache;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface QueryOptimizationCacheRepository extends JpaRepository<QueryOptimizationCache, String> {

    /**
     * Find cached optimization by connection and query fingerprint.
     */
    Optional<QueryOptimizationCache> findByConnectionIdAndQueryFingerprint(
        String connectionId, String queryFingerprint);

    /**
     * Find all cached optimizations for a connection.
     */
    List<QueryOptimizationCache> findByConnectionIdOrderByUpdatedAtDesc(String connectionId);

    /**
     * Find cached optimizations for multiple query fingerprints in a single query.
     */
    @Query("SELECT q FROM QueryOptimizationCache q WHERE q.connectionId = :connectionId AND q.queryFingerprint IN :fingerprints")
    List<QueryOptimizationCache> findByConnectionIdAndFingerprintsIn(
        @Param("connectionId") String connectionId,
        @Param("fingerprints") List<String> fingerprints);

    /**
     * Delete all cached optimizations for a connection.
     */
    @Modifying
    @Query("DELETE FROM QueryOptimizationCache q WHERE q.connectionId = :connectionId")
    void deleteByConnectionId(@Param("connectionId") String connectionId);

    /**
     * Delete old cached optimizations (cleanup).
     */
    @Modifying
    @Query("DELETE FROM QueryOptimizationCache q WHERE q.updatedAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);

    /**
     * Count cached optimizations for a connection.
     */
    long countByConnectionId(String connectionId);

    /**
     * Check if a cached optimization exists for a query.
     */
    boolean existsByConnectionIdAndQueryFingerprint(String connectionId, String queryFingerprint);
}
