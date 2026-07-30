package com.dbaagent.dto;

import com.dbaagent.model.InferredTableRelationship;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DTO representing the result of relationship inference analysis.
 * Returned by the analyze endpoint.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RelationshipInferenceResult {

    /**
     * Connection ID that was analyzed.
     */
    private String connectionId;

    /**
     * Total number of relationships inferred.
     */
    private int totalRelationshipsInferred;

    /**
     * Number of high-confidence relationships (>= 50).
     */
    private int highConfidenceCount;

    /**
     * Number of new relationships found in this analysis.
     */
    private int newRelationshipsFound;

    /**
     * Number of existing relationships updated.
     */
    private int existingRelationshipsUpdated;

    /**
     * Total queries analyzed.
     */
    private int totalQueriesAnalyzed;

    /**
     * Queries from QueryLineage.
     */
    private int queriesFromLineage;

    /**
     * Queries from SlowQueryHistory.
     */
    private int queriesFromSlowLogs;

    /**
     * Queries that failed to parse.
     */
    private int parseFailures;

    /**
     * When the analysis was performed.
     */
    private LocalDateTime analyzedAt;

    /**
     * List of inferred relationships.
     */
    private List<InferredTableRelationship> relationships;

    /**
     * Summary message.
     */
    private String message;
}
