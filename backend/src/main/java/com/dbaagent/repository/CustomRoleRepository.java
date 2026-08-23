package com.dbaagent.repository;

import com.dbaagent.model.CustomRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CustomRoleRepository extends JpaRepository<CustomRole, Long> {

    Optional<CustomRole> findByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCase(String code);

    List<CustomRole> findAllByOrderByNameAsc();
}
