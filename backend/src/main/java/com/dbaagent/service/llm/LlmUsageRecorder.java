package com.dbaagent.service.llm;

import com.dbaagent.model.LlmUsage;
import com.dbaagent.model.LlmUsageRole;
import com.dbaagent.service.QueryActorContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Writes one {@link LlmUsage} row per model call.
 *
 * <p><strong>This class must never break the call it is measuring.</strong> Accounting is
 * strictly secondary to the feature: every public method swallows its own failures and
 * logs them. A full disk, a migration not yet applied, or a bug in this file must cost the
 * operator their spend numbers, never their chat.
 *
 * <p>Rows are written in their own transaction, through {@link LlmUsageWriter}. The caller
 * is often mid-transaction and may go on to fail — a chat turn that rolls back still spent
 * the tokens, and a ledger that discards exactly the calls that failed would understate
 * spend precisely where an operator is investigating.
 */
@Service
@Slf4j
public class LlmUsageRecorder {

    private final LlmUsageWriter writer;
    private final LlmPricingService pricing;

    public LlmUsageRecorder(LlmUsageWriter writer, LlmPricingService pricing) {
        this.writer = writer;
        this.pricing = pricing;
    }

    /** A completed call, ready to be priced and stored. */
    public record Call(
            LlmUsageRole role,
            String providerId,
            String model,
            long promptTokens,
            long completionTokens,
            long totalTokens,
            long cachedPromptTokens,
            boolean estimated,
            long latencyMs,
            boolean succeeded,
            String errorCategory) {}

    public void record(Call call) {
        try {
            writer.write(build(call));
        } catch (RuntimeException e) {
            log.warn("Could not record LLM usage for {} {}; the call itself was unaffected",
                    call.role(), call.model(), e);
        }
    }

    private LlmUsage build(Call call) {
        LlmUsage row = new LlmUsage();
        row.setRoleEnum(call.role());
        row.setProviderId(call.providerId());
        row.setModel(call.model());
        row.setFeature(LlmUsageContext.currentFeature());
        row.setConnectionId(LlmUsageContext.currentConnectionId());
        row.setUsername(resolveUsername());
        row.setPromptTokens(Math.max(0, call.promptTokens()));
        row.setCompletionTokens(Math.max(0, call.completionTokens()));
        row.setCachedPromptTokens(Math.max(0, call.cachedPromptTokens()));

        // Providers vary on whether total is reported; derive it when it is missing rather
        // than storing a zero that would silently drop the call out of every token sum.
        long total = call.totalTokens() > 0
                ? call.totalTokens()
                : row.getPromptTokens() + row.getCompletionTokens();
        row.setTotalTokens(total);

        row.setEstimated(call.estimated());
        row.setLatencyMs(call.latencyMs());
        row.setSucceeded(call.succeeded());
        row.setErrorCategory(call.errorCategory());
        row.setEstimatedCostUsd(price(call, row));
        return row;
    }

    private BigDecimal price(Call call, LlmUsage row) {
        return pricing.estimateCost(
                call.model(),
                row.getPromptTokens(),
                row.getCompletionTokens(),
                row.getCachedPromptTokens()).orElse(null);
    }

    /**
     * Who to bill. The SQL actor context wins over the security context because it is
     * already the product's answer to "who is really acting" — under an admin's View as,
     * the security principal is the admin while the actor is the target user, and usage
     * belongs to whoever the work was done for.
     *
     * <p>Null for background work with no actor at all, which is a real state (scheduled
     * alert evaluation, boot-time indexing) and not an error.
     */
    private String resolveUsername() {
        String actor = QueryActorContextHolder.currentUsername();
        if (actor != null && !actor.isBlank()) {
            return actor;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        String name = auth.getName();
        return (name == null || name.isBlank() || "anonymousUser".equals(name)) ? null : name;
    }
}
