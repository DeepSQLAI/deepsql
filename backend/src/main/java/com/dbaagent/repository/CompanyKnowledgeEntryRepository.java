package com.dbaagent.repository;

import com.dbaagent.model.CompanyKnowledgeEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface CompanyKnowledgeEntryRepository extends JpaRepository<CompanyKnowledgeEntry, String> {

    @Query("""
        SELECT e
        FROM CompanyKnowledgeEntry e
        WHERE e.connectionId = :connectionId
        ORDER BY COALESCE(e.updatedAt, e.createdAt) DESC, e.title ASC
        """)
    List<CompanyKnowledgeEntry> findByConnectionIdOrderByRecency(@Param("connectionId") String connectionId);

    List<CompanyKnowledgeEntry> findByConnectionId(String connectionId);

    long countByConnectionId(String connectionId);

    @Query("""
        SELECT MAX(COALESCE(e.updatedAt, e.createdAt))
        FROM CompanyKnowledgeEntry e
        WHERE e.connectionId = :connectionId
        """)
    LocalDateTime findLatestTouchedAt(@Param("connectionId") String connectionId);

    /**
     * Native JSONB recall query for the "link-floor" pass — returns entries whose
     * {@code linked_tables} OR {@code linked_columns} reference any of the focus
     * tables. Targeted: does NOT load all entries.
     *
     * <p>Match rules (case-insensitive, scoped per element):
     * <ul>
     *   <li>{@code linked_tables} element equals a focus token (bare-bare or qualified-qualified)</li>
     *   <li>{@code linked_tables} element ends in {@code .<focus>} (qualified entry, bare focus)</li>
     *   <li>{@code linked_columns} element contains {@code <focus>.} as a dot-bounded segment
     *       (so {@code idb_database.HOTEL_PRICING.amount} matches focus {@code HOTEL_PRICING})</li>
     * </ul>
     *
     * <p>{@code jsonb_array_elements_text} is used instead of {@code jsonb_exists_any} because
     * the latter only supports exact token equality and can't anchor the suffix/segment patterns
     * we need for bare-vs-qualified mismatch tolerance.
     */
    @Query(value = """
        SELECT *
        FROM company_knowledge_entry e
        WHERE e.connection_id = :connectionId
          AND (
            EXISTS (
              SELECT 1
              FROM jsonb_array_elements_text(COALESCE(e.linked_tables, '[]'::jsonb)) t,
                   unnest(CAST(:focusTables AS text[])) f
              WHERE LOWER(t) = LOWER(f)
                 OR LOWER(t) LIKE LOWER('%.' || f)
            )
            OR EXISTS (
              SELECT 1
              FROM jsonb_array_elements_text(COALESCE(e.linked_columns, '[]'::jsonb)) c,
                   unnest(CAST(:focusTables AS text[])) f
              WHERE LOWER(c) LIKE LOWER('%.' || f || '.%')
                 OR LOWER(c) LIKE LOWER(f || '.%')
            )
          )
        ORDER BY COALESCE(e.updated_at, e.created_at) DESC
        """, nativeQuery = true)
    List<CompanyKnowledgeEntry> findByLinkedTablesAny(
        @Param("connectionId") String connectionId,
        @Param("focusTables") String[] focusTables
    );
}
