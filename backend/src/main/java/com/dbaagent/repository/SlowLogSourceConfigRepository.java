package com.dbaagent.repository;

import com.dbaagent.model.SlowLogSourceConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SlowLogSourceConfigRepository extends JpaRepository<SlowLogSourceConfig, String> {
    Optional<SlowLogSourceConfig> findByConnectionId(String connectionId);
    void deleteByConnectionId(String connectionId);
}
