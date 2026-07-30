package com.dbaagent.llm.spring;

import com.dbaagent.service.EmbeddingService;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapts {@link EmbeddingService} to Spring AI's {@link EmbeddingModel}, so the
 * auto-configured {@code VectorStore} and {@code QuestionAnswerAdvisor} embed through
 * exactly the provider the rest of the application uses.
 *
 * <p>Without this, Spring AI auto-configures its own OpenAI-backed model from
 * {@code spring.ai.openai.*}, giving the RAG read path a second, independently
 * configured embedding source. A store written by one embedding model and read through
 * another raises no error: pgvector's {@code text}-column fallback has no dimension
 * constraint, cosine similarity still returns a number, and retrieval quality collapses
 * with nothing in the logs.
 */
public class ProviderBackedEmbeddingModel implements EmbeddingModel {

    private final EmbeddingService embeddingService;

    public ProviderBackedEmbeddingModel(EmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
    }

    /**
     * One batched call, not one call per input.
     *
     * <p>{@code VectorStore.add} hands whole batches down this path, so per-text calls
     * would multiply latency and rate-limit pressure by the batch size.
     * {@link EmbeddingService#createEmbeddings} guarantees the returned list is
     * positionally aligned with its input, which is what lets the index be used verbatim.
     */
    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> inputs = request.getInstructions();
        List<List<Double>> vectors = embeddingService.createEmbeddings(inputs);
        if (vectors.size() != inputs.size()) {
            throw new IllegalStateException(
                    "Embedding service returned " + vectors.size() + " vectors for "
                    + inputs.size() + " inputs; refusing to guess which input each belongs to");
        }
        List<Embedding> embeddings = new ArrayList<>(inputs.size());
        for (int i = 0; i < vectors.size(); i++) {
            embeddings.add(new Embedding(toFloats(vectors.get(i)), i));
        }
        return new EmbeddingResponse(List.copyOf(embeddings));
    }

    @Override
    public float[] embed(Document document) {
        return toFloats(embeddingService.createEmbedding(document.getText()));
    }

    /**
     * The provider's real width, not a guess.
     *
     * <p>Spring AI's default implementation embeds a probe string and measures it; the
     * Azure vector store calls this on every add and every search to tag its observation,
     * so the measured value is cached in {@link EmbeddingService} rather than re-probed.
     */
    @Override
    public int dimensions() {
        return embeddingService.dimensions();
    }

    /**
     * An empty vector is the one output that must never reach a vector store.
     *
     * <p>{@code EmbeddingService} is fail-open by default: a provider outage yields an
     * empty list rather than an exception, which is the right trade for a service that
     * returns vectors to Java callers. On this path it is not — a zero-width vector is
     * written into the index without complaint and is indistinguishable, later, from a
     * document that simply retrieves badly. Failing loudly here is the last point at
     * which that corruption is still detectable.
     */
    private static float[] toFloats(List<Double> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalStateException(
                    "Embedding provider returned no vector. Refusing to hand an empty "
                    + "vector to the vector store: it is stored without error and "
                    + "corrupts retrieval silently. See the preceding EmbeddingService "
                    + "log line for the provider and error category.");
        }
        float[] out = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            out[i] = values.get(i).floatValue();
        }
        return out;
    }
}
