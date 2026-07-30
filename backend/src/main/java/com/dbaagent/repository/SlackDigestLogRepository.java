package com.dbaagent.repository;

import com.dbaagent.model.SlackDigestLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SlackDigestLogRepository extends JpaRepository<SlackDigestLog, Long> {

    Page<SlackDigestLog> findAllByChannelIdIsNullOrderBySentAtDesc(Pageable pageable);

    Page<SlackDigestLog> findByConnectionIdAndChannelIdIsNullOrderBySentAtDesc(String connectionId, Pageable pageable);

    Optional<SlackDigestLog> findTopByConnectionIdAndChannelIdIsNullOrderBySentAtDesc(String connectionId);

    long countByConnectionIdAndChannelIdIsNull(String connectionId);
}
