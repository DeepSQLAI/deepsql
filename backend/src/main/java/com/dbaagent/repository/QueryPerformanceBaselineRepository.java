package com.dbaagent.repository;

import com.dbaagent.model.QueryPerformanceBaseline;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface QueryPerformanceBaselineRepository extends JpaRepository<QueryPerformanceBaseline, Long> {

    Optional<QueryPerformanceBaseline> findByConnectionIdAndQueryHash(String connectionId, String queryHash);

    List<QueryPerformanceBaseline> findByConnectionIdOrderByLastSeenDesc(String connectionId);

    void deleteByConnectionIdAndQueryHash(String connectionId, String queryHash);

    void deleteByConnectionId(String connectionId);
}
