package com.dbaagent.service.llm;

import com.dbaagent.model.LlmUsage;
import com.dbaagent.model.LlmUsageRole;
import com.dbaagent.service.QueryActorContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmUsageRecorderTest {

    private final LlmUsageWriter writer = mock(LlmUsageWriter.class);
    private final LlmPricingService pricing = mock(LlmPricingService.class);
    private final LlmUsageRecorder recorder = new LlmUsageRecorder(writer, pricing);

    @AfterEach
    void clearContexts() {
        SecurityContextHolder.clearContext();
    }

    private static LlmUsageRecorder.Call chatCall() {
        return new LlmUsageRecorder.Call(
                LlmUsageRole.CHAT, "openai", "gpt-4o",
                1_000, 500, 1_500, 0, false, 42L, true, null);
    }

    private LlmUsage captureWritten() {
        ArgumentCaptor<LlmUsage> captor = ArgumentCaptor.forClass(LlmUsage.class);
        verify(writer).write(captor.capture());
        return captor.getValue();
    }

    @Test
    void storesTokenCountsAndPricedCost() {
        when(pricing.estimateCost(anyString(), anyLong(), anyLong(), anyLong()))
                .thenReturn(Optional.of(new BigDecimal("0.007500")));

        recorder.record(chatCall());

        LlmUsage row = captureWritten();
        assertThat(row.getRole()).isEqualTo("CHAT");
        assertThat(row.getModel()).isEqualTo("gpt-4o");
        assertThat(row.getPromptTokens()).isEqualTo(1_000);
        assertThat(row.getCompletionTokens()).isEqualTo(500);
        assertThat(row.getTotalTokens()).isEqualTo(1_500);
        assertThat(row.getEstimatedCostUsd()).isEqualByComparingTo("0.007500");
        assertThat(row.isEstimated()).isFalse();
    }

    /**
     * An unpriced model must store null, not zero — a zero would silently understate every
     * total that sums this column, with nothing to indicate the gap.
     */
    @Test
    void unpricedModelStoresNullCostNotZero() {
        when(pricing.estimateCost(anyString(), anyLong(), anyLong(), anyLong()))
                .thenReturn(Optional.empty());

        recorder.record(chatCall());

        assertThat(captureWritten().getEstimatedCostUsd()).isNull();
    }

    @Test
    void derivesTotalWhenTheProviderDidNotReportOne() {
        when(pricing.estimateCost(anyString(), anyLong(), anyLong(), anyLong()))
                .thenReturn(Optional.empty());

        recorder.record(new LlmUsageRecorder.Call(
                LlmUsageRole.CHAT, "openai", "gpt-4o",
                700, 300, 0, 0, false, 1L, true, null));

        assertThat(captureWritten().getTotalTokens()).isEqualTo(1_000);
    }

    @Test
    void attributesUsageToTheSqlActorRatherThanTheSecurityPrincipal() {
        when(pricing.estimateCost(anyString(), anyLong(), anyLong(), anyLong()))
                .thenReturn(Optional.empty());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", "x", List.of()));

        QueryActorContextHolder.withActor("analyst", () -> {
            recorder.record(chatCall());
            return null;
        });

        // Under "View as", the principal is the admin but the work is done for the target
        // user, and the spend belongs to them.
        assertThat(captureWritten().getUsername()).isEqualTo("analyst");
    }

    @Test
    void fallsBackToTheSecurityPrincipalWithNoActor() {
        when(pricing.estimateCost(anyString(), anyLong(), anyLong(), anyLong()))
                .thenReturn(Optional.empty());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("dba", "x", List.of()));

        recorder.record(chatCall());

        assertThat(captureWritten().getUsername()).isEqualTo("dba");
    }

    @Test
    void backgroundWorkRecordsNullUsernameRatherThanFailing() {
        when(pricing.estimateCost(anyString(), anyLong(), anyLong(), anyLong()))
                .thenReturn(Optional.empty());

        recorder.record(chatCall());

        assertThat(captureWritten().getUsername()).isNull();
    }

    @Test
    void capturesTheDeclaredFeatureAndConnection() {
        when(pricing.estimateCost(anyString(), anyLong(), anyLong(), anyLong()))
                .thenReturn(Optional.empty());

        LlmUsageContext.with("dashboard-generate", "conn-7", () -> {
            recorder.record(chatCall());
            return null;
        });

        LlmUsage row = captureWritten();
        assertThat(row.getFeature()).isEqualTo("dashboard-generate");
        assertThat(row.getConnectionId()).isEqualTo("conn-7");
    }

    @Test
    void unattributedCallRecordsUnknownFeatureRatherThanFailing() {
        when(pricing.estimateCost(anyString(), anyLong(), anyLong(), anyLong()))
                .thenReturn(Optional.empty());

        recorder.record(chatCall());

        assertThat(captureWritten().getFeature()).isEqualTo("unknown");
    }

    /**
     * The property this whole class is built around: accounting is secondary to the
     * feature, so a broken ledger must never surface to the caller.
     */
    @Test
    void aWriteFailureNeverPropagatesToTheCaller() {
        when(pricing.estimateCost(anyString(), anyLong(), anyLong(), anyLong()))
                .thenReturn(Optional.empty());
        doThrow(new RuntimeException("table llm_usage does not exist"))
                .when(writer).write(any());

        assertThatCode(() -> recorder.record(chatCall())).doesNotThrowAnyException();
    }

    @Test
    void aPricingFailureNeverPropagatesToTheCaller() {
        when(pricing.estimateCost(anyString(), anyLong(), anyLong(), anyLong()))
                .thenThrow(new RuntimeException("config backend down"));

        assertThatCode(() -> recorder.record(chatCall())).doesNotThrowAnyException();
    }

    @Test
    void negativeTokenCountsAreClampedToZero() {
        when(pricing.estimateCost(anyString(), anyLong(), anyLong(), anyLong()))
                .thenReturn(Optional.empty());

        recorder.record(new LlmUsageRecorder.Call(
                LlmUsageRole.CHAT, "openai", "gpt-4o",
                -5, -5, 0, -5, false, 1L, true, null));

        LlmUsage row = captureWritten();
        assertThat(row.getPromptTokens()).isZero();
        assertThat(row.getCompletionTokens()).isZero();
        assertThat(row.getCachedPromptTokens()).isZero();
    }

    @Test
    void failedCallsAreRecordedWithTheirErrorCategory() {
        when(pricing.estimateCost(anyString(), anyLong(), anyLong(), anyLong()))
                .thenReturn(Optional.empty());

        recorder.record(new LlmUsageRecorder.Call(
                LlmUsageRole.CHAT, "openai", "gpt-4o",
                1_000, 0, 1_000, 0, false, 5L, false, "RATE_LIMIT"));

        LlmUsage row = captureWritten();
        assertThat(row.isSucceeded()).isFalse();
        assertThat(row.getErrorCategory()).isEqualTo("RATE_LIMIT");
    }

    /** Nested scopes must restore the outer one rather than clearing it. */
    @Test
    void usageContextRestoresTheEnclosingScope() {
        LlmUsageContext.with("outer", "conn-a", () -> {
            LlmUsageContext.with("inner", "conn-b", () -> {
                assertThat(LlmUsageContext.currentFeature()).isEqualTo("inner");
                return null;
            });
            assertThat(LlmUsageContext.currentFeature()).isEqualTo("outer");
            assertThat(LlmUsageContext.currentConnectionId()).isEqualTo("conn-a");
            return null;
        });
        assertThat(LlmUsageContext.currentFeature()).isEqualTo(LlmUsageContext.UNKNOWN_FEATURE);
    }
}
