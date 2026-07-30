package com.dbaagent.repository;

import com.dbaagent.model.ConnectionAnalyticsConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ConnectionAnalyticsConfigRepository
    extends JpaRepository<ConnectionAnalyticsConfig, String> {
    // Primary key is connectionId — findById(connectionId) is all we need.
}
