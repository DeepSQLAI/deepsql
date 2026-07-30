package com.dbaagent.repository;

import com.dbaagent.model.SavedDashboard;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SavedDashboardRepository extends JpaRepository<SavedDashboard, UUID> {

    /**
     * Find all saved dashboards for a specific connection
     */
    List<SavedDashboard> findByConnectionIdOrderByCreatedAtDesc(String connectionId);

    /**
     * Resolve a public share token to its dashboard (used by the public endpoints).
     */
    Optional<SavedDashboard> findByShareToken(String shareToken);

    /**
     * Find all favorite dashboards for a specific connection
     */
    List<SavedDashboard> findByConnectionIdAndIsFavoriteTrueOrderByCreatedAtDesc(String connectionId);

    /**
     * Find dashboards by folder
     */
    List<SavedDashboard> findByConnectionIdAndFolderOrderByCreatedAtDesc(String connectionId, String folder);

    /**
     * Search dashboards by name or description
     */
    @Query("SELECT sd FROM SavedDashboard sd WHERE sd.connectionId = :connectionId " +
           "AND (LOWER(sd.name) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
           "OR LOWER(sd.description) LIKE LOWER(CONCAT('%', :searchTerm, '%')))")
    List<SavedDashboard> searchDashboards(@Param("connectionId") String connectionId,
                                         @Param("searchTerm") String searchTerm);

    /**
     * Find dashboards by tags
     */
    @Query("SELECT sd FROM SavedDashboard sd WHERE sd.connectionId = :connectionId " +
           "AND LOWER(sd.tags) LIKE LOWER(CONCAT('%', :tag, '%'))")
    List<SavedDashboard> findByTag(@Param("connectionId") String connectionId,
                                   @Param("tag") String tag);

    /**
     * Count dashboards in a folder
     */
    long countByConnectionIdAndFolder(String connectionId, String folder);

    /**
     * Get distinct folders for a connection
     */
    @Query("SELECT DISTINCT sd.folder FROM SavedDashboard sd WHERE sd.connectionId = :connectionId " +
           "AND sd.folder IS NOT NULL ORDER BY sd.folder")
    List<String> findDistinctFoldersByConnectionId(@Param("connectionId") String connectionId);
}
