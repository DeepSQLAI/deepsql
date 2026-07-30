package com.dbaagent.repository;

import com.dbaagent.model.SlackThreadSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SlackThreadSessionRepository extends JpaRepository<SlackThreadSession, Long> {
    Optional<SlackThreadSession> findByTeamIdAndChannelIdAndRootThreadTs(String teamId, String channelId, String rootThreadTs);
    void deleteByTeamIdAndSlackUserId(String teamId, String slackUserId);
}
