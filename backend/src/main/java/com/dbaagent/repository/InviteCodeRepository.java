package com.dbaagent.repository;

import com.dbaagent.model.InviteCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface InviteCodeRepository extends JpaRepository<InviteCode, Long> {

    Optional<InviteCode> findByCode(String code);

    Optional<InviteCode> findByCodeAndActiveTrue(String code);

    boolean existsByCode(String code);

    List<InviteCode> findByActiveTrue();

    List<InviteCode> findByCreatedBy(String createdBy);

    @Query("SELECT ic FROM InviteCode ic WHERE ic.active = true " +
           "AND (ic.expiresAt IS NULL OR ic.expiresAt > CURRENT_TIMESTAMP) " +
           "AND (ic.maxUses IS NULL OR ic.currentUses < ic.maxUses)")
    List<InviteCode> findAllValid();

    @Query("SELECT ic FROM InviteCode ic ORDER BY ic.createdAt DESC")
    List<InviteCode> findAllOrderByCreatedAtDesc();
}
