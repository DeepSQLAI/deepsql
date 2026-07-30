package com.dbaagent.service.telemetry;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BrainRetrievalCountingVectorStoreTest {

    @Test
    void similaritySearchIncrementsCounterAndDelegatesResults() {
        VectorStore delegate = mock(VectorStore.class);
        Document doc = new Document("hello");
        when(delegate.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc));

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TelemetryCounters counters = new TelemetryCounters(registry);

        BrainRetrievalCountingVectorStore wrapped =
            new BrainRetrievalCountingVectorStore(delegate, counters);

        List<Document> result = wrapped.similaritySearch(SearchRequest.builder().query("q").build());

        assertThat(result).containsExactly(doc);
        assertThat(registry.counter(TelemetryCounters.BRAIN_RETRIEVALS).count()).isEqualTo(1.0);
        verify(delegate).similaritySearch(any(SearchRequest.class));
    }

    @Test
    void addAndDeleteDelegateWithoutCounting() {
        VectorStore delegate = mock(VectorStore.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TelemetryCounters counters = new TelemetryCounters(registry);
        BrainRetrievalCountingVectorStore wrapped =
            new BrainRetrievalCountingVectorStore(delegate, counters);

        List<Document> docs = List.of(new Document("d"));
        wrapped.add(docs);
        wrapped.delete(List.of("id-1"));

        verify(delegate).add(docs);
        verify(delegate).delete(List.of("id-1"));
        assertThat(registry.counter(TelemetryCounters.BRAIN_RETRIEVALS).count()).isEqualTo(0.0);
    }
}
