package com.dbaagent.repository;

import com.dbaagent.model.SlackUserLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SlackUserLinkRepository extends JpaRepository<SlackUserLink, Long> {
    Optional<SlackUserLink> findByTeamIdAndSlackUserId(String teamId, String slackUserId);
    Optional<SlackUserLink> findByTeamIdAndSlackUserIdAndLinkStatus(String teamId, String slackUserId, String linkStatus);

    /**
     * Find all linked Slack accounts for a DeepSQL username.
     * Returns only LINKED entries (not PENDING or REVOKED).
     */
    @Query("SELECT l FROM SlackUserLink l WHERE l.deepsqlUsername = :username AND l.linkStatus = 'LINKED'")
    List<SlackUserLink> findLinkedByDeepsqlUsername(@Param("username") String username);

    /**
     * Find all linked users (for seeding digest preferences).
     */
    @Query("SELECT DISTINCT l.deepsqlUsername FROM SlackUserLink l WHERE l.linkStatus = 'LINKED'")
    List<String> findAllLinkedDeepsqlUsernames();
}
