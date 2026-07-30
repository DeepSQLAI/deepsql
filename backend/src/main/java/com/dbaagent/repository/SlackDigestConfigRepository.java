package com.dbaagent.repository;

import com.dbaagent.model.SlackDigestConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SlackDigestConfigRepository extends JpaRepository<SlackDigestConfig, Long> {
}
