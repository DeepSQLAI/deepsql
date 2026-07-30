package com.dbaagent.repository;

import com.dbaagent.model.Chat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChatRepository extends JpaRepository<Chat, String> {
    List<Chat> findByProjectIdOrderByLastMessageAtDesc(String projectId);
    List<Chat> findByConnectionIdOrderByLastMessageAtDesc(String connectionId);
    List<Chat> findByProjectIdAndConnectionIdOrderByLastMessageAtDesc(String projectId, String connectionId);
    List<Chat> findAllByOrderByLastMessageAtDesc();
    List<Chat> findByProjectIdAndOwnerUsernameIgnoreCaseOrderByLastMessageAtDesc(String projectId, String ownerUsername);
    List<Chat> findByConnectionIdAndOwnerUsernameIgnoreCaseOrderByLastMessageAtDesc(String connectionId, String ownerUsername);
    List<Chat> findAllByOwnerUsernameIgnoreCaseOrderByLastMessageAtDesc(String ownerUsername);
    java.util.Optional<Chat> findByIdAndOwnerUsernameIgnoreCase(String id, String ownerUsername);
}
