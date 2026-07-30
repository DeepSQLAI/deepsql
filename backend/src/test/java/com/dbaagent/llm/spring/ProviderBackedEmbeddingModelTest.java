package com.dbaagent.llm.spring;

import com.dbaagent.service.EmbeddingService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProviderBackedEmbeddingModelTest {

    private final EmbeddingService service = mock(EmbeddingService.class);
    private final ProviderBackedEmbeddingModel model = new ProviderBackedEmbeddingModel(service);

    @Test
    void embedsAWholeBatchInOneCallAndKeepsInputOrder() {
        when(service.createEmbeddings(List.of("a", "b", "c")))
                .thenReturn(List.of(List.of(1.0, 2.0), List.of(3.0, 4.0), List.of(5.0, 6.0)));

        EmbeddingResponse response =
                model.call(new EmbeddingRequest(List.of("a", "b", "c"), null));

        assertThat(response.getResults()).hasSize(3);
        assertThat(response.getResults().get(0).getOutput()).containsExactly(1.0f, 2.0f);
        assertThat(response.getResults().get(1).getOutput()).containsExactly(3.0f, 4.0f);
        assertThat(response.getResults().get(2).getOutput()).containsExactly(5.0f, 6.0f);
        assertThat(response.getResults().stream().map(e -> e.getIndex()).toList())
                .as("index must be the position of the input the vector belongs to")
                .containsExactly(0, 1, 2);
        // One batched request, not one per text: VectorStore.add pushes whole batches here.
        verify(service, never()).createEmbedding(anyString());
    }

    @Test
    void refusesAResultCountThatDoesNotMatchTheInputCount() {
        when(service.createEmbeddings(anyList())).thenReturn(List.of(List.of(1.0)));

        assertThatThrownBy(() -> model.call(new EmbeddingRequest(List.of("a", "b"), null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refusing to guess");
    }

    @Test
    void refusesToHandAnEmptyVectorToTheVectorStore() {
        // EmbeddingService is fail-open by default and answers an outage with an empty
        // list. Written into the index that is indistinguishable from a document that
        // merely retrieves badly, so it must not get that far.
        when(service.createEmbeddings(anyList()))
                .thenReturn(List.of(List.of(1.0, 2.0), List.of()));

        assertThatThrownBy(() -> model.call(new EmbeddingRequest(List.of("a", "b"), null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("corrupts retrieval silently");
    }

    @Test
    void embedsASingleDocumentThroughTheSameService() {
        when(service.createEmbedding("hello")).thenReturn(List.of(0.5, -0.25));

        assertThat(model.embed(new Document("hello"))).containsExactly(0.5f, -0.25f);
    }

    @Test
    void refusesAnEmptyVectorOnTheSingleDocumentPathToo() {
        when(service.createEmbedding("hello")).thenReturn(List.of());

        assertThatThrownBy(() -> model.embed(new Document("hello")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Refusing to hand an empty vector");
    }

    @Test
    void reportsTheProvidersRealWidthNotSpringAisProbeDefault() {
        when(service.dimensions()).thenReturn(3072);

        assertThat(model.dimensions()).isEqualTo(3072);
        // Spring AI's default would embed a probe string and measure it; that would be a
        // network call on every vector-store operation, since AzureVectorStore asks for
        // dimensions() while building each observation context.
        verify(service, never()).createEmbedding(anyString());
    }

    @Test
    void anEmptyBatchProducesAnEmptyResponse() {
        when(service.createEmbeddings(List.of())).thenReturn(List.of());

        assertThat(model.call(new EmbeddingRequest(List.of(), null)).getResults()).isEmpty();
    }

    @Test
    void narrowsDoublesToFloatsWithoutReorderingOrDroppingComponents() {
        List<Double> wide = Arrays.asList(0.1, 0.2, 0.3, 0.4);
        when(service.createEmbedding("v")).thenReturn(wide);

        float[] out = model.embed(new Document("v"));

        assertThat(out).hasSize(4);
        for (int i = 0; i < wide.size(); i++) {
            assertThat(out[i]).isEqualTo(wide.get(i).floatValue());
        }
    }
}
