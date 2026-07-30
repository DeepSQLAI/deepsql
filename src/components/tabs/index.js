/**
 * Tab Components - Feature-Based Organization
 *
 * Import tabs from their feature domains:
 *   import { SlowQueryAnalysisTab } from '@/components/tabs/Performance'
 *   import { PlaybooksTab } from '@/components/tabs/Operations'
 *   import { SqlRunnerTab } from '@/components/tabs/Core'
 *
 * Or import all from this index:
 *   import { SlowQueryAnalysisTab, PlaybooksTab, SqlRunnerTab } from '@/components/tabs'
 */

// Core
export {
    SqlRunnerTab,
    PreviewTab,
    RagTrainingTab,
    TablesOverviewTab,
    DatabaseAdvisorTab,
} from './Core'

// Performance
export {
    SlowQueryAnalysisTab,
    QueryPerformanceTab,
    ExplainPlanTab,
    ActiveQueryTab,
    LockContentionTab,
    PerformanceInsightsTab,
    PerformanceDashboard,
} from './Performance'

// Monitoring
export {
    GrowthMonitoringTab,
    SentinelAnalyticsTab,
    AnalyticsTab,
} from './Monitoring'

// Operations
export {
    PlaybooksTab,
    ConfigurationTunerTab,
} from './Operations'

// Brain (already has its own index)
export * from './Brain'
