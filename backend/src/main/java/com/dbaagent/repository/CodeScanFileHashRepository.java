package com.dbaagent.repository;

import com.dbaagent.model.code.CodeScanFileHash;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CodeScanFileHashRepository
    extends JpaRepository<CodeScanFileHash, CodeScanFileHash.PK> {

    List<CodeScanFileHash> findBySourceId(String sourceId);

    void deleteBySourceId(String sourceId);
}
