package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "slack_digest_log")
@Data
@NoArgsConstructor
public class SlackDigestLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String connectionId;
    private String connectionName;
    private String channelId;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    private String headline;

    @Column(nullable = false)
    private LocalDateTime sentAt = LocalDateTime.now();

    @Column(nullable = false)
    private String status = "SENT";

    @Column(columnDefinition = "TEXT")
    private String errorMessage;
}
