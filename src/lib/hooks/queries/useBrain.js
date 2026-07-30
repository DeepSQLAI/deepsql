/**
 * TanStack Query hooks for Brain API
 */

import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { brainAPI } from "@/lib/api/client";
import { queryKeys } from "@/lib/queryKeys";

// ==================== Understanding ====================

/**
 * Fetch brain understanding for a connection
 */
export function useBrainUnderstanding(connectionId) {
  return useQuery({
    queryKey: queryKeys.brain.understanding(connectionId),
    queryFn: () => brainAPI.getUnderstanding(connectionId),
    enabled: Boolean(connectionId),
  });
}

/**
 * Profile a connection
 */
export function useProfileConnection() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (connectionId) => brainAPI.profileConnection(connectionId),
    onSuccess: (_data, connectionId) => {
      queryClient.invalidateQueries({
        queryKey: queryKeys.brain.all(connectionId),
      });
    },
  });
}

/**
 * Rescan schema
 */
export function useRescanSchema() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (connectionId) => brainAPI.rescanSchema(connectionId),
    onSuccess: (_data, connectionId) => {
      queryClient.invalidateQueries({
        queryKey: queryKeys.brain.all(connectionId),
      });
      queryClient.invalidateQueries({
        queryKey: queryKeys.schema.all(connectionId),
      });
    },
  });
}

// ==================== Tasks ====================

/**
 * Fetch brain tasks
 */
export function useBrainTasks(connectionId, status = null) {
  return useQuery({
    queryKey: queryKeys.brain.tasks(connectionId, status),
    queryFn: () => brainAPI.getTasks(connectionId, status),
    enabled: Boolean(connectionId),
  });
}

/**
 * Create a brain task
 */
export function useCreateBrainTask() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: brainAPI.createTask,
    onSuccess: (data) => {
      queryClient.invalidateQueries({
        queryKey: queryKeys.brain.tasks(data.connectionId, null),
      });
    },
  });
}

/**
 * Update task status
 */
export function useUpdateTaskStatus() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: ({ taskId, status }) =>
      brainAPI.updateTaskStatus(taskId, status),
    onSuccess: () => {
      // Invalidate all task queries since we don't know the connectionId
      queryClient.invalidateQueries({ queryKey: ["brain"] });
    },
  });
}

// ==================== Notes ====================

/**
 * Fetch brain notes
 */
export function useBrainNotes(connectionId, params = {}) {
  return useQuery({
    queryKey: queryKeys.brain.notes(connectionId, params),
    queryFn: () => brainAPI.getNotes(connectionId, params),
    enabled: Boolean(connectionId),
  });
}

/**
 * Create a brain note
 */
export function useCreateBrainNote() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: brainAPI.createNote,
    onSuccess: (data) => {
      queryClient.invalidateQueries({
        queryKey: ["brain", data.connectionId, "notes"],
      });
    },
  });
}

/**
 * Update a brain note
 */
export function useUpdateBrainNote() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: ({ noteId, payload }) => brainAPI.updateNote(noteId, payload),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["brain"] });
    },
  });
}

/**
 * Delete a brain note
 */
export function useDeleteBrainNote() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: brainAPI.deleteNote,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["brain"] });
    },
  });
}

// ==================== Key Columns ====================

/**
 * Fetch key columns
 */
export function useKeyColumns(connectionId, params = {}) {
  return useQuery({
    queryKey: queryKeys.brain.keyColumns(connectionId, params),
    queryFn: () => brainAPI.getKeyColumns(connectionId, params),
    enabled: Boolean(connectionId),
  });
}

/**
 * Analyze key columns
 */
export function useAnalyzeKeyColumns() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (connectionId) => brainAPI.analyzeKeyColumns(connectionId),
    onSuccess: (_data, connectionId) => {
      queryClient.invalidateQueries({
        queryKey: ["brain", connectionId, "keyColumns"],
      });
    },
  });
}

/**
 * Acknowledge an anti-pattern
 */
export function useAcknowledgeAntiPattern() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: brainAPI.acknowledgeAntiPattern,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["brain"] });
    },
  });
}

// ==================== Schema Classification ====================

/**
 * Fetch schema classification
 */
export function useSchemaClassification(connectionId) {
  return useQuery({
    queryKey: queryKeys.brain.schemaClassification(connectionId),
    queryFn: () => brainAPI.getSchemaClassification(connectionId),
    enabled: Boolean(connectionId),
  });
}

/**
 * Analyze schema classification
 */
export function useAnalyzeSchemaClassification() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (connectionId) =>
      brainAPI.analyzeSchemaClassification(connectionId),
    onSuccess: (_data, connectionId) => {
      queryClient.invalidateQueries({
        queryKey: queryKeys.brain.schemaClassification(connectionId),
      });
      queryClient.invalidateQueries({
        queryKey: ["brain", connectionId, "tableClassifications"],
      });
    },
  });
}

/**
 * Fetch table classifications
 */
export function useTableClassifications(connectionId, role = null) {
  return useQuery({
    queryKey: queryKeys.brain.tableClassifications(connectionId, role),
    queryFn: () => brainAPI.getTableClassifications(connectionId, role),
    enabled: Boolean(connectionId),
  });
}

/**
 * Fetch fact tables
 */
export function useFactTables(connectionId) {
  return useQuery({
    queryKey: queryKeys.brain.factTables(connectionId),
    queryFn: () => brainAPI.getFactTables(connectionId),
    enabled: Boolean(connectionId),
  });
}

