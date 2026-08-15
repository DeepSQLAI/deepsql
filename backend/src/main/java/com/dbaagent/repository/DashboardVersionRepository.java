package com.dbaagent.repository;

import com.dbaagent.model.DashboardVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface DashboardVersionRepository extends JpaRepository<DashboardVersion, UUID> {

    List<DashboardVersion> findByDashboardIdOrderByCreatedAtDesc(UUID dashboardId);

    void deleteByDashboardId(UUID dashboardId);
}
