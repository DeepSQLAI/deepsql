package com.dbaagent.repository;

import com.dbaagent.model.code.CodeScanSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CodeScanSourceRepository extends JpaRepository<CodeScanSource, String> {

    List<CodeScanSource> findByConnectionIdAndActiveTrueOrderByCreatedAtDesc(String connectionId);

    List<CodeScanSource> findByActiveTrueAndScheduleCronIsNotNull();
}
