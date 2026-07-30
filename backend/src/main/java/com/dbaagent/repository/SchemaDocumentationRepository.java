package com.dbaagent.repository;

import com.dbaagent.model.DocumentationSource;
import com.dbaagent.model.SchemaDocumentation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;

@Repository
public interface SchemaDocumentationRepository extends JpaRepository<SchemaDocumentation, String> {

    List<SchemaDocumentation> findByConnectionId(String connectionId);

    List<SchemaDocumentation> findByConnectionIdAndObjectType(
        String connectionId,
        SchemaDocumentation.DocumentationType objectType
    );

    Optional<SchemaDocumentation> findByConnectionIdAndObjectTypeAndObjectName(
        String connectionId,
        SchemaDocumentation.DocumentationType objectType,
        String objectName
    );

    List<SchemaDocumentation> findByConnectionIdAndSource(String connectionId, DocumentationSource source);

    long countByConnectionId(String connectionId);

    long countByConnectionIdAndSource(String connectionId, DocumentationSource source);

    List<SchemaDocumentation> findByConnectionIdAndParentObject(String connectionId, String parentObject);

    @Query("""
        SELECT MAX(COALESCE(d.updatedAt, d.createdAt))
        FROM SchemaDocumentation d
        WHERE d.connectionId = :connectionId
        """)
    LocalDateTime findLatestTouchedAt(@Param("connectionId") String connectionId);

    @Query("""
        SELECT COUNT(d)
        FROM SchemaDocumentation d
        WHERE d.connectionId = :connectionId
          AND d.businessTerms IS NOT NULL
          AND TRIM(d.businessTerms) <> ''
        """)
    long countWithBusinessTerms(@Param("connectionId") String connectionId);

    // Upsert support: find existing AI doc to update instead of creating duplicates
    Optional<SchemaDocumentation> findByConnectionIdAndObjectTypeAndObjectNameAndSource(
        String connectionId, SchemaDocumentation.DocumentationType objectType,
        String objectName, DocumentationSource source);

    Optional<SchemaDocumentation> findByConnectionIdAndObjectTypeAndObjectNameAndParentObjectAndSource(
        String connectionId, SchemaDocumentation.DocumentationType objectType,
        String objectName, String parentObject, DocumentationSource source);

    @Query("SELECT d FROM SchemaDocumentation d WHERE d.connectionId = :connectionId " +
           "AND (LOWER(d.objectName) LIKE LOWER(CONCAT('%', :search, '%')) " +
           "OR LOWER(d.description) LIKE LOWER(CONCAT('%', :search, '%')) " +
           "OR LOWER(d.businessTerms) LIKE LOWER(CONCAT('%', :search, '%')))")
    List<SchemaDocumentation> searchDocumentation(
        @Param("connectionId") String connectionId,
        @Param("search") String search
    );
}
