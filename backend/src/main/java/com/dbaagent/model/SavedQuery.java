package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "saved_queries", indexes = {
    @Index(name = "idx_saved_queries_connection_id", columnList = "connectionId"),
    @Index(name = "idx_saved_queries_user_id", columnList = "userId"),
    @Index(name = "idx_saved_queries_is_favorite", columnList = "isFavorite"),
    @Index(name = "idx_saved_queries_created_at", columnList = "createdAt")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SavedQuery {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String connectionId;

    @Column
    private String userId;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String query;

    @Column(length = 500)
    private String tags;

    @Column(nullable = false)
    private Boolean isFavorite = false;

    @Column(length = 255)
    private String folder;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
