package com.dbaagent.model;

import com.azure.search.documents.indexes.SearchableField;
import com.azure.search.documents.indexes.SimpleField;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Azure AI Search document model for training data
 * Supports hybrid search with both vector and keyword search
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class TrainingDataSearchDocument {

    @SimpleField(isKey = true, isFilterable = true)
    @JsonProperty("id")
    private String id;

    @SimpleField(isFilterable = true)
    @JsonProperty("connectionId")
    private String connectionId;

    @SearchableField(isFilterable = true)
    @JsonProperty("type")
    private String type; // SCHEMA_DDL, QUERY_EXAMPLE, DOCUMENTATION, COLUMN_VALUES, QUERY_PATTERN, WORKLOAD_INSIGHT, CARDINALITY_INSIGHT

    @SearchableField(analyzerName = "en.lucene")
    @JsonProperty("content")
    private String content;

    @SearchableField
    @JsonProperty("naturalLanguage")
    private String naturalLanguage; // For query examples

    @SearchableField(analyzerName = "en.microsoft")
    @JsonProperty("sql")
    private String sql; // For query examples

    @SearchableField
    @JsonProperty("objectName")
    private String objectName; // For documentation and schema

    @SimpleField(isFilterable = true)
    @JsonProperty("tableName")
    private String tableName; // Resolved table name for Stage 2 filtering (bare or schema.table)

    @SearchableField(analyzerName = "en.lucene")
    @JsonProperty("description")
    private String description; // For documentation

    @SearchableField
    @JsonProperty("businessTerms")
    private String businessTerms; // For documentation

    @SimpleField(isFilterable = true)
    @JsonProperty("tablesUsed")
    private String tablesUsed; // Comma-separated list of tables

    @SimpleField
    @JsonProperty("dbType")
    private String dbType;

    @JsonProperty("contentVector")
    private List<Float> contentVector; // Embedding vector for semantic search

    @SimpleField
    @JsonProperty("metadata")
    private String metadata; // JSON metadata

    @SimpleField(isFilterable = true, isSortable = true)
    @JsonProperty("createdAt")
    private String createdAt;

    @SimpleField
    @JsonProperty("successful")
    private Boolean successful; // For query examples

    @SimpleField
    @JsonProperty("executionTimeMs")
    private Long executionTimeMs; // For query examples

    @JsonProperty("@search.score")
    private Double searchScore; // Retrieved relevance score from Azure AI Search
}
