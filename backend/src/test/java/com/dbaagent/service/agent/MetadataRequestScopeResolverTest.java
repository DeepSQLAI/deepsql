package com.dbaagent.service.agent;

import com.dbaagent.model.SchemaMetadata;
import com.dbaagent.model.TableMetadata;
import com.dbaagent.service.ChatQuestionRoutingService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MetadataRequestScopeResolverTest {

    private final MetadataRequestScopeResolver resolver = new MetadataRequestScopeResolver();

    @Test
    void resolve_marksExploratoryRelationshipQuestionAsExplanatory() {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setTables(List.of(
            new TableMetadata("ORDERS", null, "table", 1L, 0L, List.of(), List.of()),
            new TableMetadata("ORDER_DETAIL", null, "table", 1L, 0L, List.of(), List.of())
        ));

        PromptIntent promptIntent = new PromptIntent(
            PromptIntent.Domain.SCHEMA,
            PromptIntent.TaskType.EXPLAIN,
            Set.of(PromptIntent.SubjectType.TABLE, PromptIntent.SubjectType.RELATIONSHIP),
            PromptIntent.RequestedOutput.LIST,
            Map.of(),
            false,
            false,
            true,
            true
        );

        MetadataRequestScope scope = resolver.resolve(
            "How are ORDERS and ORDER_DETAIL related?",
            schema,
            new ChatQuestionRoutingService.QuestionRoute(
                ChatQuestionRoutingService.RouteType.BRAIN_METADATA,
                ChatQuestionRoutingService.BrainTopic.RELATIONSHIPS
            ),
            promptIntent
        );

        assertThat(scope.pairScoped()).isTrue();
        assertThat(scope.requestedTables()).containsExactly("ORDER_DETAIL", "ORDERS");
        assertThat(scope.answerStyle()).isEqualTo(MetadataRequestScope.AnswerStyle.EXPLANATORY);
    }
}
