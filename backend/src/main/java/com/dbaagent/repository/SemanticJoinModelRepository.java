package com.dbaagent.repository;

import com.dbaagent.model.SemanticJoinModel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface SemanticJoinModelRepository extends JpaRepository<SemanticJoinModel, String> {

    List<SemanticJoinModel> findByConnectionIdOrderByPreferredDescConfidenceScoreDescSourceTableAsc(String connectionId);

    @Query("""
        SELECT s
        FROM SemanticJoinModel s
        WHERE s.connectionId = :connectionId
          AND (s.sourceTable IN :tableNames OR s.targetTable IN :tableNames)
        ORDER BY s.preferred DESC, s.confidenceScore DESC, s.sourceTable ASC, s.targetTable ASC
        """)
    List<SemanticJoinModel> findByConnectionIdAndTables(
        @Param("connectionId") String connectionId,
        @Param("tableNames") Collection<String> tableNames);

    long countByConnectionId(String connectionId);

    @Modifying
    @Query("DELETE FROM SemanticJoinModel s WHERE s.connectionId = :connectionId")
    void deleteByConnectionId(@Param("connectionId") String connectionId);
}
