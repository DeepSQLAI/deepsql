package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One metered LLM call.
 *
 * <p>Rows are written from the two provider funnels ({@code RefreshableChatModel} and
 * {@code EmbeddingService}), never from feature code, so a new caller is accounted for
 * without touching this table.
 *
 * <p>{@code estimated} is load-bearing rather than decorative. Chat providers return real
 * token counts in the response; the embedding provider API returns vectors only, so those
 * counts are derived from input length. Both land in the same columns because operators
 * want one spend total, but a row that says 4,000 tokens has to be distinguishable from a
 * row that guesses 4,000 — otherwise a reconciliation against the vendor invoice has no
 * way to know which half of the ledger to trust.
 */
@Entity
@Table(name = "llm_usage")
@Data
public class LlmUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16)
    private String role;

    @Column(name = "provider_id", nullable = false)
    private String providerId;

    @Column(nullable = false)
    private String model;

    /** The feature that made the call, e.g. {@code chat}, {@code dashboard-generate}. */
    @Column(nullable = false, length = 64)
    private String feature;

    /** Null for background work with no human actor (scheduled jobs, boot-time indexing). */
    @Column
    private String username;

    @Column(name = "connection_id")
    private String connectionId;

    @Column(name = "prompt_tokens", nullable = false)
    private long promptTokens;

    @Column(name = "completion_tokens", nullable = false)
    private long completionTokens;

    @Column(name = "total_tokens", nullable = false)
    private long totalTokens;

    /**
     * Cached prompt tokens, when the provider reports them. Billed well below fresh input
     * by every provider that offers it, so cost is computed against
     * {@code promptTokens - cachedPromptTokens} rather than the raw prompt count.
     */
    @Column(name = "cached_prompt_tokens", nullable = false)
    private long cachedPromptTokens;

    /**
     * Cost in USD at the rates configured when the row was written, or null when the
     * model has no configured rate.
     *
     * <p>Null is deliberately not zero. An unpriced model is an operator gap to surface,
     * and writing 0.00 would silently understate spend in every total that sums this
     * column.
     */
    @Column(name = "estimated_cost_usd", precision = 12, scale = 6)
    private BigDecimal estimatedCostUsd;

    /** True when token counts are derived locally rather than reported by the provider. */
    @Column(nullable = false)
    private boolean estimated;

    @Column(name = "latency_ms")
    private Long latencyMs;

    /** False for a call that threw; such rows still cost tokens upstream sometimes. */
    @Column(nullable = false)
    private boolean succeeded;

    @Column(name = "error_category", length = 64)
    private String errorCategory;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Transient
    public LlmUsageRole getRoleEnum() {
        return role == null ? null : LlmUsageRole.valueOf(role);
    }

    public void setRoleEnum(LlmUsageRole value) {
        this.role = value != null ? value.name() : null;
    }
}
