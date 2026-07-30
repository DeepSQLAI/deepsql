'use client'

import { useMemo } from 'react'
import {
  useWorkloadProfile,
  useWorkloadStatus,
  useSchemaClassification,
  useQueryAntiPatterns,
  useCardinalityAccuracy,
  useLatestSimulation,
  useHighRiskTables,
} from '@/lib/hooks/queries'

const hasValue = (value) => value !== null && value !== undefined

const formatTimeAgo = (dateStr) => {
  if (!dateStr) return null
  const date = new Date(dateStr)
  if (Number.isNaN(date.getTime())) return null
  const now = new Date()
  const diffMs = now - date
  const diffMins = Math.floor(diffMs / 60000)
  const diffHours = Math.floor(diffMs / 3600000)
  const diffDays = Math.floor(diffMs / 86400000)

  if (diffMins < 1) return 'Just now'
  if (diffMins < 60) return `${diffMins}m ago`
  if (diffHours < 24) return `${diffHours}h ago`
  return `${diffDays}d ago`
}

export function useBrainAnalyticsSummary(connectionId) {
  const workloadProfile = useWorkloadProfile(connectionId)
  const workloadStatus = useWorkloadStatus(connectionId)
  const schemaClassification = useSchemaClassification(connectionId)
  const antiPatterns = useQueryAntiPatterns(connectionId, null)
  const accuracy = useCardinalityAccuracy(connectionId)
  const latestSimulation = useLatestSimulation(connectionId)
  const highRiskTables = useHighRiskTables(connectionId)

  const antiPatternCounts = useMemo(() => {
    if (!antiPatterns.data) return null
    return antiPatterns.data.reduce(
      (acc, pattern) => {
        const severity = (pattern?.severity || '').toUpperCase()
        acc.total += 1
        if (severity === 'CRITICAL') acc.critical += 1
        if (severity === 'HIGH') acc.high += 1
        if (severity === 'MEDIUM') acc.medium += 1
        if (severity === 'LOW') acc.low += 1
        return acc
      },
      { total: 0, critical: 0, high: 0, medium: 0, low: 0 }
    )
  }, [antiPatterns.data])

  const workload = useMemo(() => {
    const profile = workloadProfile.data || null
    const status = workloadStatus.data || null
    return {
      type: profile?.workloadType || null,
      confidence: hasValue(profile?.classificationConfidence)
        ? profile.classificationConfidence
        : hasValue(profile?.confidence)
          ? profile.confidence
          : null,
      subtype: profile?.workloadSubtype || null,
      lastUpdated: status?.profileLastUpdated || null,
      lastSnapshotAt: status?.lastSnapshotAt || null,
      readyToCharacterize: Boolean(status?.readyToCharacterize),
      snapshotCount: hasValue(status?.snapshotCount) ? status.snapshotCount : null,
      minSnapshotsRequired: hasValue(status?.minSnapshotsRequired) ? status.minSnapshotsRequired : null,
    }
  }, [workloadProfile.data, workloadStatus.data])

  const schema = useMemo(() => {
    const data = schemaClassification.data || null
    return {
      pattern: data?.globalPattern || null,
      confidence: hasValue(data?.confidenceScore) ? data.confidenceScore : null,
      avgHealthScore: hasValue(data?.avgHealthScore) ? data.avgHealthScore : null,
      tablesWithAntiPatterns: hasValue(data?.tablesWithAntiPatterns) ? data.tablesWithAntiPatterns : null,
      totalTables: hasValue(data?.totalTables) ? data.totalTables : null,
      hasCycles: Boolean(data?.hasCycles),
    }
  }, [schemaClassification.data])

  const query = useMemo(() => {
    const data = accuracy.data || null
    return {
      overallAccuracy: hasValue(data?.overallAccuracy) ? data.overallAccuracy : null,
      totalEstimates: hasValue(data?.totalEstimates) ? data.totalEstimates : null,
      totalExecutions: hasValue(data?.totalExecutions) ? data.totalExecutions : null,
      needsExplainData: Boolean(data?.needsExplainData),
    }
  }, [accuracy.data])

  const scalability = useMemo(() => {
    const latest = latestSimulation.data || null
    const riskyTables = highRiskTables.data || []
    const worstTable = riskyTables.length > 0
      ? riskyTables[0]?.tableName || riskyTables[0]?.name || null
      : null
    return {
      riskLevel: latest?.overallRiskLevel || null,
      scenario: latest?.growthScenario || null,
      score: hasValue(latest?.scalabilityScore) ? latest.scalabilityScore : null,
      highRiskCount: riskyTables.length,
      worstTable,
    }
  }, [latestSimulation.data, highRiskTables.data])

  const freshness = useMemo(() => {
    const profileUpdate = formatTimeAgo(workload.lastUpdated)
    const snapshotUpdate = formatTimeAgo(workload.lastSnapshotAt)
    if (profileUpdate) {
      return { label: 'Profile updated', value: profileUpdate }
    }
    if (snapshotUpdate) {
      return { label: 'Latest snapshot', value: snapshotUpdate }
    }
    return null
  }, [workload.lastUpdated, workload.lastSnapshotAt])

  const loading = [
    workloadProfile.isLoading,
    workloadStatus.isLoading,
    schemaClassification.isLoading,
    antiPatterns.isLoading,
    accuracy.isLoading,
    latestSimulation.isLoading,
    highRiskTables.isLoading,
  ].some(Boolean)

  return {
    workload,
    schema,
    query,
    antiPatterns: antiPatternCounts,
    scalability,
    freshness,
    loading,
  }
}