/**
 * Fetch dimension tables
 */
export function useDimensionTables(connectionId) {
  return useQuery({
    queryKey: queryKeys.brain.dimensionTables(connectionId),
    queryFn: () => brainAPI.getDimensionTables(connectionId),
    enabled: Boolean(connectionId),
  });
}

// ==================== Query Anti-Patterns ====================

/**
 * Fetch query anti-patterns
 */
export function useQueryAntiPatterns(connectionId, limit = null) {
  return useQuery({
    queryKey: queryKeys.brain.queryAntiPatterns(connectionId, limit),
    queryFn: () => brainAPI.getQueryAntiPatterns(connectionId, limit),
    enabled: Boolean(connectionId),
  });
}

/**
 * Analyze query anti-patterns
 */
export function useAnalyzeQueryAntiPatterns() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (connectionId) =>
      brainAPI.analyzeQueryAntiPatterns(connectionId),
    onSuccess: (_data, connectionId) => {
      queryClient.invalidateQueries({
        queryKey: ["brain", connectionId, "queryAntiPatterns"],
      });
    },
  });
}

// ==================== Workload Intelligence ====================

/**
 * Fetch workload profile
 */
export function useWorkloadProfile(connectionId) {
  return useQuery({
    queryKey: queryKeys.brain.workloadProfile(connectionId),
    queryFn: () => brainAPI.getWorkloadProfile(connectionId),
    enabled: Boolean(connectionId),
  });
}

/**
 * Fetch workload status
 */
export function useWorkloadStatus(connectionId) {
  return useQuery({
    queryKey: queryKeys.brain.workloadStatus(connectionId),
    queryFn: () => brainAPI.getWorkloadStatus(connectionId),
    enabled: Boolean(connectionId),
  });
}

// ==================== Cardinality Accuracy ====================

/**
 * Fetch cardinality accuracy metrics
 */
export function useCardinalityAccuracy(connectionId) {
  return useQuery({
    queryKey: queryKeys.brain.cardinalityAccuracy(connectionId),
    queryFn: () => brainAPI.getCardinalityAccuracy(connectionId),
    enabled: Boolean(connectionId),
  });
}

// ==================== Scalability Simulation ====================

/**
 * Fetch scalability simulations
 */
export function useScalabilitySimulations(connectionId) {
  return useQuery({
    queryKey: queryKeys.brain.scalabilitySimulations(connectionId),
    queryFn: () => brainAPI.getScalabilitySimulations(connectionId),
    enabled: Boolean(connectionId),
  });
}

/**
 * Fetch latest simulation
 */
export function useLatestSimulation(connectionId) {
  return useQuery({
    queryKey: queryKeys.brain.latestSimulation(connectionId),
    queryFn: async () => {
      try {
        return await brainAPI.getLatestSimulation(connectionId);
      } catch (err) {
        // err.status (not err.response.status) — the response interceptor
        // creates an enhanced Error with status as a direct property.
        if (err?.status === 404) {
          return null;
        }
        throw err;
      }
    },
    enabled: Boolean(connectionId),
  });
}

/**
 * Run scalability simulation
 */
export function useRunScalabilitySimulation() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: ({ connectionId, scenario }) =>
      brainAPI.runScalabilitySimulation(connectionId, scenario),
    onSuccess: (_data, { connectionId }) => {
      queryClient.invalidateQueries({
        queryKey: queryKeys.brain.scalabilitySimulations(connectionId),
      });
      queryClient.invalidateQueries({
        queryKey: queryKeys.brain.latestSimulation(connectionId),
      });
    },
  });
}

/**
 * Fetch table predictions for a simulation
 */
export function useTablePredictions(simulationId) {
  return useQuery({
    queryKey: queryKeys.brain.tablePredictions(simulationId),
    queryFn: () => brainAPI.getTablePredictions(simulationId),
    enabled: Boolean(simulationId),
  });
}

/**
 * Fetch high risk tables
 */
export function useHighRiskTables(connectionId) {
  return useQuery({
    queryKey: queryKeys.brain.highRiskTables(connectionId),
    queryFn: () => brainAPI.getHighRiskTables(connectionId),
    enabled: Boolean(connectionId),
  });
}

// ==================== Brain Score ====================

/**
 * Fetch latest brain score
 */
export function useBrainScore(connectionId) {
  return useQuery({
    queryKey: queryKeys.brain.score(connectionId),
    queryFn: async () => {
      try {
        return await brainAPI.getLatestScore(connectionId);
      } catch (err) {
        // Return null if score doesn't exist (404) rather than throwing.
        // err.status (not err.response.status) — the response interceptor
        // creates an enhanced Error with status as a direct property.
        if (err?.status === 404) {
          return null;
        }
        throw err;
      }
    },
    enabled: Boolean(connectionId),
  });
}

/**
 * Fetch brain score history
 */
export function useBrainScoreHistory(connectionId, limit = 10) {
  return useQuery({
    queryKey: queryKeys.brain.scoreHistory(connectionId, limit),
    queryFn: () => brainAPI.getScoreHistory(connectionId, limit),
    enabled: Boolean(connectionId),
  });
}

/**
 * Calculate brain score
 */
export function useCalculateBrainScore() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (connectionId) => brainAPI.calculateScore(connectionId),
    onSuccess: (_data, connectionId) => {
      queryClient.invalidateQueries({
        queryKey: queryKeys.brain.score(connectionId),
      });
      queryClient.invalidateQueries({
        queryKey: ["brain", connectionId, "score", "history"],
      });
    },
  });
}
