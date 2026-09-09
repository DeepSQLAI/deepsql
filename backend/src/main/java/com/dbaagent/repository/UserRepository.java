package com.dbaagent.repository;

import com.dbaagent.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.List;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    Optional<User> findByUsernameIgnoreCase(String username);
    Optional<User> findByEmailIgnoreCase(String email);
    boolean existsByEmailIgnoreCase(String email);
    List<User> findAllByAccountStatus(String accountStatus);

    /** How many users hold this role code. Guards deletion of a custom role in use. */
    long countByRoleIgnoreCase(String role);
}
