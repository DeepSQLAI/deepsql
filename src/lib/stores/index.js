/**
 * Zustand Stores - Centralized state management
 *
 * This module exports all Zustand stores for the application.
 * Stores replace React Context for better performance via selective subscriptions.
 *
 * Store Overview:
 * - useDashboardStore: Dashboard state (activeTab, connectionId, dashboardConfig)
 * - useConnectionStore: Connection selection with persistence
 * - useChatStore: Chat messages per connection/tab
 * - useUIStore: Transient UI state (modals, panels, menus)
 *
 * Usage Patterns:
 *
 * 1. Direct store access (for reading state):
 *    const activeTab = useDashboardStore((state) => state.activeTab)
 *
 * 2. Selector hooks (recommended for optimized re-renders):
 *    const activeTab = useActiveTab()
 *
 * 3. Actions hooks (stable references, don't cause re-renders):
 *    const { setActiveTab, setConnectionId } = useDashboardActions()
 *
 * 4. Outside React (for imperative updates):
 *    useDashboardStore.getState().setActiveTab('brain')
 */

// Dashboard Store
export {
  useDashboardStore,
  useActiveTab,
  useConnectionId,
  useDashboardConfig,
  useIsBuildingDashboard,
  useDashboardActions,
} from './useDashboardStore'

// Connection Store
export {
  useConnectionStore,
  useSelectedConnectionId,
  useLastConnections,
  useConnectionActions,
} from './useConnectionStore'

// Chat Store
export {
  useChatStore,
  usePrompt,
  useAttachedImage,
  useChatMessages,
  useChatId,
  useIsSending,
  useChatActions,
} from './useChatStore'

// UI Store
export {
  useUIStore,
  useModal,
  useModals,
  usePanel,
  usePanels,
  useMenu,
  useIsDragging,
  useIsCapturing,
  useExpandedImage,
  useUIActions,
} from './useUIStore'

/**
 * Legacy Compatibility Layer
 *
 * For gradual migration from DashboardContext, you can use this hook
 * that provides the same interface as useDashboard().
 *
 * Usage:
 *   // Old: const { activeTab, setActiveTab } = useDashboard()
 *   // New: const { activeTab, setActiveTab } = useDashboardCompat()
 */
export const useDashboardCompat = () => {
  const store = useDashboardStore()
  return {
    dashboardConfig: store.dashboardConfig,
    setDashboardConfig: store.setDashboardConfig,
    clearDashboard: store.clearDashboard,
    activeTab: store.activeTab,
    setActiveTab: store.setActiveTab,
    connectionId: store.connectionId,
    setConnectionId: store.setConnectionId,
    isBuildingDashboard: store.isBuildingDashboard,
    setIsBuildingDashboard: store.setIsBuildingDashboard,
  }
}
