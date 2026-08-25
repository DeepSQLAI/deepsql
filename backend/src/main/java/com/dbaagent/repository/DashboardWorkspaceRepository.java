package com.dbaagent.repository;

import com.dbaagent.model.DashboardWorkspace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DashboardWorkspaceRepository extends JpaRepository<DashboardWorkspace, UUID> {

    List<DashboardWorkspace> findByConnectionIdOrderByNameAsc(String connectionId);

    Optional<DashboardWorkspace> findByConnectionIdAndNameIgnoreCase(String connectionId, String name);

    void deleteByConnectionId(String connectionId);
}
