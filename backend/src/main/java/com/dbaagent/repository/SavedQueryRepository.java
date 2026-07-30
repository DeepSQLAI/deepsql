package com.dbaagent.repository;

import com.dbaagent.model.SavedQuery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SavedQueryRepository extends JpaRepository<SavedQuery, UUID> {

    /**
     * Find all saved queries for a specific connection
     */
    List<SavedQuery> findByConnectionIdOrderByCreatedAtDesc(String connectionId);

    /**
     * Find all favorite queries for a specific connection
     */
    List<SavedQuery> findByConnectionIdAndIsFavoriteTrueOrderByCreatedAtDesc(String connectionId);

    /**
     * Find queries by folder
     */
    List<SavedQuery> findByConnectionIdAndFolderOrderByCreatedAtDesc(String connectionId, String folder);

    /**
     * Search queries by name or description
     */
    @Query("SELECT sq FROM SavedQuery sq WHERE sq.connectionId = :connectionId " +
           "AND (LOWER(sq.name) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
           "OR LOWER(sq.description) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
           "OR LOWER(sq.query) LIKE LOWER(CONCAT('%', :searchTerm, '%')))")
    List<SavedQuery> searchQueries(@Param("connectionId") String connectionId,
                                   @Param("searchTerm") String searchTerm);

    /**
     * Find queries by tags
     */
    @Query("SELECT sq FROM SavedQuery sq WHERE sq.connectionId = :connectionId " +
           "AND LOWER(sq.tags) LIKE LOWER(CONCAT('%', :tag, '%'))")
    List<SavedQuery> findByTag(@Param("connectionId") String connectionId,
                               @Param("tag") String tag);

    /**
     * Count queries in a folder
     */
    long countByConnectionIdAndFolder(String connectionId, String folder);

    /**
     * Get distinct folders for a connection
     */
    @Query("SELECT DISTINCT sq.folder FROM SavedQuery sq WHERE sq.connectionId = :connectionId " +
           "AND sq.folder IS NOT NULL ORDER BY sq.folder")
    List<String> findDistinctFoldersByConnectionId(@Param("connectionId") String connectionId);
}
