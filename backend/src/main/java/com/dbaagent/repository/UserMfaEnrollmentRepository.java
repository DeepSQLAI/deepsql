package com.dbaagent.repository;

import com.dbaagent.model.UserMfaEnrollment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserMfaEnrollmentRepository extends JpaRepository<UserMfaEnrollment, Long> {
    Optional<UserMfaEnrollment> findByUserId(Long userId);
}
