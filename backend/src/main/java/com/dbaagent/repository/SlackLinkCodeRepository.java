package com.dbaagent.repository;

import com.dbaagent.model.SlackLinkCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SlackLinkCodeRepository extends JpaRepository<SlackLinkCode, Long> {
    List<SlackLinkCode> findAllByDeepsqlUsernameOrderByCreatedAtDesc(String deepsqlUsername);
    Optional<SlackLinkCode> findFirstByDeepsqlUsernameOrderByCreatedAtDesc(String deepsqlUsername);
}
