package com.dbaagent.service.llm;

import com.dbaagent.repository.SystemConfigRepository;
import com.dbaagent.service.SystemConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

/**
 * Turns token counts into a USD estimate using rates held in {@code system_config}.
 *
 * <p>Rates live in configuration rather than in a constant table on purpose: vendors
 * reprice, self-hosters run their own models at their own cost, and an operator who
 * discovers their spend numbers are wrong must be able to correct them without waiting
 * for a release. Keys are
 *
 * <pre>
 *   llm.pricing.&lt;model&gt;.input-per-1m
 *   llm.pricing.&lt;model&gt;.output-per-1m
 *   llm.pricing.&lt;model&gt;.cached-input-per-1m   (optional; defaults to input rate)
 * </pre>
 *
 * <p>There are deliberately <strong>no built-in default prices</strong>. A stale bundled
 * price list is worse than none: it produces confident wrong totals that nobody thinks to
 * check, and this repo has already been bitten once by a credential default shipped in a
 * properties file. An unpriced model yields {@link Optional#empty()}, which the caller
 * stores as a null cost and the UI reports as unpriced.
 */
@Service
@Slf4j
public class LlmPricingService {

    private static final BigDecimal PER_MILLION = new BigDecimal("1000000");
    private static final int COST_SCALE = 6;

    static final String KEY_PREFIX = "llm.pricing.";
    static final String INPUT = "input-per-1m";
    static final String OUTPUT = "output-per-1m";
    static final String CACHED_INPUT = "cached-input-per-1m";

    private final SystemConfigService systemConfig;
    private final SystemConfigRepository configRepository;

    public LlmPricingService(SystemConfigService systemConfig,
                             SystemConfigRepository configRepository) {
        this.systemConfig = systemConfig;
        this.configRepository = configRepository;
    }

    /**
     * Cost for one call, or empty when the model has no configured input rate.
     *
     * <p>{@code cachedPromptTokens} is billed at the cached rate and the remainder of the
     * prompt at the full input rate. A provider that reports more cached tokens than
     * prompt tokens (or a caller that miscounts) would otherwise produce a negative
     * fresh-token count and undercharge, so the split is clamped at zero.
     */
    public Optional<BigDecimal> estimateCost(
            String model, long promptTokens, long completionTokens, long cachedPromptTokens) {
        if (model == null || model.isBlank()) {
            return Optional.empty();
        }

        Optional<BigDecimal> inputRate = rate(model, INPUT);
        if (inputRate.isEmpty()) {
            return Optional.empty();
        }

        // An output rate is optional so an embedding model, which has no completion side,
        // needs only one key configured.
        BigDecimal outputRate = rate(model, OUTPUT).orElse(BigDecimal.ZERO);
        BigDecimal cachedRate = rate(model, CACHED_INPUT).orElse(inputRate.get());

        long cached = Math.max(0, Math.min(cachedPromptTokens, promptTokens));
        long fresh = Math.max(0, promptTokens - cached);

        BigDecimal cost = perMillion(fresh, inputRate.get())
                .add(perMillion(cached, cachedRate))
                .add(perMillion(Math.max(0, completionTokens), outputRate));

        return Optional.of(cost.setScale(COST_SCALE, RoundingMode.HALF_UP));
    }

    /** The three rates configured for one model; a null field means "not set". */
    public record ModelRates(
            String model,
            BigDecimal inputPer1m,
            BigDecimal outputPer1m,
            BigDecimal cachedInputPer1m) {

        public boolean isPriced() {
            return inputPer1m != null;
        }
    }

    /**
     * Model names that have at least one pricing key written, parsed back out of the key
     * namespace.
     *
     * <p>A model name may itself contain dots ({@code gpt-5.4}), so the suffix is stripped
     * from the end rather than splitting on the first separator — the naive split yields
     * {@code gpt-5} and silently loses the row.
     */
    public List<String> configuredModels() {
        return configRepository.findKeysByPrefix(KEY_PREFIX).stream()
                .map(LlmPricingService::modelFromKey)
                .filter(m -> m != null && !m.isBlank())
                .distinct()
                .sorted()
                .toList();
    }

    private static String modelFromKey(String key) {
        String remainder = key.substring(KEY_PREFIX.length());
        for (String suffix : List.of(INPUT, OUTPUT, CACHED_INPUT)) {
            if (remainder.endsWith("." + suffix)) {
                return remainder.substring(0, remainder.length() - suffix.length() - 1);
            }
        }
        return null;
    }

    public ModelRates ratesFor(String model) {
        String normalized = normalize(model);
        return new ModelRates(
                normalized,
                rate(normalized, INPUT).orElse(null),
                rate(normalized, OUTPUT).orElse(null),
                rate(normalized, CACHED_INPUT).orElse(null));
    }

    /**
     * Writes the rates for one model. A null or blank field <em>clears</em> that rate
     * rather than leaving the previous value in place — the editor sends the whole set,
     * so an omitted field means the operator emptied the box.
     *
     * <p>Clearing writes an empty string instead of deleting the row: {@link #rate}
     * already treats blank as absent, and there is no delete on
     * {@link SystemConfigService}. Adding one for this would widen a shared service for a
     * single caller's convenience.
     *
     * <p>Rejects a negative rate outright. {@link #rate} also ignores one on read, but a
     * value that silently does nothing after the UI reported it saved is worse than an
     * error at the point of entry.
     */
    public ModelRates updateRates(String model, BigDecimal input, BigDecimal output,
                                  BigDecimal cachedInput) {
        String normalized = normalize(model);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("A model name is required");
        }
        write(normalized, INPUT, input);
        write(normalized, OUTPUT, output);
        write(normalized, CACHED_INPUT, cachedInput);
        return ratesFor(normalized);
    }

    private void write(String model, String suffix, BigDecimal value) {
        if (value != null && value.signum() < 0) {
            throw new IllegalArgumentException(
                    "Rate for " + model + " " + suffix + " cannot be negative");
        }
        systemConfig.set(key(model, suffix),
                value == null ? "" : value.toPlainString(),
                false,
                "USD per 1M tokens");
    }

    private static String normalize(String model) {
        return model == null ? "" : model.trim().toLowerCase();
    }

    private static String key(String model, String suffix) {
        return KEY_PREFIX + model + "." + suffix;
    }

    private BigDecimal perMillion(long tokens, BigDecimal ratePerMillion) {
        return BigDecimal.valueOf(tokens)
                .multiply(ratePerMillion)
                .divide(PER_MILLION, COST_SCALE + 4, RoundingMode.HALF_UP);
    }

    /**
     * Reads one rate. A malformed value is treated as absent rather than propagated: this
     * runs on the accounting path behind every model call, and a typo in a config row must
     * not turn into a failed chat turn.
     */
    private Optional<BigDecimal> rate(String model, String suffix) {
        String key = key(normalize(model), suffix);
        return systemConfig.get(key)
                .filter(v -> !v.isBlank())
                .flatMap(v -> {
                    try {
                        BigDecimal parsed = new BigDecimal(v.trim());
                        return parsed.signum() < 0 ? Optional.empty() : Optional.of(parsed);
                    } catch (NumberFormatException e) {
                        log.warn("Ignoring unparseable LLM rate {}={}", key, v);
                        return Optional.empty();
                    }
                });
    }
}
