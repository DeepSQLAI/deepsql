package com.dbaagent.service.telemetry;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.List;
import java.util.Optional;

/**
 * Decorator that increments the brain-retrieval counter on every similarity
 * search. All other VectorStore operations pass through unchanged so the
 * decorator is a drop-in replacement for the auto-configured bean.
 *
 * Tier-aware (memory/Redis/vector) breakdown is deferred to Phase-3.
 */
@RequiredArgsConstructor
public class BrainRetrievalCountingVectorStore implements VectorStore {

    private final VectorStore delegate;
    private final TelemetryCounters counters;

    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        counters.incrementBrainRetrieval();
        return delegate.similaritySearch(request);
    }

    @Override
    public List<Document> similaritySearch(String query) {
        counters.incrementBrainRetrieval();
        return delegate.similaritySearch(query);
    }

    @Override
    public void add(List<Document> documents) {
        delegate.add(documents);
    }

    @Override
    public void delete(List<String> idList) {
        delegate.delete(idList);
    }

    @Override
    public void delete(Filter.Expression filterExpression) {
        delegate.delete(filterExpression);
    }

    @Override
    public void delete(String filterExpression) {
        delegate.delete(filterExpression);
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public <T> Optional<T> getNativeClient() {
        return delegate.getNativeClient();
    }
}
