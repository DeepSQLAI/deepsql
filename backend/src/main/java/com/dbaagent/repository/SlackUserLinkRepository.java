package com.dbaagent.repository;

import com.dbaagent.model.SlackUserLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SlackUserLinkRepository extends JpaRepository<SlackUserLink, Long> {
    Optional<SlackUserLink> findByTeamIdAndSlackUserId(String teamId, String slackUserId);
    Optional<SlackUserLink> findByTeamIdAndSlackUserIdAndLinkStatus(String teamId, String slackUserId, String linkStatus);
}
