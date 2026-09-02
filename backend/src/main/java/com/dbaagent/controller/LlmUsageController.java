package com.dbaagent.controller;

import com.dbaagent.model.LlmUsage;
import com.dbaagent.service.llm.LlmPricingService;
import com.dbaagent.service.llm.LlmUsageQueryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * LLM spend reporting.
 *
 * <p>Admin-only, and not scoped to a connection: usage spans every connection and every
 * user, so there is no connection ACL that could authorize it. That makes this one of the
 * cases {@code CLAUDE.md} calls out — an endpoint with no connection scope at all is
 * admin-only, enforced with {@code @PreAuthorize} rather than an access-control assert.
 */
@Slf4j
@RestController
@RequestMapping("/admin/llm-usage")
@PreAuthorize("hasRole('ADMIN')")
public class LlmUsageController {

    private final LlmUsageQueryService usageQueryService;
    private final LlmPricingService pricingService;

    public LlmUsageController(LlmUsageQueryService usageQueryService,
                              LlmPricingService pricingService) {
        this.usageQueryService = usageQueryService;
        this.pricingService = pricingService;
    }

    @GetMapping("/summary")
    public ResponseEntity<LlmUsageQueryService.Summary> summary(
            @RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(usageQueryService.summary(days));
    }

    @GetMapping("/recent")
    public ResponseEntity<Page<LlmUsage>> recent(
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(usageQueryService.recent(days, page, size));
    }

    @GetMapping("/pricing")
    public ResponseEntity<List<LlmUsageQueryService.PricingRow>> pricing() {
        return ResponseEntity.ok(usageQueryService.pricing());
    }

    /**
     * Rates for one model, in USD per 1M tokens. A null rate field clears that rate.
     *
     * <p>{@code model} is optional here and only used when the path cannot carry the name
     * — see {@link #updatePricing}.
     */
    public record PricingUpdate(
            String model,
            BigDecimal inputPer1m,
            BigDecimal outputPer1m,
            BigDecimal cachedInputPer1m) {}

    /**
     * The model is normally a path variable, so the URL identifies what is being edited,
     * and {@code :.+} keeps a dotted name like {@code gpt-5.4} intact.
     *
     * <p>A name containing a slash cannot travel in the path at all, even percent-encoded:
     * Spring Security's default {@code StrictHttpFirewall} rejects {@code %2F} with a bare
     * 400 before any controller runs — verified against this deployment, and it rejects
     * such a URL on every endpoint, not just this one. Self-hosted model ids are routinely
     * of the form {@code meta-llama/Llama-3-8b}, so the body may carry {@code model}
     * instead and the caller PUTs to {@link #PRICING_BODY_MODEL}. Relaxing the firewall
     * would be the wrong trade: it is a platform-wide security control, and this is a
     * naming convenience.
     */
    static final String PRICING_BODY_MODEL = "_";

    @PutMapping({"/pricing/{model:.+}", "/pricing"})
    public ResponseEntity<?> updatePricing(
            @PathVariable(required = false) String model, @RequestBody PricingUpdate update) {
        try {
            String target = PRICING_BODY_MODEL.equals(model) || model == null || model.isBlank()
                    ? update.model()
                    : model;
            LlmPricingService.ModelRates saved = pricingService.updateRates(
                    target, update.inputPer1m(), update.outputPer1m(), update.cachedInputPer1m());
            log.info("Updated LLM pricing for model {}", saved.model());
            return ResponseEntity.ok(saved);
        } catch (IllegalArgumentException e) {
            // A bad rate is the operator's typo, not a server fault, and the message names
            // what to correct.
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (RuntimeException e) {
            // Anything else — the config store being unreachable, say — must still come
            // back with a `message`. Spring's default 500 body has none, and the client
            // then has nothing to display: verified by taking system_config away, where
            // the save failed and the UI showed the user nothing at all.
            log.error("Could not save LLM pricing for model {}", model, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Could not save the rate: " + e.getMessage()));
        }
    }

    @DeleteMapping("/purge")
    public ResponseEntity<Map<String, Object>> purge(@RequestParam int olderThanDays) {
        int deleted = usageQueryService.purgeOlderThan(olderThanDays);
        log.info("Purged {} LLM usage rows older than {} days", deleted, olderThanDays);
        return ResponseEntity.ok(Map.of("deleted", deleted));
    }
}
