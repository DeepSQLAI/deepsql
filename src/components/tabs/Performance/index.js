/**
 * Performance Domain Components
 *
 * Includes: Slow Queries, Query Performance, EXPLAIN Plans, Active Queries, Lock Contention
 */

// Main Tabs
export { default as SlowQueryAnalysisTab } from './SlowQueryAnalysisTab'
export { default as QueryPerformanceTab } from './QueryPerformanceTab'
export { default as ExplainPlanTab } from './ExplainPlanTab'
export { default as ActiveQueryTab } from './ActiveQueryTab'
export { default as LockContentionTab } from './LockContentionTab'
export { default as PerformanceInsightsTab } from './PerformanceInsightsTab'
export { default as PerformanceDashboard } from './PerformanceDashboard'
export { default as QueryTrendsTab } from './QueryTrendsTab'

// SlowQueries submodule
export * from './SlowQueries'
