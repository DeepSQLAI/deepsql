package com.dbaagent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Represents training data with its embedding for RAG retrieval
 * This is used for in-memory/Azure Cognitive Search operations
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrainingDataEmbedding {

    private String id;
    private String connectionId;
    private String content; // The text to embed (question, description, etc.)
    private String metadata; // JSON metadata for filtering
    private TrainingDataType type;
    private List<Double> embedding; // Vector embedding
    private Double score; // Similarity score during retrieval

    public enum TrainingDataType {
        QUERY_EXAMPLE,
        SCHEMA_DDL,
        DOCUMENTATION,
        BUSINESS_TERM,
        COMPANY_KNOWLEDGE,
        COLUMN_VALUES,
        QUERY_PATTERN,
        WORKLOAD_INSIGHT,
        CARDINALITY_INSIGHT,
        RELATIONSHIP
    }
}
