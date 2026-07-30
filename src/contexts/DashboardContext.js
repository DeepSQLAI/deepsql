'use client'

import { useDashboardStore } from '@/lib/stores'

/**
 * DashboardContext - Legacy compatibility layer over Zustand store
 *
 * This hook now delegates directly to useDashboardStore.
 * The DashboardProvider is NO LONGER NEEDED and has been removed.
 *
 * Migration Guide:
 * - Old: const { activeTab, setActiveTab } = useDashboard()
 * - New: const activeTab = useActiveTab()
 *        const { setActiveTab } = useDashboardActions()
 *
 * Or use the direct store:
 * - New: const activeTab = useDashboardStore((s) => s.activeTab)
 *
 * Benefits of migrating to direct Zustand usage:
 * - Better performance (selective subscriptions)
 * - No provider needed
 * - Simpler component trees
 *
 * @deprecated Use imports from '@/lib/stores' instead
 */
export function useDashboard() {
    // Delegate directly to Zustand store (reactive)
    const store = useDashboardStore()

    return {
        // State
        dashboardConfig: store.dashboardConfig,
        activeTab: store.activeTab,
        connectionId: store.connectionId,
        isBuildingDashboard: store.isBuildingDashboard,

        // Actions
        setDashboardConfig: store.setDashboardConfig,
        clearDashboard: store.clearDashboard,
        setActiveTab: store.setActiveTab,
        setConnectionId: store.setConnectionId,
        setIsBuildingDashboard: store.setIsBuildingDashboard,
    }
}

/**
 * @deprecated DashboardProvider is no longer needed. Zustand stores work without providers.
 * This is kept for backward compatibility but is a no-op.
 */
export function DashboardProvider({ children }) {
    // No-op wrapper - Zustand doesn't need providers
    return children
}
