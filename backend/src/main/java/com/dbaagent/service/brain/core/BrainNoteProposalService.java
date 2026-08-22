package com.dbaagent.service.brain.core;

import com.dbaagent.dto.BrainNoteProposalRequest;
import com.dbaagent.dto.BrainNoteProposalResponse;
import com.dbaagent.model.SchemaDocumentation;
import com.dbaagent.model.brain.BrainNoteRequest;
import com.dbaagent.model.brain.BrainNoteResponse;
import com.dbaagent.model.brain.BrainRule;
import com.dbaagent.repository.SchemaDocumentationRepository;
import com.dbaagent.repository.brain.BrainRuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class BrainNoteProposalService {

    private final BrainNoteIntentService intentService;
    private final BrainNoteService brainNoteService;
    private final SchemaDocumentationRepository documentationRepository;
    private final BrainRuleRepository brainRuleRepository;

    public Optional<BrainNoteProposalResponse> proposeFromTurn(BrainNoteProposalRequest request) {
        if (request == null || request.getConnectionId() == null || request.getConnectionId().isBlank()) {
            return Optional.empty();
        }
        List<BrainNoteIntentService.ContextItem> context = loadContext(request.getConnectionId());
        return intentService.proposeFromTurn(request.getQuestion(), request.getAnswer(), context)
            .filter(proposal -> !"SKIP".equals(proposal.action()))
            .map(this::toResponse);
    }

    public BrainNoteResponse accept(BrainNoteRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request is required");
        }
        List<BrainNoteIntentService.ContextItem> context = loadContext(request.getConnectionId());
        BrainNoteIntentService.Proposal draft = new BrainNoteIntentService.Proposal(
            request.getScopeType(),
            request.getTableName(),
            request.getColumnName(),
            null,
            request.getNoteText(),
            request.getNoteText(),
            "NEW",
            null,
            null,
            null
        );
        BrainNoteIntentService.Proposal resolved = intentService.resolveOverlap(draft, context);
        if (resolved != null && "MERGE".equals(resolved.action()) && resolved.existingNoteId() != null
            && isDocumentationNote(resolved.existingNoteId())) {
            BrainNoteRequest update = new BrainNoteRequest();
            update.setScopeType(resolved.scopeType());
            update.setTableName(resolved.tableName());
            update.setColumnName(resolved.columnName());
            update.setNoteText(resolved.proposedNoteText());
            update.setCreatedBy(request.getCreatedBy());
            return brainNoteService.updateNote(resolved.existingNoteId(), update);
        }
        if (resolved != null && "SKIP".equals(resolved.action()) && resolved.existingNoteId() != null
            && isDocumentationNote(resolved.existingNoteId())) {
            return brainNoteService.getNotes(
                request.getConnectionId(),
                resolved.scopeType(),
                resolved.tableName(),
                resolved.columnName()
            ).stream().findFirst().orElseGet(() -> brainNoteService.createNote(request));
        }
        if (resolved != null) {
            request.setNoteText(resolved.proposedNoteText());
            request.setScopeType(resolved.scopeType());
            request.setTableName(resolved.tableName());
            request.setColumnName(resolved.columnName());
        }
        return brainNoteService.createNote(request);
    }

    private boolean isDocumentationNote(String id) {
        return documentationRepository.findById(id).isPresent();
    }

    private List<BrainNoteIntentService.ContextItem> loadContext(String connectionId) {
        List<BrainNoteIntentService.ContextItem> items = new ArrayList<>();
        for (SchemaDocumentation doc : documentationRepository.findByConnectionId(connectionId)) {
            if (doc.getObjectType() == SchemaDocumentation.DocumentationType.TABLE) {
                items.add(new BrainNoteIntentService.ContextItem(
                    doc.getId(), doc.getObjectName(), null, doc.getDescription(), "brain note"
                ));
            } else if (doc.getObjectType() == SchemaDocumentation.DocumentationType.COLUMN) {
                items.add(new BrainNoteIntentService.ContextItem(
                    doc.getId(), doc.getParentObject(), doc.getObjectName(), doc.getDescription(), "brain note"
                ));
            }
        }
        for (BrainRule rule : brainRuleRepository.findByConnectionIdAndIsActiveTrueOrderByCreatedAtDesc(connectionId)) {
            items.add(new BrainNoteIntentService.ContextItem(
                rule.getId(),
                rule.getTableName(),
                rule.getColumnName(),
                rule.getRuleText(),
                "business rule"
            ));
        }
        return items;
    }

    private BrainNoteProposalResponse toResponse(BrainNoteIntentService.Proposal proposal) {
        return BrainNoteProposalResponse.builder()
            .scopeType(proposal.scopeType())
            .tableName(proposal.tableName())
            .columnName(proposal.columnName())
            .bubbleLabel(proposal.bubbleLabel())
            .excerpt(proposal.excerpt())
            .proposedNoteText(proposal.proposedNoteText())
            .action(proposal.action())
            .existingNoteId(proposal.existingNoteId())
            .existingNoteText(proposal.existingNoteText())
            .overlapReason(proposal.overlapReason())
            .build();
    }
}
