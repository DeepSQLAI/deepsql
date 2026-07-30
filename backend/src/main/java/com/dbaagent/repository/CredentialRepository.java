package com.dbaagent.repository;

import com.dbaagent.model.DatabaseConnection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CredentialRepository extends JpaRepository<DatabaseConnection, String> {
    List<DatabaseConnection> findAllByOrderByCreatedAtDesc();
    List<DatabaseConnection> findAllByOwnerUsernameOrderByCreatedAtDesc(String ownerUsername);
    Optional<DatabaseConnection> findByConnectionName(String connectionName);

    /**
     * Get all connection IDs for scheduled background tasks.
     */
    @Query("SELECT c.id FROM DatabaseConnection c")
    List<String> findAllIds();
}


