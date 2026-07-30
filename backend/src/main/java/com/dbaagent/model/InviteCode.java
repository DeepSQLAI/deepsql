package com.dbaagent.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Column;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table(name = "invite_codes")
@Data
public class InviteCode {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 20)
    private String code;

    @Column(name = "max_uses")
    private Integer maxUses = 1;

    @Column(name = "current_uses")
    private Integer currentUses = 0;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(columnDefinition = "TEXT")
    private String note;

    @Column
    private Boolean active = true;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    /**
     * Check if this invite code is valid for use.
     * A code is valid if:
     * - It is active
     * - It has not expired
     * - It has not reached its maximum uses
     */
    public boolean isValid() {
        if (!Boolean.TRUE.equals(active)) {
            return false;
        }
        if (expiresAt != null && LocalDateTime.now().isAfter(expiresAt)) {
            return false;
        }
        if (maxUses != null && currentUses >= maxUses) {
            return false;
        }
        return true;
    }

    /**
     * Increment the usage count.
     * Returns true if the code was successfully used, false if it has reached max uses.
     */
    public boolean use() {
        if (!isValid()) {
            return false;
        }
        this.currentUses = (this.currentUses == null ? 0 : this.currentUses) + 1;
        return true;
    }

    /**
     * Get remaining uses for this code.
     * Returns null for unlimited use codes (maxUses is null).
     */
    public Integer getRemainingUses() {
        if (maxUses == null) {
            return null;
        }
        return Math.max(0, maxUses - (currentUses == null ? 0 : currentUses));
    }
}
