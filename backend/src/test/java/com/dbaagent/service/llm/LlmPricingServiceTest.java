package com.dbaagent.service.llm;

import com.dbaagent.repository.SystemConfigRepository;
import com.dbaagent.service.SystemConfigService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmPricingServiceTest {

    private final Map<String, String> config = new HashMap<>();
    private final LlmPricingService pricing =
            new LlmPricingService(stubConfig(), stubRepository());

    private SystemConfigService stubConfig() {
        SystemConfigService svc = mock(SystemConfigService.class);
        when(svc.get(anyString())).thenAnswer(
                inv -> Optional.ofNullable(config.get(inv.getArgument(0, String.class))));
        // Writes land in the same map the reads come from, so a save is observable through
        // the public read path rather than by verifying a mock interaction.
        org.mockito.Mockito.doAnswer(inv -> {
            config.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(svc).set(anyString(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.any());
        return svc;
    }

    private SystemConfigRepository stubRepository() {
        SystemConfigRepository repo = mock(SystemConfigRepository.class);
        when(repo.findKeysByPrefix(anyString())).thenAnswer(inv -> {
            String prefix = inv.getArgument(0, String.class);
            return config.keySet().stream().filter(k -> k.startsWith(prefix)).sorted().toList();
        });
        return repo;
    }

    private void rate(String model, String suffix, String value) {
        config.put("llm.pricing." + model + "." + suffix, value);
    }

    @Test
    void pricesInputAndOutputSeparately() {
        rate("gpt-4o", "input-per-1m", "2.50");
        rate("gpt-4o", "output-per-1m", "10.00");

        // 1M input at $2.50 + 1M output at $10.00
        assertThat(pricing.estimateCost("gpt-4o", 1_000_000, 1_000_000, 0))
                .contains(new BigDecimal("12.500000"));
    }

    @Test
    void chargesCachedPromptTokensAtTheCachedRate() {
        rate("gpt-4o", "input-per-1m", "2.50");
        rate("gpt-4o", "output-per-1m", "10.00");
        rate("gpt-4o", "cached-input-per-1m", "1.25");

        // 400k fresh at $2.50/M = $1.00, 600k cached at $1.25/M = $0.75
        assertThat(pricing.estimateCost("gpt-4o", 1_000_000, 0, 600_000))
                .contains(new BigDecimal("1.750000"));
    }

    @Test
    void cachedRateDefaultsToTheInputRateWhenUnset() {
        rate("gpt-4o", "input-per-1m", "2.00");

        // With no cached rate configured, cached tokens must not become free.
        assertThat(pricing.estimateCost("gpt-4o", 1_000_000, 0, 500_000))
                .contains(new BigDecimal("2.000000"));
    }

    /**
     * A provider reporting more cached tokens than prompt tokens must not produce a
     * negative fresh count, which would subtract from the bill.
     */
    @Test
    void clampsCachedTokensThatExceedThePromptCount() {
        rate("m", "input-per-1m", "10.00");
        rate("m", "cached-input-per-1m", "1.00");

        // Cached is clamped to the 1,000 prompt tokens, so all 1,000 bill at the cached
        // $1.00/M — $0.001. The bug this guards against is the unclamped arithmetic, where
        // fresh = 1,000 - 9,999,999 goes negative and *subtracts* from the bill.
        assertThat(pricing.estimateCost("m", 1_000, 0, 9_999_999))
                .contains(new BigDecimal("0.001000"));
    }

    @Test
    void unpricedModelYieldsEmptyRatherThanZero() {
        assertThat(pricing.estimateCost("some-local-llama", 1_000_000, 1_000_000, 0)).isEmpty();
    }

    /** An embedding model has no completion side, so one key must be enough. */
    @Test
    void outputRateIsOptional() {
        rate("text-embedding-3-large", "input-per-1m", "0.13");

        assertThat(pricing.estimateCost("text-embedding-3-large", 1_000_000, 0, 0))
                .contains(new BigDecimal("0.130000"));
    }

    @Test
    void malformedRateIsTreatedAsUnpricedRatherThanThrowing() {
        rate("m", "input-per-1m", "not-a-number");

        assertThat(pricing.estimateCost("m", 1_000, 1_000, 0)).isEmpty();
    }

    @Test
    void negativeRateIsRejected() {
        rate("m", "input-per-1m", "-5.00");

        assertThat(pricing.estimateCost("m", 1_000, 0, 0)).isEmpty();
    }

    @Test
    void modelLookupIsCaseInsensitive() {
        rate("gpt-4o", "input-per-1m", "2.50");

        assertThat(pricing.estimateCost("GPT-4o", 1_000_000, 0, 0))
                .contains(new BigDecimal("2.500000"));
    }

    @Test
    void blankModelIsUnpriced() {
        assertThat(pricing.estimateCost("  ", 1_000, 0, 0)).isEmpty();
        assertThat(pricing.estimateCost(null, 1_000, 0, 0)).isEmpty();
    }

    /** Sub-cent calls must not round away to zero at the stored scale. */
    @Test
    void keepsPrecisionForSmallCalls() {
        rate("gpt-4o", "input-per-1m", "2.50");

        assertThat(pricing.estimateCost("gpt-4o", 1_000, 0, 0))
                .contains(new BigDecimal("0.002500"));
    }

    // ── Editing rates ─────────────────────────────────────────────────────────

    @Test
    void savedRatesAreUsedByTheNextCostCalculation() {
        pricing.updateRates("gpt-4o", new BigDecimal("2.50"), new BigDecimal("10.00"), null);

        assertThat(pricing.estimateCost("gpt-4o", 1_000_000, 1_000_000, 0))
                .contains(new BigDecimal("12.500000"));
    }

    @Test
    void ratesForReportsWhatWasSaved() {
        pricing.updateRates("gpt-4o", new BigDecimal("2.50"), new BigDecimal("10.00"),
                new BigDecimal("1.25"));

        LlmPricingService.ModelRates rates = pricing.ratesFor("gpt-4o");
        assertThat(rates.inputPer1m()).isEqualByComparingTo("2.50");
        assertThat(rates.outputPer1m()).isEqualByComparingTo("10.00");
        assertThat(rates.cachedInputPer1m()).isEqualByComparingTo("1.25");
        assertThat(rates.isPriced()).isTrue();
    }

    /** Emptying a field must clear the rate, not silently keep the old one. */
    @Test
    void aNullFieldClearsThatRate() {
        pricing.updateRates("gpt-4o", new BigDecimal("2.50"), new BigDecimal("10.00"), null);
        pricing.updateRates("gpt-4o", new BigDecimal("2.50"), null, null);

        assertThat(pricing.ratesFor("gpt-4o").outputPer1m()).isNull();
        // Output now contributes nothing, so only the input side is billed.
        assertThat(pricing.estimateCost("gpt-4o", 1_000_000, 1_000_000, 0))
                .contains(new BigDecimal("2.500000"));
    }

    @Test
    void clearingTheInputRateMakesTheModelUnpricedAgain() {
        pricing.updateRates("gpt-4o", new BigDecimal("2.50"), null, null);
        pricing.updateRates("gpt-4o", null, null, null);

        assertThat(pricing.ratesFor("gpt-4o").isPriced()).isFalse();
        assertThat(pricing.estimateCost("gpt-4o", 1_000, 0, 0)).isEmpty();
    }

    @Test
    void aNegativeRateIsRejectedAtWriteTime() {
        assertThatThrownBy(() ->
                pricing.updateRates("gpt-4o", new BigDecimal("-1"), null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
    }

    @Test
    void aBlankModelNameIsRejected() {
        assertThatThrownBy(() -> pricing.updateRates("   ", new BigDecimal("1"), null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void modelNamesAreNormalisedOnWriteSoLookupMatches() {
        pricing.updateRates("  GPT-4o  ", new BigDecimal("2.50"), null, null);

        assertThat(pricing.estimateCost("gpt-4o", 1_000_000, 0, 0))
                .contains(new BigDecimal("2.500000"));
    }

    /**
     * A model name can itself contain dots. Splitting the key on the first '.' after the
     * prefix would report "gpt-5" and lose the real row.
     */
    @Test
    void configuredModelsHandlesDottedModelNames() {
        pricing.updateRates("gpt-5.4", new BigDecimal("1.25"), new BigDecimal("10.00"), null);

        assertThat(pricing.configuredModels()).containsExactly("gpt-5.4");
    }

    @Test
    void configuredModelsListsEachModelOnceAcrossItsThreeKeys() {
        pricing.updateRates("gpt-4o", new BigDecimal("2.50"), new BigDecimal("10.00"),
                new BigDecimal("1.25"));
        pricing.updateRates("text-embedding-3-large", new BigDecimal("0.13"), null, null);

        assertThat(pricing.configuredModels())
                .containsExactly("gpt-4o", "text-embedding-3-large");
    }

    @Test
    void configuredModelsIsEmptyWhenNothingIsPriced() {
        assertThat(pricing.configuredModels()).isEmpty();
    }

    /**
     * Self-hosted ids look like {@code meta-llama/Llama-3-8b}. Such a name cannot travel
     * in the URL path — Spring Security's StrictHttpFirewall rejects an encoded slash
     * before any controller runs — so the controller accepts it in the body instead. The
     * service layer must handle it like any other name.
     */
    @Test
    void handlesModelNamesContainingASlash() {
        pricing.updateRates("meta-llama/Llama-3-8b", new BigDecimal("0.05"),
                new BigDecimal("0.08"), null);

        assertThat(pricing.configuredModels()).containsExactly("meta-llama/llama-3-8b");
        assertThat(pricing.estimateCost("meta-llama/Llama-3-8b", 1_000_000, 1_000_000, 0))
                .contains(new BigDecimal("0.130000"));
    }
}
