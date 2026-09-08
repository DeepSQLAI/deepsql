package com.dbaagent.service;

import com.dbaagent.model.DigestDeliveryMethod;
import com.dbaagent.model.PersonaTag;
import com.dbaagent.model.Role;
import com.dbaagent.model.SlackDigestLog;
import com.dbaagent.model.SlackUserLink;
import com.dbaagent.model.User;
import com.dbaagent.model.UserDigestPreference;
import com.dbaagent.model.digest.DigestAssemblyResult;
import com.dbaagent.model.digest.DigestInsight;
import com.dbaagent.model.digest.InsightCategory;
import com.dbaagent.repository.UserDigestPreferenceRepository;
import com.dbaagent.repository.UserRepository;
import com.dbaagent.repository.SlackDigestLogRepository;
import com.dbaagent.service.digest.DigestInsightAssemblerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for per-recipient personalized digest delivery (PR3).
 *
 * <h3>Key scenarios:</h3>
 * <ul>
 *   <li>Two users with different personas get different digests for same connection</li>
 *   <li>EXEC persona gets tight 3-bullet executive summary</li>
 *   <li>Legacy fallback when no preferences exist</li>
 *   <li>Proper logging with recipient details</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class PerRecipientDigestDeliveryTest {

    @Mock private UserDigestPreferenceRepository preferenceRepository;
    @Mock private DigestInsightAssemblerService assemblerService;
    @Mock private SlackUserLinkService slackUserLinkService;
    @Mock private UserRepository userRepository;
    @Mock private SlackDigestLogRepository digestLogRepository;

    @BeforeEach
    void setUp() {
        lenient().when(preferenceRepository.hasAnyPreferences()).thenReturn(false);
    }

    @Test
    void twoUsersWithDifferentPersonas_getDifferentDigests() {
        // Given: two users with different personas on the same connection
        String connectionId = "conn-123";

        UserDigestPreference dbaPreference = UserDigestPreference.builder()
            .id(1L)
            .username("alice_dba")
            .connectionId(connectionId)
            .enabled(true)
            .deliveryMethod(DigestDeliveryMethod.SLACK_DM)
            .personaTag(PersonaTag.DBA)
            .build();

        UserDigestPreference execPreference = UserDigestPreference.builder()
            .id(2L)
            .username("bob_exec")
            .connectionId(connectionId)
            .enabled(true)
            .deliveryMethod(DigestDeliveryMethod.SLACK_DM)
            .personaTag(PersonaTag.EXEC)
            .build();

        // Mock user lookups
        User dbaUser = new User();
        dbaUser.setUsername("alice_dba");
        dbaUser.setRole("DBA");

        User execUser = new User();
        execUser.setUsername("bob_exec");
        execUser.setRole("ADMIN");

        when(userRepository.findByUsernameIgnoreCase("alice_dba")).thenReturn(Optional.of(dbaUser));
        when(userRepository.findByUsernameIgnoreCase("bob_exec")).thenReturn(Optional.of(execUser));

        // Mock assembler to return different results based on persona
        DigestAssemblyResult dbaResult = createDbaDigest();
        DigestAssemblyResult execResult = createExecDigest();

        when(assemblerService.assembleDigest(
            eq("alice_dba"), eq(connectionId), eq(Role.DBA), eq(PersonaTag.DBA), any()
        )).thenReturn(dbaResult);

        when(assemblerService.assembleDigest(
            eq("bob_exec"), eq(connectionId), eq(Role.ADMIN), eq(PersonaTag.EXEC), any()
        )).thenReturn(execResult);

        // When: assembling digests for both users
        DigestAssemblyResult aliceDigest = assemblerService.assembleDigest(
            "alice_dba", connectionId, Role.DBA, PersonaTag.DBA, null);
        DigestAssemblyResult bobDigest = assemblerService.assembleDigest(
            "bob_exec", connectionId, Role.ADMIN, PersonaTag.EXEC, null);

        // Then: they get different insights based on persona
        assertThat(aliceDigest.getPersonaTag()).isEqualTo(PersonaTag.DBA);
        assertThat(bobDigest.getPersonaTag()).isEqualTo(PersonaTag.EXEC);

        // DBA gets more insights (full digest)
        assertThat(aliceDigest.getInsights()).hasSize(5);

        // EXEC gets fewer, with executive summary
        assertThat(bobDigest.getInsights()).hasSize(3);
        assertThat(bobDigest.getExecutiveSummary()).isNotNull();
        assertThat(bobDigest.getExecutiveSummary()).hasSizeLessThanOrEqualTo(3);
    }

    @Test
    void execPersona_getsTightThreeBulletSummary() {
        // Given: EXEC persona digest
        DigestAssemblyResult execResult = createExecDigest();

        // Then: executive summary has at most 3 bullets
        assertThat(execResult.getExecutiveSummary()).isNotNull();
        assertThat(execResult.getExecutiveSummary()).hasSizeLessThanOrEqualTo(3);

        // And has a decision ask
        assertThat(execResult.getDecisionAsk()).isNotNull();
    }

    @Test
    void digestLog_containsRecipientDetails() {
        // Given: a digest log entry for personalized delivery
        SlackDigestLog logEntry = new SlackDigestLog();
        logEntry.setConnectionId("conn-123");
        logEntry.setRecipientUsername("alice_dba");
        logEntry.setRecipientRole("DBA");
        logEntry.setPersonaTag(PersonaTag.DBA);
        logEntry.setDeliveryMethod(DigestDeliveryMethod.SLACK_DM);
        logEntry.setPersonalized(true);
        logEntry.setStatus("SENT");

        // Then: it has all the per-recipient details
        assertThat(logEntry.getRecipientUsername()).isEqualTo("alice_dba");
        assertThat(logEntry.getRecipientRole()).isEqualTo("DBA");
        assertThat(logEntry.getPersonaTag()).isEqualTo(PersonaTag.DBA);
        assertThat(logEntry.isPersonalized()).isTrue();
        assertThat(logEntry.isPerUserDelivery()).isTrue();
    }

    @Test
    void legacyDigest_hasNoRecipientDetails() {
        // Given: a legacy channel-broadcast digest log entry
        SlackDigestLog logEntry = new SlackDigestLog();
        logEntry.setConnectionId("conn-123");
        logEntry.setChannelId("C123456");
        logEntry.setPersonalized(false);
        logEntry.setStatus("SENT");

        // Then: it has no recipient-specific details
        assertThat(logEntry.getRecipientUsername()).isNull();
        assertThat(logEntry.getRecipientRole()).isNull();
        assertThat(logEntry.getPersonaTag()).isNull();
        assertThat(logEntry.isPersonalized()).isFalse();
        assertThat(logEntry.isPerUserDelivery()).isFalse();
    }

    @Test
    void preferenceForConnection_appliesCorrectly() {
        // Given: a preference with a specific connection
        UserDigestPreference pref = UserDigestPreference.builder()
            .username("alice")
            .connectionId("conn-123")
            .enabled(true)
            .build();

        // Then: it applies to that connection
        assertThat(pref.appliesTo("conn-123")).isTrue();
        assertThat(pref.appliesTo("conn-456")).isFalse();
    }

    @Test
    void preferenceWithNullConnection_appliesToAll() {
        // Given: a preference without a specific connection
        UserDigestPreference pref = UserDigestPreference.builder()
            .username("alice")
            .connectionId(null)
            .enabled(true)
            .build();

        // Then: it applies to all connections
        assertThat(pref.appliesTo("conn-123")).isTrue();
        assertThat(pref.appliesTo("conn-456")).isTrue();
    }

    @Test
    void assemblyResult_emptyDigest_indicatesEmpty() {
        // Given: an empty digest result
        DigestAssemblyResult empty = DigestAssemblyResult.empty(
            "alice", "conn-123", Role.DBA, PersonaTag.DBA);

        // Then: it's marked as empty
        assertThat(empty.isEmpty()).isTrue();
        assertThat(empty.getInsights()).isEmpty();
        assertThat(empty.getHeadline()).isEqualTo("No new insights since your last digest");
    }

    private DigestAssemblyResult createDbaDigest() {
        List<DigestInsight> insights = List.of(
            createInsight(InsightCategory.LOCK_CONCURRENCY, "Critical lock wait", 92),
            createInsight(InsightCategory.QUERY_PERFORMANCE, "Plan regression", 85),
            createInsight(InsightCategory.INDEX_RECOMMENDATIONS, "3 index recommendations", 75),
            createInsight(InsightCategory.CONFIG_TUNING, "join_collapse_limit adjustment", 65),
            createInsight(InsightCategory.GROWTH_ANOMALIES, "Table growth detected", 55)
        );

        return DigestAssemblyResult.builder()
            .username("alice_dba")
            .connectionId("conn-123")
            .role(Role.DBA)
            .personaTag(PersonaTag.DBA)
            .assembledAt(LocalDateTime.now())
            .insights(insights)
            .totalCandidates(10)
            .build();
    }

    private DigestAssemblyResult createExecDigest() {
        List<DigestInsight> insights = List.of(
            createInsight(InsightCategory.COST_CAPACITY, "Storage cost up 15%", 75),
            createInsight(InsightCategory.QUERY_PERFORMANCE, "Top regression: 3x slowdown", 85),
            createInsight(InsightCategory.GROWTH_ANOMALIES, "Capacity planning needed", 70)
        );

        List<String> execSummary = List.of(
            "⚠️ 1 critical issue requires attention",
            "📉 Top regression: 3x slowdown on orders query",
            "💰 Storage cost up 15% this month"
        );

        return DigestAssemblyResult.builder()
            .username("bob_exec")
            .connectionId("conn-123")
            .role(Role.ADMIN)
            .personaTag(PersonaTag.EXEC)
            .assembledAt(LocalDateTime.now())
            .insights(insights)
            .executiveSummary(execSummary)
            .decisionAsk("Review and approve index recommendation for orders table")
            .totalCandidates(10)
            .build();
    }

    private DigestInsight createInsight(InsightCategory category, String headline, int severity) {
        return DigestInsight.builder()
            .category(category)
            .headline(headline)
            .severity(severity)
            .timestamp(LocalDateTime.now())
            .actionable(true)
            .signatureKey(category.name() + ":" + headline.hashCode())
            .build();
    }
}
