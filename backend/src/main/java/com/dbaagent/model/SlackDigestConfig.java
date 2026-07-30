package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "slack_digest_config")
@Data
@NoArgsConstructor
public class SlackDigestConfig {

    @Id
    private Long id = 1L;

    @Column(nullable = false)
    private String cronExpression = "0 0 9 * * *";

    @Column(nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();
}
