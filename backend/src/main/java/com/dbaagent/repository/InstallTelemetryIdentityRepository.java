package com.dbaagent.repository;

import com.dbaagent.model.InstallTelemetryIdentity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface InstallTelemetryIdentityRepository
        extends JpaRepository<InstallTelemetryIdentity, Integer> {
}
