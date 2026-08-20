package com.dbaagent.repository;

import com.dbaagent.model.ConnectionChatAccessPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConnectionChatAccessPolicyRepository extends JpaRepository<ConnectionChatAccessPolicy, Long> {
    Optional<ConnectionChatAccessPolicy> findByConnectionIdAndUsernameIgnoreCase(String connectionId, String username);
    List<ConnectionChatAccessPolicy> findAllByUsernameIgnoreCaseOrderByUpdatedAtDesc(String username);
    boolean existsByConnectionIdAndActiveTrue(String connectionId);
    void deleteByConnectionId(String connectionId);
}
