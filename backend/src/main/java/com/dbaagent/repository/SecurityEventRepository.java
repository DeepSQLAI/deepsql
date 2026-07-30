package com.dbaagent.repository;

import com.dbaagent.model.SecurityEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface SecurityEventRepository extends JpaRepository<SecurityEvent, Long>, JpaSpecificationExecutor<SecurityEvent> {
    long countByEmailIgnoreCaseAndEventTypeAndCreatedAtAfter(String email, String eventType, LocalDateTime createdAt);
    long countByClientIpAndEventTypeAndCreatedAtAfter(String clientIp, String eventType, LocalDateTime createdAt);

    @Modifying
    @Query("DELETE FROM SecurityEvent e WHERE e.createdAt < :before")
    int deleteByCreatedAtBefore(@Param("before") LocalDateTime before);
}
