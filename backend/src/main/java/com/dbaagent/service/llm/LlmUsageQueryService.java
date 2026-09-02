package com.dbaagent.service.llm;

import com.dbaagent.dto.LlmUsageDailyPoint;
import com.dbaagent.dto.LlmUsageGroup;
import com.dbaagent.dto.LlmUsageTotals;
import com.dbaagent.model.LlmUsage;
import com.dbaagent.repository.LlmUsageRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Read side of LLM accounting. */
@Service
@Transactional(readOnly = true)
public class LlmUsageQueryService {

    /** Caps the reporting window so a hand-edited query string cannot scan the whole table. */
    private static final int MAX_WINDOW_DAYS = 365;
    private static final int MAX_PAGE_SIZE = 200;

    private final LlmUsageRepository repository;
    private final LlmPricingService pricing;

    public LlmUsageQueryService(LlmUsageRepository repository, LlmPricingService pricing) {
        this.repository = repository;
        this.pricing = pricing;
    }

    public record Summary(
            int windowDays,
            LlmUsageTotals totals,
            List<LlmUsageGroup> byFeature,
            List<LlmUsageGroup> byUser,
            List<LlmUsageGroup> byModel,
            List<LlmUsageDailyPoint> daily,
            List<String> unpricedModels) {}

    public Summary summary(int requestedDays) {
        int days = clampDays(requestedDays);
        LocalDateTime since = LocalDateTime.now().minusDays(days);

        LlmUsageTotals totals = repository.totalsSince(since);
        return new Summary(
                days,
                totals == null ? LlmUsageTotals.empty() : totals,
                repository.byFeatureSince(since),
                repository.byUserSince(since),
                repository.byModelSince(since),
                repository.dailySince(since),
                repository.unpricedModelsSince(since));
    }

    public Page<LlmUsage> recent(int requestedDays, int page, int size) {
        LocalDateTime since = LocalDateTime.now().minusDays(clampDays(requestedDays));
        int bounded = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return repository.findByCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
                since, PageRequest.of(Math.max(page, 0), bounded));
    }

    /** One editable row in the pricing table. */
    public record PricingRow(
            String model,
            java.math.BigDecimal inputPer1m,
            java.math.BigDecimal outputPer1m,
            java.math.BigDecimal cachedInputPer1m,
            boolean priced,
            boolean seenInUsage) {}

    /**
     * Every model worth pricing: those the ledger has recorded, plus any model that has a
     * rate configured but has not been called (so a rate set ahead of a rollout, or left
     * behind by a model that was retired, is still visible and removable).
     *
     * <p>Unpriced models sort first — they are the ones costing money the totals do not
     * show, and the whole point of this screen is to close that gap.
     */
    public List<PricingRow> pricing() {
        List<String> seen = repository.distinctModels();
        Set<String> models = new LinkedHashSet<>(seen);
        models.addAll(pricing.configuredModels());

        return models.stream()
                .map(model -> {
                    LlmPricingService.ModelRates rates = pricing.ratesFor(model);
                    return new PricingRow(
                            rates.model(),
                            rates.inputPer1m(),
                            rates.outputPer1m(),
                            rates.cachedInputPer1m(),
                            rates.isPriced(),
                            seen.contains(model));
                })
                .sorted(Comparator.comparing(PricingRow::priced)
                        .thenComparing(PricingRow::model))
                .toList();
    }

    @Transactional
    public int purgeOlderThan(int days) {
        return repository.deleteByCreatedAtBefore(
                LocalDateTime.now().minusDays(Math.max(days, 1)));
    }

    private int clampDays(int days) {
        return Math.min(Math.max(days, 1), MAX_WINDOW_DAYS);
    }
}
