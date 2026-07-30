package com.dbaagent.service;

import com.dbaagent.repository.CompanyKnowledgeEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Runs the expensive derived-state sync for company-knowledge mutations OFF the
 * request thread.
 *
 * <p>Creating/updating/deleting an entry used to block the HTTP request for ~15s
 * because it synchronously (a) called Azure to embed the entry and (b) rebuilt
 * and re-persisted the whole connection's semantic join model — which starts
 * with a full live schema scan. The mutation now commits fast and publishes an
 * event; these {@code AFTER_COMMIT} + {@code @Async} listeners do the embedding
 * and rebuild in the background, so the entry becomes queryable a moment later.
 *
 * <p>Rebuilds are serialized per connection (a burst of edits runs sequentially,
 * never concurrently) so two background rebuilds can't clobber the same
 * {@code semantic_join_model} rows. Different connections still rebuild in
 * parallel. {@code fallbackExecution = true} keeps the listeners working for any
 * non-transactional caller.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CompanyKnowledgeIndexer {

    private final CompanyKnowledgeEntryRepository companyKnowledgeEntryRepository;
    private final TrainingService trainingService;
    private final SemanticModelService semanticModelService;

    private final ConcurrentHashMap<String, ReentrantLock> connectionLocks = new ConcurrentHashMap<>();

    /** Published after a create/update commits; carries the entry to (re)embed. */
    public record EntryUpserted(String connectionId, String entryId) { }

    /** Published after a delete commits; the embedding row was already removed inline. */
    public record EntryDeleted(String connectionId) { }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onUpserted(EntryUpserted event) {
        try {
            companyKnowledgeEntryRepository.findById(event.entryId()).ifPresentOrElse(
                entry -> {
                    try {
                        trainingService.upsertCompanyKnowledgeEmbedding(entry);
                    } catch (Exception e) {
                        log.warn("Background embedding upsert failed for company-knowledge entry {}: {}",
                            event.entryId(), e.getMessage());
                    }
                },
                () -> log.warn("Company-knowledge entry {} vanished before background embedding", event.entryId()));
        } finally {
            rebuild(event.connectionId());
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onDeleted(EntryDeleted event) {
        rebuild(event.connectionId());
    }

    private void rebuild(String connectionId) {
        ReentrantLock lock = connectionLocks.computeIfAbsent(connectionId, k -> new ReentrantLock());
        lock.lock();
        try {
            semanticModelService.rebuildSemanticModel(connectionId);
        } catch (Exception e) {
            log.warn("Background semantic-model rebuild failed for connection {}: {}", connectionId, e.getMessage());
        } finally {
            lock.unlock();
        }
    }
}
