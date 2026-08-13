package com.dbaagent.model;

public enum InitStage {
    SCHEMA_SCAN,
    DATA_SAMPLING,
    KEY_COLUMN_ANALYSIS,
    COLUMN_VALUE_COLLECTION,
    INFERRED_RELATIONSHIPS,
    SCHEMA_CLASSIFICATION,
    AI_DESCRIPTION,
    RAG_EMBEDDING,
    BRAIN_ANALYSIS,
    SEMANTIC_MODELING,
    /**
     * Schema coverage incomplete — Brain must not claim Complete 100%.
     * Surfaced when indexed base tables are far below live user tables (W2b).
     */
    NEEDS_ATTENTION,
    COMPLETED,
    FAILED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == NEEDS_ATTENTION;
    }

    public InitStage next() {
        return switch (this) {
            case SCHEMA_SCAN -> DATA_SAMPLING;
            case DATA_SAMPLING -> KEY_COLUMN_ANALYSIS;
            case KEY_COLUMN_ANALYSIS -> COLUMN_VALUE_COLLECTION;
            case COLUMN_VALUE_COLLECTION -> INFERRED_RELATIONSHIPS;
            case INFERRED_RELATIONSHIPS -> SCHEMA_CLASSIFICATION;
            case SCHEMA_CLASSIFICATION -> AI_DESCRIPTION;
            case AI_DESCRIPTION -> RAG_EMBEDDING;
            case RAG_EMBEDDING -> BRAIN_ANALYSIS;
            case BRAIN_ANALYSIS -> SEMANTIC_MODELING;
            case SEMANTIC_MODELING, COMPLETED, FAILED, NEEDS_ATTENTION -> null;
        };
    }
}
