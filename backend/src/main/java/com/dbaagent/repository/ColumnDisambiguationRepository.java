package com.dbaagent.repository;

import com.dbaagent.model.ColumnDisambiguation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ColumnDisambiguationRepository extends JpaRepository<ColumnDisambiguation, String> {
    List<ColumnDisambiguation> findByConnectionId(String connectionId);

    Optional<ColumnDisambiguation> findByConnectionIdAndColumnName(String connectionId, String columnName);
}
