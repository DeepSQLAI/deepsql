package com.dbaagent.repository.brain;

import com.dbaagent.model.brain.BrainScoreSnapshot;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BrainScoreSnapshotRepository extends JpaRepository<BrainScoreSnapshot, String> {
    Optional<BrainScoreSnapshot> findTopByConnectionIdOrderByCreatedAtDesc(String connectionId);

    List<BrainScoreSnapshot> findByConnectionIdOrderByCreatedAtDesc(
        String connectionId,
        Pageable pageable
    );
}
