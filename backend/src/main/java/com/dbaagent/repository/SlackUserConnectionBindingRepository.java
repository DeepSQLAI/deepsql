package com.dbaagent.repository;

import com.dbaagent.model.SlackUserConnectionBinding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SlackUserConnectionBindingRepository extends JpaRepository<SlackUserConnectionBinding, Long> {
    Optional<SlackUserConnectionBinding> findByTeamIdAndSlackUserId(String teamId, String slackUserId);
    void deleteByTeamIdAndSlackUserId(String teamId, String slackUserId);
}
