package com.dbaagent.repository;

import com.dbaagent.model.SchemaClassification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SchemaClassificationRepository extends JpaRepository<SchemaClassification, String> {

    /**
     * Get the latest schema classification for a connection.
     */
    @Query("SELECT sc FROM SchemaClassification sc WHERE sc.connectionId = :connectionId " +
           "ORDER BY sc.classifiedAt DESC LIMIT 1")
    Optional<SchemaClassification> findLatestByConnectionId(String connectionId);

    /**
     * Get schema classification history for trend analysis.
     */
    List<SchemaClassification> findByConnectionIdOrderByClassifiedAtDesc(String connectionId);

    /**
     * Get schema classification history with limit.
     */
    @Query("SELECT sc FROM SchemaClassification sc WHERE sc.connectionId = :connectionId " +
           "ORDER BY sc.classifiedAt DESC LIMIT :limit")
    List<SchemaClassification> findTopByConnectionIdOrderByClassifiedAtDesc(String connectionId, int limit);
}
