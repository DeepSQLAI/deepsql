package com.dbaagent.repository;

import com.dbaagent.model.CliAuthorization;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CliAuthorizationRepository extends JpaRepository<CliAuthorization, Long> {
    Optional<CliAuthorization> findByAuthorizationId(String authorizationId);
}
