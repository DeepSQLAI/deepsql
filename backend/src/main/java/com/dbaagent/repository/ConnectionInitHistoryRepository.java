package com.dbaagent.repository;

import com.dbaagent.model.ConnectionInitHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ConnectionInitHistoryRepository extends JpaRepository<ConnectionInitHistory, String> {
    List<ConnectionInitHistory> findByConnectionIdOrderByCreatedAtDesc(String connectionId);
}
