package com.dbaagent.repository;

import com.dbaagent.model.GoogleWorkspaceDomain;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GoogleWorkspaceDomainRepository extends JpaRepository<GoogleWorkspaceDomain, Long> {
    Optional<GoogleWorkspaceDomain> findByDomainIgnoreCase(String domain);
    List<GoogleWorkspaceDomain> findAllByEnabledTrueOrderByDomainAsc();
}
