package com.dbaagent.repository;

import com.dbaagent.model.SemanticTableModel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface SemanticTableModelRepository extends JpaRepository<SemanticTableModel, String> {

    List<SemanticTableModel> findByConnectionIdOrderByTableNameAsc(String connectionId);

    List<SemanticTableModel> findByConnectionIdAndTableNameInOrderByTableNameAsc(
        String connectionId, Collection<String> tableNames);

    long countByConnectionId(String connectionId);

    @Modifying
    @Query("DELETE FROM SemanticTableModel s WHERE s.connectionId = :connectionId")
    void deleteByConnectionId(@Param("connectionId") String connectionId);
}
