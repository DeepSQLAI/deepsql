package com.dbaagent.service;

import com.dbaagent.model.QueryExecutionOrigin;
import com.dbaagent.model.QueryRequest;
import com.dbaagent.model.QueryResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserDataAccessPolicyServiceTest {

    @Mock private ConnectionChatAccessPolicyService policyService;
    @Mock private SecurityEventService securityEventService;

    private UserDataAccessPolicyService service;

    @BeforeEach
    void setUp() {
        service = new UserDataAccessPolicyService(policyService, securityEventService);
        lenient().when(policyService.buildProtectionDescriptors(anyString(), any(), any(), any()))
            .thenReturn(Map.of(
                "customer_profiles",
                descriptor("customer_profiles", false, "email", "phone_number")
            ));
    }

    @Test
    void evaluatePrompt_blocksDirectSensitiveRequestButAllowsAggregate() {
        ConnectionChatAccessPolicyService.EffectivePolicy policy = policy();
        when(policyService.resolveEffectivePolicy("conn-1", "analyst", false)).thenReturn(policy);

        UserDataAccessPolicyService.PromptDecision blocked = service.evaluatePrompt(
            "conn-1",
            "analyst",
            false,
            "Show me customer emails and phone numbers for active customers"
        );
        UserDataAccessPolicyService.PromptDecision allowed = service.evaluatePrompt(
            "conn-1",
            "analyst",
            false,
            "How many active customers do we have by month?"
        );

        assertThat(blocked.allowed()).isFalse();
        assertThat(blocked.responseMessage()).contains("protected data");
        assertThat(allowed.allowed()).isTrue();
    }

    @Test
    void enforcePreExecution_blocksRawProtectedColumns() {
        when(policyService.resolveEffectivePolicy("conn-1", "analyst", false)).thenReturn(policy());

        UserDataAccessPolicyException exception = assertThrows(
            UserDataAccessPolicyException.class,
            () -> service.enforcePreExecution(
                "conn-1",
                new QueryRequest("SELECT email, phone_number FROM customer_profiles", null, null),
                new QueryExecutionContext(QueryExecutionOrigin.CHAT, QueryExecutionContext.MutationMode.READ_ONLY_ONLY, "analyst", false, false)
            )
        );

        assertThat(exception.getErrorCode()).isEqualTo("POLICY_SQL_BLOCKED");
    }

    @Test
    void redactResult_masksProtectedColumnsButKeepsSafeColumns() {
        when(policyService.resolveEffectivePolicy("conn-1", "analyst", false)).thenReturn(policy());

        QueryResult redacted = service.redactResult(
            "conn-1",
            new QueryResult(
                List.of("customer_name", "email", "phone_number"),
                List.of(List.of("Hotel One", "a@example.com", "1234567890")),
                1,
                1L,
                false,
                12L,
                "SELECT customer_name, email, phone_number FROM customer_profiles"
            ),
            new QueryExecutionContext(QueryExecutionOrigin.CHAT, QueryExecutionContext.MutationMode.READ_ONLY_ONLY, "analyst", false, false)
        );

        assertThat(redacted.getRows()).containsExactly(List.of("Hotel One", "[redacted:13]", "[redacted:10]"));
    }

    @Test
    void decorateQuestionWithPolicy_injectsBoundariesForPlanner() {
        String decorated = service.decorateQuestionWithPolicy(policy(), "Show me customer segments by month");

        assertThat(decorated).contains("DeepSQL access policy for this user");
        assertThat(decorated).contains("customer segments by month");
    }

    private ConnectionChatAccessPolicyService.EffectivePolicy policy() {
        return new ConnectionChatAccessPolicyService.EffectivePolicy(
            true,
            "conn-1",
            "analyst",
            Set.of("PII_MEDIUM"),
            Set.of(),
            Set.of("customer_profiles.email", "customer_profiles.phone_number"),
            true,
            true,
            "No PII",
            List.of(),
            List.of("customer_profiles.email", "customer_profiles.phone_number")
        );
    }

    private ConnectionChatAccessPolicyService.ProtectionDescriptor descriptor(String tableName, boolean protectWholeTable, String... columns) {
        try {
            var constructor = ConnectionChatAccessPolicyService.ProtectionDescriptor.class
                .getDeclaredConstructor(String.class, boolean.class, Set.class);
            constructor.setAccessible(true);
            return constructor.newInstance(tableName, protectWholeTable, new java.util.LinkedHashSet<>(List.of(columns)));
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
