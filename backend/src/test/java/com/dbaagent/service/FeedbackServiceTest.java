package com.dbaagent.service;

import com.dbaagent.model.ChatFeedback;
import com.dbaagent.model.ApprovedAgentWorkflow;
import com.dbaagent.repository.ChatFeedbackRepository;
import com.dbaagent.service.agent.ApprovedWorkflowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class FeedbackServiceTest {

    @Mock
    private ChatFeedbackRepository feedbackRepository;

    @Mock
    private BusinessRuleMemoryService businessRuleMemoryService;

    @Mock
    private TrainingService trainingService;

    @Mock
    private SchemaScannerService schemaScannerService;

    @Mock
    private ApprovedWorkflowService approvedWorkflowService;

    private FeedbackService service;

    @BeforeEach
    void setUp() {
        service = new FeedbackService(
            feedbackRepository,
            businessRuleMemoryService,
            trainingService,
            schemaScannerService,
            approvedWorkflowService
        );
        ReflectionTestUtils.setField(service, "trainOnThumbsUp", true);
        when(feedbackRepository.save(any(ChatFeedback.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void recordCorrectionLearnsSqlGuardrails() {
        service.recordCorrection(
            "conn-1",
            "chat-1",
            "msg-1",
            "question",
            "response",
            "SELECT 1",
            "Use accounts + accounts_ledger on group_id and type=CREDIT",
            "accounts",
            "group_id"
        );

        verify(businessRuleMemoryService).learnFromFeedback(
            eq("conn-1"),
            eq("Use accounts + accounts_ledger on group_id and type=CREDIT"),
            eq("accounts"),
            eq("group_id"),
            eq("feedback-correction"),
            eq(null)
        );
    }

    @Test
    void recordThumbsUpDoesNotLearnSqlGuardrailsButVerifiesExamples() {
        ApprovedAgentWorkflow workflow = new ApprovedAgentWorkflow();
        workflow.setExampleQuestion("find recurring guests in the last one week");
        when(approvedWorkflowService.approveRun("conn-1", "run-1")).thenReturn(java.util.Optional.of(workflow));

        service.recordThumbsUp(
            "conn-1",
            "chat-1",
            "msg-1",
            "don't do * 1000 for milli seconds conversion.",
            "response",
            "SELECT 1",
            "run-1"
        );

        // Thumbs-up should NOT learn guardrails...
        verify(businessRuleMemoryService, never()).learnFromFeedback(
            any(),
            any(),
            any(),
            any(),
            any(),
            any()
        );
        // ...but SHOULD verify query examples
        verify(trainingService).verifyQueryExamplesBySql(eq("conn-1"), eq("SELECT 1"));
        verify(trainingService).trainWithQueryExampleIfNotExists(
            eq("conn-1"),
            eq("find recurring guests in the last one week"),
            eq("SELECT 1"),
            eq(null),
            eq(null)
        );
        verify(approvedWorkflowService).approveRun(eq("conn-1"), eq("run-1"));
        var inOrder = inOrder(approvedWorkflowService, trainingService);
        inOrder.verify(approvedWorkflowService).approveRun("conn-1", "run-1");
        inOrder.verify(trainingService).trainWithQueryExampleIfNotExists(
            "conn-1",
            "find recurring guests in the last one week",
            "SELECT 1",
            null,
            null
        );
        inOrder.verify(trainingService).verifyQueryExamplesBySql("conn-1", "SELECT 1");
        verifyNoMoreInteractions(businessRuleMemoryService);
    }

    @Test
    void recordThumbsDownRejectsMatchingExamples() {
        service.recordThumbsDown(
            "conn-1",
            "chat-1",
            "msg-1",
            "question",
            "response",
            "SELECT 1",
            "wrong query"
        );

        verify(trainingService).rejectQueryExamplesBySql(eq("conn-1"), eq("SELECT 1"));
    }

    @Test
    void recordColumnValuesLearnsSqlGuardrails() {
        service.recordColumnValues(
            "conn-1",
            "accounts_ledger",
            "mode",
            List.of("SUBSCRIPTION", "ADDON")
        );

        verify(businessRuleMemoryService).learnFromFeedback(
            eq("conn-1"),
            eq("Valid values for accounts_ledger.mode: 'SUBSCRIPTION', 'ADDON'"),
            eq("accounts_ledger"),
            eq("mode"),
            eq("feedback-column-values"),
            eq(null)
        );
    }
}
