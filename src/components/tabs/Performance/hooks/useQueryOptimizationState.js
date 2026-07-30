"use client";

import { useState, useEffect, useMemo, useRef } from "react";
import {
  useBenchmarkCandidates,
  useCachedOptimizations,
  useOptimizationCandidates,
} from "@/lib/hooks/queries";
import { slowQueriesAPI } from "@/lib/api/client";

/**
 * Generates a React key for a query row. Used for selection state and React `key` prop.
 * Falls back to index to guarantee uniqueness.
 */
export function rowKey(query, fallbackIndex) {
  if (!query) return fallbackIndex;
  return (
    query.queryId ??
    query.fingerprint ??
    query.normalizedQuery ??
    query.queryText ??
    fallbackIndex
  );
}

/**
 * Returns a stable API fingerprint for optimization calls.
 * Returns null if no queryId — never falls back to SQL text.
 */
export function queryFingerprint(query) {
  if (!query) return null;
  return query.queryId ?? null;
}

/**
 * Shared optimization state for both Performance and Monitoring tabs.
 *
 * @param {string} connectionId
 * @param {Array} queries - list of slow query objects
 * @param {object} [options]
 * @param {boolean} [options.includeCandidates=true] - false for Monitoring mode (disables candidate fetches)
 */
export function useQueryOptimizationState(connectionId, queries, options = {}) {
  const { includeCandidates = true } = options;

  // Optimization state
  const [optimizationResults, setOptimizationResults] = useState({});
  const [optimizingQueryKey, setOptimizingQueryKey] = useState(null);
  const [optimizationSteps, setOptimizationSteps] = useState({});  // per-key step arrays
  const [selectedFingerprint, setSelectedFingerprint] = useState(null);

  // Track active EventSource connections so we can close them on unmount
  const eventSourcesRef = useRef({});

  // Mutations
  const benchmarkCandidatesMutation = useBenchmarkCandidates();

  // Extract fingerprints for cached optimization lookup
  const queryFingerprints = useMemo(() => {
    if (!queries) return [];
    return queries.map((q) => q.queryId).filter(Boolean);
  }, [queries]);

  // Fetch cached optimizations for all queries
  const { data: cachedOptimizationsData } = useCachedOptimizations(
    connectionId,
    queryFingerprints,
  );

  // Only wire optimization candidates when includeCandidates is true
  const {
    data: optimizationCandidatesData,
    refetch: refetchOptimizationCandidates,
  } = useOptimizationCandidates(
    includeCandidates ? connectionId : null,
    includeCandidates ? selectedFingerprint : null,
  );

  // Merge cached optimizations into state
  useEffect(() => {
    if (
      cachedOptimizationsData &&
      Object.keys(cachedOptimizationsData).length > 0
    ) {
      setOptimizationResults((prev) => {
        const next = { ...prev };
        let hasNew = false;
        Object.entries(cachedOptimizationsData).forEach(([fp, result]) => {
          if (!next[fp]) {
            next[fp] = result;
            hasNew = true;
          }
        });
        return hasNew ? next : prev;
      });
    }
  }, [cachedOptimizationsData]);

  // Clear optimization results when connection changes
  useEffect(() => {
    setOptimizationResults({});
    setSelectedFingerprint(null);
  }, [connectionId]);

  const getOptimizationKey = (query) => {
    if (!query) return null;
    return (
      query.queryId ??
      query.normalizedQuery ??
      query.queryText?.substring(0, 50) ??
      query.queryText ??
      null
    );
  };

  const handleOptimize = (query, options = {}) => {
    const key = getOptimizationKey(query);
    if (!key) return;

    // Close any existing SSE for this query
    if (eventSourcesRef.current[key]) {
      eventSourcesRef.current[key].close();
      delete eventSourcesRef.current[key];
    }

    setOptimizingQueryKey(key);
    setOptimizationSteps((prev) => ({ ...prev, [key]: [] }));

    const hasPgPlaceholders = (sql) => /\$\d+/.test(sql || "");
    const normalizedQuery = query.normalizedQuery || query.queryText;
    const actualQuery =
      query.sampleQuery ||
      (query.queryText &&
      !query.queryText.includes("?") &&
      !hasPgPlaceholders(query.queryText)
        ? query.queryText
        : null);

    const streamUrl = slowQueriesAPI.buildOptimizeStreamUrl({
      connectionId,
      queryText: normalizedQuery,
      sampleQuery: actualQuery,
      queryId: query.queryId || null,
      avgExecutionTimeMs: query.avgExecutionTimeMs,
      forceRefresh: true,  // streaming always runs fresh
    });

    const es = new EventSource(streamUrl);
    eventSourcesRef.current[key] = es;

    es.addEventListener("step", (e) => {
      try {
        const step = JSON.parse(e.data);
        setOptimizationSteps((prev) => ({
          ...prev,
          [key]: [...(prev[key] || []), step],
        }));
      } catch {/* ignore malformed events */}
    });

    es.addEventListener("result", (e) => {
      try {
        const result = JSON.parse(e.data);
        setOptimizationResults((prev) => ({ ...prev, [key]: result }));
      } catch {/* ignore */}
      es.close();
      delete eventSourcesRef.current[key];
      setOptimizingQueryKey(null);

      if (includeCandidates && query?.queryId && selectedFingerprint === query.queryId) {
        refetchOptimizationCandidates?.();
      }
    });

    es.addEventListener("error", (e) => {
      let message = "Optimization failed";
      try { message = JSON.parse(e.data)?.message || message; } catch {/* ok */}
      setOptimizationResults((prev) => ({ ...prev, [key]: { error: message } }));
      es.close();
      delete eventSourcesRef.current[key];
      setOptimizingQueryKey(null);
    });

    // Catch network errors (server down, CORS, etc.)
    es.onerror = () => {
      // onerror fires on every retry attempt when the stream ends naturally; only
      // treat it as a real error if we haven't received a 'result' event yet.
      if (eventSourcesRef.current[key]) {
        setOptimizationResults((prev) => ({
          ...prev,
          [key]: { error: "Connection to optimization service failed" },
        }));
        es.close();
        delete eventSourcesRef.current[key];
        setOptimizingQueryKey(null);
      }
    };
  };

  const handleBenchmark = async (query) => {
    if (!connectionId || !query?.queryId) return;
    try {
      await benchmarkCandidatesMutation.mutateAsync({
        connectionId,
        queryFingerprint: query.queryId,
        runs: 3,
      });
    } catch (err) {
      console.error("Error benchmarking candidates:", err);
    }
  };

  const getOptimizationForQuery = (query) => {
    const key = getOptimizationKey(query);
    return key ? (optimizationResults[key] ?? null) : null;
  };

  const isOptimizingQuery = (query) => {
    const key = getOptimizationKey(query);
    return key ? optimizingQueryKey === key : false;
  };

  // Clean up all SSE connections when hook unmounts or connectionId changes
  useEffect(() => {
    return () => {
      Object.values(eventSourcesRef.current).forEach((es) => es.close());
      eventSourcesRef.current = {};
    };
  }, [connectionId]);

  return {
    optimizationResults,
    optimizingQueryKey,
    optimizationSteps,
    handleOptimize,
    handleBenchmark,
    getOptimizationForQuery,
    isOptimizingQuery,
    selectedFingerprint,
    setSelectedFingerprint,
    optimizationCandidates: includeCandidates
      ? optimizationCandidatesData
      : null,
    isBenchmarking: benchmarkCandidatesMutation.isPending,
    rowKey,
    queryFingerprint,
    // Internal helpers exposed for Performance tab compatibility
    getOptimizationKey,
    setOptimizationResults,
    refetchOptimizationCandidates: includeCandidates
      ? refetchOptimizationCandidates
      : undefined,
  };
}
