package com.dbaagent.provider.api;

import com.dbaagent.model.ExplainPlanAnalysis;
import com.dbaagent.model.ExplainPlanNode;
import com.dbaagent.model.IndexRecommendation;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * Provider interface for query explain plan analysis.
 * Handles execution of EXPLAIN commands and parsing of results.
 */
public interface ExplainPlanProvider {

    /**
     * Get the database type this provider handles.
     * @return The database type (e.g., "mysql", "postgres")
     */
    String getDatabaseType();

    /**
     * Execute EXPLAIN for a query.
     * @param connection The database connection
     * @param query The query to explain
     * @param analyze Whether to use EXPLAIN ANALYZE (executes query)
     * @return The raw explain results
     * @throws SQLException If a database error occurs
     */
    List<Map<String, Object>> executeExplain(Connection connection, String query, boolean analyze) throws SQLException;

    /**
     * Parse the explain result into a plan tree.
     * @param explainResults The raw explain results
     * @return The parsed plan tree
     */
    ExplainPlanNode parseExplainResult(List<Map<String, Object>> explainResults);

    /**
     * Detect performance issues in the plan tree.
     * @param planTree The parsed plan tree
     * @param analysis The analysis object to populate with issues
     */
    void detectIssues(ExplainPlanNode planTree, ExplainPlanAnalysis analysis);

    /**
     * Generate index recommendations based on the plan.
     * @param planTree The parsed plan tree
     * @param query The original query
     * @return List of index recommendations
     */
    List<IndexRecommendation> generateIndexRecommendations(ExplainPlanNode planTree, String query);

    /**
     * Get the EXPLAIN query format string for this database.
     * @param analyze Whether to include ANALYZE option
     * @return The EXPLAIN format prefix
     */
    String getExplainFormat(boolean analyze);

    /**
     * Perform complete query analysis.
     * @param connection The database connection
     * @param query The query to analyze
     * @param analyze Whether to use EXPLAIN ANALYZE
     * @param analysis The analysis object to populate
     * @throws SQLException If a database error occurs
     */
    void analyzeQuery(Connection connection, String query, boolean analyze, ExplainPlanAnalysis analysis) throws SQLException;
}
