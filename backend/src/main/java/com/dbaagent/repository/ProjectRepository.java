package com.dbaagent.repository;

import com.dbaagent.model.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProjectRepository extends JpaRepository<Project, String> {
    List<Project> findByConnectionIdOrderByUpdatedAtDesc(String connectionId);
    List<Project> findAllByOrderByUpdatedAtDesc();
}
