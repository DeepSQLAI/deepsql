package com.dbaagent.service.brain.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class BrainNoteIntentServiceTest {

    private static final String PRIOR_WRONG =
        "There are 12,004 people in dim_person.";
    private static final String CORRECTION =
        "No, that's wrong — the pinned metric is `meditator_count_current` from `marts.dim_person`.";
    private static final String AGENT_AFTER = "290066 meditators on that pinned metric.";

    private final BrainNoteIntentService service = new BrainNoteIntentService();

    @Test
    void proposeFromTurn_staysQuietWhenTheFirstAnswerNeedsNoFeedback() {
        Optional<BrainNoteIntentService.Proposal> proposal = service.proposeFromTurn(
            "what is the meditator count?",
            "The correct pinned metric is `meditator_count_current` from `marts.dim_person`, totaling 290066 meditators.",
            List.of(),
            null
        );

        assertThat(proposal).isEmpty();
    }

    @Test
    void proposeFromTurn_staysQuietWhenTheUserJustThanksTheAgent() {
        Optional<BrainNoteIntentService.Proposal> proposal = service.proposeFromTurn(
            "thanks, that's right",
            "Glad it helped.",
            List.of(),
            "The correct pinned metric is `meditator_count_current` from `marts.dim_person`."
        );

        assertThat(proposal).isEmpty();
    }

    @Test
    void proposeFromTurn_offersAfterUserCorrectsTheAgent() {
        Optional<BrainNoteIntentService.Proposal> proposal = service.proposeFromTurn(
            CORRECTION,
            AGENT_AFTER,
            List.of(),
            PRIOR_WRONG
        );

        assertThat(proposal).isPresent();
        assertThat(proposal.get().action()).isEqualTo("NEW");
        assertThat(proposal.get().tableName()).isEqualTo("marts.dim_person");
        assertThat(proposal.get().columnName()).isEqualTo("meditator_count_current");
        assertThat(proposal.get().bubbleLabel()).contains("correction");
        assertThat(proposal.get().excerpt()).contains("meditator_count_current");
        assertThat(proposal.get().proposedNoteText()).contains("marts.dim_person");
    }

    @Test
    void proposeFromTurn_skipsWhenExistingNoteIsSameIntent() {
        String existing = "For marts.dim_person.meditator_count_current: No, that's wrong — the pinned metric is meditator_count_current from marts.dim_person. 290066 meditators on that pinned metric.";
        Optional<BrainNoteIntentService.Proposal> proposal = service.proposeFromTurn(
            CORRECTION,
            AGENT_AFTER,
            List.of(new BrainNoteIntentService.ContextItem(
                "note-1", "marts.dim_person", "meditator_count_current", existing, "brain note"
            )),
            PRIOR_WRONG
        );

        assertThat(proposal).isPresent();
        assertThat(proposal.get().action()).isEqualTo("SKIP");
        assertThat(proposal.get().existingNoteId()).isEqualTo("note-1");
    }

    @Test
    void proposeFromTurn_mergesOverlappingContextIntoOneIntent() {
        Optional<BrainNoteIntentService.Proposal> proposal = service.proposeFromTurn(
            "No, that's wrong — the pinned metric is `meditator_count_current` from `marts.dim_person`, excluding ambiguous matches.",
            AGENT_AFTER,
            List.of(new BrainNoteIntentService.ContextItem(
                "note-1",
                "marts.dim_person",
                "meditator_count_current",
                "dim_person is the person dimension used for IRC region rollups.",
                "brain note"
            )),
            PRIOR_WRONG
        );

        assertThat(proposal).isPresent();
        assertThat(proposal.get().action()).isEqualTo("MERGE");
        assertThat(proposal.get().proposedNoteText()).contains("person dimension");
        assertThat(proposal.get().proposedNoteText()).contains("pinned metric");
        assertThat(proposal.get().overlapReason()).contains("one intent");
    }

    @Test
    void proposeFromTurn_matchesBareTableNameAgainstQualifiedContext() {
        Optional<BrainNoteIntentService.Proposal> proposal = service.proposeFromTurn(
            "No, that's wrong — the pinned metric is `meditator_count_current` from `marts.dim_person`, excluding ambiguous matches.",
            AGENT_AFTER,
            List.of(new BrainNoteIntentService.ContextItem(
                "rule-1",
                "dim_person",
                "meditator_count_current",
                "dim_person is the person dimension used for IRC region rollups.",
                "business rule"
            )),
            PRIOR_WRONG
        );

        assertThat(proposal).isPresent();
        assertThat(proposal.get().action()).isEqualTo("MERGE");
        assertThat(proposal.get().overlapReason()).contains("one intent");
    }

    @Test
    void proposeFromTurn_ignoresNonDefinitionAnswers() {
        Optional<BrainNoteIntentService.Proposal> proposal = service.proposeFromTurn(
            "how many tables are there?",
            "There are 42 tables in this database.",
            List.of()
        );
        assertThat(proposal).isEmpty();
    }

    @Test
    void findFirstQualifiedTable_skipsTripleQualifiedAndCatalogSchemas() {
        assertThat(BrainNoteIntentService.findFirstQualifiedTable("see marts.dim_person.col then crm.accounts"))
            .containsExactly("crm", "accounts");
        assertThat(BrainNoteIntentService.findFirstQualifiedTable("pg_catalog.pg_class")).isNull();
        assertThat(BrainNoteIntentService.findFirstQualifiedTable("A".repeat(20_000) + " marts.dim_person"))
            .containsExactly("marts", "dim_person");
    }

    @Test
    void mergeTexts_doesNotDuplicateTheSameSentence() {
        String merged = service.mergeTexts(
            "Always filter cancelled bookings.",
            "always filter cancelled bookings"
        );
        assertThat(merged).isEqualTo("Always filter cancelled bookings.");
    }
}
