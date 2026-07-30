/**
 * TanStack Query hooks for Growth Monitoring API
 */

import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { growthMonitoringAPI, schemaAPI } from "@/lib/api/client";
import { queryKeys } from "@/lib/queryKeys";

/**
 * Fetch growth history
 */
export function useGrowthHistory(connectionId, tableName = null, days = 7) {
  return useQuery({
    queryKey: queryKeys.growth.history(connectionId, tableName, days),
    queryFn: () =>
      growthMonitoringAPI.getGrowthHistory(connectionId, tableName, days),
    enabled: Boolean(connectionId),
    staleTime: 5 * 60 * 1000,
  });
}

/**
 * Fetch growth anomalies
 */
export function useGrowthAnomalies(
  connectionId,
  { tableName = null, unacknowledgedOnly = false, days = 30 } = {},
) {
  return useQuery({
    queryKey: queryKeys.growth.anomalies(
      connectionId,
      tableName,
      unacknowledgedOnly,
      days,
    ),
    queryFn: () =>
      growthMonitoringAPI.getAnomalies(
        connectionId,
        tableName,
        unacknowledgedOnly,
        days,
      ),
    enabled: Boolean(connectionId),
    staleTime: 5 * 60 * 1000,
  });
}

/**
 * Acknowledge a growth anomaly
 */
export function useAcknowledgeGrowthAnomaly() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: ({ anomalyId, acknowledgedBy = "user" }) =>
      growthMonitoringAPI.acknowledgeAnomaly(anomalyId, acknowledgedBy),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["growth"] });
    },
  });
}

/**
 * Fetch growth alert configuration
 */
export function useGrowthConfiguration(connectionId, tableName = null) {
  return useQuery({
    queryKey: queryKeys.growth.config(connectionId, tableName),
    queryFn: () =>
      growthMonitoringAPI.getConfiguration(connectionId, tableName),
    enabled: Boolean(connectionId),
  });
}

/**
 * Save growth alert configuration
 */
export function useSaveGrowthConfiguration() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: growthMonitoringAPI.saveConfiguration,
    onSuccess: (data) => {
      if (data?.connectionId) {
        queryClient.invalidateQueries({
          queryKey: ["growth", data.connectionId, "config"],
        });
      }
    },
  });
}

/**
 * Fetch growth trends
 */
export function useGrowthTrends(connectionId, tableName = null, days = 30) {
  return useQuery({
    queryKey: queryKeys.growth.trends(connectionId, tableName, days),
    queryFn: () =>
      growthMonitoringAPI.getGrowthTrends(connectionId, tableName, days),
    enabled: Boolean(connectionId),
  });
}

/**
 * Trigger manual capture
 */
export function useManualCapture() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (connectionId) =>
      growthMonitoringAPI.manualCapture(connectionId),
    onSuccess: (_data, connectionId) => {
      queryClient.invalidateQueries({
        queryKey: ["growth", connectionId],
      });
    },
  });
}

/**
 * Trigger manual cleanup
 */
export function useManualCleanup() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: growthMonitoringAPI.manualCleanup,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["growth"] });
    },
  });
}

/**
 * Fetch schema changes for a connection
 */
export function useSchemaChanges(connectionId) {
  return useQuery({
    queryKey: queryKeys.schema.changes(connectionId),
    queryFn: () => schemaAPI.getSchemaChanges(connectionId),
    enabled: Boolean(connectionId),
    staleTime: 5 * 60 * 1000,
  });
}
