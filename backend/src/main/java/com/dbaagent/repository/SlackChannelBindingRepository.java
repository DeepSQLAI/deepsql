package com.dbaagent.repository;

import com.dbaagent.model.SlackChannelBinding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SlackChannelBindingRepository extends JpaRepository<SlackChannelBinding, Long> {
    Optional<SlackChannelBinding> findByTeamIdAndChannelId(String teamId, String channelId);
    void deleteByTeamIdAndChannelId(String teamId, String channelId);
}
