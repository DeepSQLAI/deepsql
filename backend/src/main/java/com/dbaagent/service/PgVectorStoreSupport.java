package com.dbaagent.service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Shared pgvector SQL and capability helpers for the local RAG store.
 *
 * <p>We store embeddings in {@code vector(N)} for fidelity, and switch the
 * ANN query/index strategy based on the effective embedding dimensions and
 * database capabilities. High-dimensional embeddings (for example 3072-dim
 * text-embedding-3-large) exceed pgvector's HNSW-on-vector limit, so we use
 * a {@code halfvec} cast for ANN search when that type is available.
 */
public final class PgVectorStoreSupport {

    public static final int DEFAULT_EMBEDDING_DIMENSIONS = 3072;
    public static final int MAX_VECTOR_HNSW_DIMENSIONS = 2000;

    private PgVectorStoreSupport() {
    }

    public enum AnnIndexMode {
        VECTOR_HNSW,
        HALFVEC_HNSW,
        NONE
    }

    public static String vectorColumnType(int embeddingDimensions) {
        return "vector(" + embeddingDimensions + ")";
    }

    public static boolean usesHalfvecAnn(int embeddingDimensions, boolean halfvecSupported) {
        return embeddingDimensions > MAX_VECTOR_HNSW_DIMENSIONS && halfvecSupported;
    }

    public static AnnIndexMode determineAnnIndexMode(int embeddingDimensions, boolean halfvecSupported) {
        if (embeddingDimensions <= MAX_VECTOR_HNSW_DIMENSIONS) {
            return AnnIndexMode.VECTOR_HNSW;
        }
        return halfvecSupported ? AnnIndexMode.HALFVEC_HNSW : AnnIndexMode.NONE;
    }

    public static boolean requiresAnnIndex(int embeddingDimensions, boolean halfvecSupported) {
        return determineAnnIndexMode(embeddingDimensions, halfvecSupported) != AnnIndexMode.NONE;
    }

    public static String createAnnIndexSql(int embeddingDimensions, boolean halfvecSupported) {
        return switch (determineAnnIndexMode(embeddingDimensions, halfvecSupported)) {
            case VECTOR_HNSW -> """
                    CREATE INDEX IF NOT EXISTS idx_rag_docs_embedding
                    ON rag_documents USING hnsw (embedding vector_cosine_ops)
                    WITH (m = 16, ef_construction = 64)
                    """;
            case HALFVEC_HNSW -> """
                    CREATE INDEX IF NOT EXISTS idx_rag_docs_embedding
                    ON rag_documents USING hnsw (((embedding)::halfvec(%d)) halfvec_cosine_ops)
                    WITH (m = 16, ef_construction = 64)
                    """.formatted(embeddingDimensions);
            case NONE -> "";
        };
    }

    public static String distanceExpression(
            int embeddingDimensions,
            boolean halfvecSupported,
            String parameterPlaceholder
    ) {
        if (usesHalfvecAnn(embeddingDimensions, halfvecSupported)) {
            return "((embedding)::halfvec(%d)) <=> %s::halfvec(%d)"
                    .formatted(embeddingDimensions, parameterPlaceholder, embeddingDimensions);
        }
        return "embedding <=> %s::vector".formatted(parameterPlaceholder);
    }

    public static String describeSearchMode(int embeddingDimensions, boolean halfvecSupported) {
        return switch (determineAnnIndexMode(embeddingDimensions, halfvecSupported)) {
            case VECTOR_HNSW -> "vector cosine search with HNSW ANN";
            case HALFVEC_HNSW -> "vector storage with halfvec-backed HNSW ANN";
            case NONE -> "exact vector cosine search (ANN unavailable for this embedding dimensionality)";
        };
    }

    /**
     * Converts a float embedding vector to pgvector's wire format: {@code [0.1,0.2,...]}.
     * Returns {@code null} when the list is null, empty, or mismatched with the configured dimensions.
     */
    public static String toVectorLiteral(List<Float> vector, int expectedDimensions) {
        if (vector == null || vector.isEmpty()) {
            return null;
        }
        if (expectedDimensions > 0 && vector.size() != expectedDimensions) {
            return null;
        }
        return "[" + vector.stream()
                .map(f -> Float.isNaN(f) || Float.isInfinite(f) ? "0" : String.valueOf(f))
                .collect(Collectors.joining(",")) + "]";
    }
}
