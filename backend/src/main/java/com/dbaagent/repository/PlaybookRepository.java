package com.dbaagent.repository;

import com.dbaagent.model.Playbook;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PlaybookRepository extends JpaRepository<Playbook, String> {
    List<Playbook> findByIsActiveTrue();
    List<Playbook> findByCategory(String category);
    List<Playbook> findByDbTypeOrDbType(String dbType, String all);
    List<Playbook> findByIsSystemTrue();
    List<Playbook> findByScheduleCronIsNotNull();
}
