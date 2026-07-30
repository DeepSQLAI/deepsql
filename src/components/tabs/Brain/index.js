/**
 * Brain Tab - Centralized exports
 * All Phases: Complete refactoring with advanced features
 */

// Hooks - Phase 1 & 2
export { useBrainData } from "./hooks/useBrainData";
export { useBrainTasks } from "./hooks/useBrainTasks";
export { useBrainNotes } from "./hooks/useBrainNotes";
export { useBrainTraining } from "./hooks/useBrainTraining";
export { useConnectionInfo } from "./hooks/useConnectionInfo";
export { useBrainState, ACTIONS } from "./hooks/useBrainState";
export { useWebSocket } from "./hooks/useWebSocket";
export {
  useKeyboardShortcuts,
  BRAIN_SHORTCUTS,
} from "./hooks/useKeyboardShortcuts";
export { useDarkMode } from "./hooks/useDarkMode";
export { useKeyColumns } from "./hooks/useKeyColumns";
export { useColumnValues } from "./hooks/useColumnValues";
export { useSchemaClassification } from "./hooks/useSchemaClassification";
export { useQueryAntiPatterns } from "./hooks/useQueryAntiPatterns";
export { useScalabilitySimulation } from "./hooks/useScalabilitySimulation";

// Components - Phase 1
export { BrainErrorBoundary } from "./BrainErrorBoundary";
export { BrainHeader } from "./BrainHeader";
export { BrainTabNavigation } from "./BrainTabNavigation";
export { BrainOverview, BrainOverviewHero } from "./components";
export { BrainAnalyticsLayout } from "./components/BrainAnalyticsLayout";
export { UnderstandingPanel } from "./UnderstandingPanel";
export { KeyColumnsPanel } from "./KeyColumnsPanel";
export { ColumnValuesPanel } from "./ColumnValuesPanel";
export { SchemaClassificationPanel } from "./SchemaClassificationPanel";
export { QueryAntiPatternsPanel } from "./QueryAntiPatternsPanel";
export { ScalabilityDashboardPanel } from "./ScalabilityDashboardPanel";
export { NeedsInputSection } from "./NeedsInputSection";
export { BrainTasks } from "./BrainTasks";
export { TrainingProgress, TrainingStatusBadge } from "./TrainingProgress";
export { TrainingHistory } from "./TrainingHistory";

// Schema Docs
export { SchemaDocsPanel } from "./SchemaDocs/SchemaDocsPanel";

// Brain 2.0 ML Components
export { WorkloadPanel } from "./WorkloadPanel";
export { QueryIntelligencePanel } from "./QueryIntelligencePanel";
export { ConfigTuningPanel } from "./ConfigTuningPanel";
export { MLHealthWidget } from "./MLHealthWidget";

// Modals - Phase 1
export { NoteModal } from "./modals/NoteModal";
export { AmbiguityModal } from "./modals/AmbiguityModal";
export { ActionConfirmModal } from "./modals/ActionConfirmModal";
export { BCNFModal } from "./modals/BCNFModal";
export { BCNFReviewModal } from "./modals/BCNFReviewModal";

// Phase 2: Performance Components
export {
  SkeletonCard,
  SkeletonRow,
  SkeletonGrid,
  SkeletonPanel,
} from "./LoadingSkeleton";
export { VirtualList } from "./VirtualList";

// Phase 3: UX Components
export { Pagination } from "./Pagination";

// Phase 4: Feature Components
export { BulkActions, QuickActions } from "./BulkActions";
export { MarkdownEditor } from "./MarkdownEditor";

// Phase 5: Advanced Components
export { NormalFormsPanel } from "./NormalFormsPanel";
export { SchemaERD } from "./SchemaERD";
export { SchemaERD3D, ERD3DErrorBoundary } from "./SchemaERD3D";

// Utilities - All Phases
export * from "./utils/formatUtils";
export * from "./utils/bcnfUtils";
export * from "./utils/statusUtils";
export * from "./utils/exportUtils";
export * from "./utils/normalFormUtils";
