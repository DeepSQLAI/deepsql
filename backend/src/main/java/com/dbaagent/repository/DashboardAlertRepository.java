package com.dbaagent.repository;

import com.dbaagent.model.DashboardAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface DashboardAlertRepository extends JpaRepository<DashboardAlert, UUID> {

    List<DashboardAlert> findByDashboardIdOrderByCreatedAtDesc(UUID dashboardId);

    // Due = enabled AND (never checked OR its own interval has elapsed since the last
    // check). Computed in SQL rather than pulled into Java so a growing alert count
    // never means pulling every row into memory just to filter most of them out.
    // The now::timestamp cast is required — without it, Postgres can't resolve
    // whether the parameter or the (timestamp - interval) expression should drive
    // the comparison's type and rejects the query with "operator does not exist:
    // timestamp without time zone <= interval".
    @Query(value = "SELECT * FROM dashboard_alerts WHERE is_enabled = true "
        + "AND (last_checked_at IS NULL OR last_checked_at <= CAST(:now AS timestamp) - (check_interval_minutes * INTERVAL '1 minute'))",
        nativeQuery = true)
    List<DashboardAlert> findDue(LocalDateTime now);
}
