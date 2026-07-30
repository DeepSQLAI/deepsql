import { create } from 'zustand'
import { persist, subscribeWithSelector } from 'zustand/middleware'

/**
 * Dashboard Store - Manages dashboard-related global state
 * Replaces DashboardContext with better performance (selective subscriptions)
 *
 * State:
 * - activeTab: Currently active tab in workspace
 * - connectionId: Selected database connection ID
 * - dashboardConfig: Per-connection dashboard configuration
 * - isBuildingDashboard: Flag for dashboard builder mode
 */
export const useDashboardStore = create(
  subscribeWithSelector(
    persist(
      (set, get) => ({
        // State
        activeTab: 'rag-training', // Default to Brain tab (first visible tab)
        connectionId: null,
        dashboardConfig: null,
        isBuildingDashboard: false,

        // Actions
        setActiveTab: (tab) => {
          const currentTab = get().activeTab
          if (currentTab === tab) return
          set({ activeTab: tab })
        },

        setConnectionId: (id) => {
          const currentId = get().connectionId
          if (currentId === id) return

          // Load dashboard config for the new connection
          let newDashboardConfig = null
          if (id && typeof window !== 'undefined') {
            try {
              const saved = localStorage.getItem(`dashboard-${id}`)
              if (saved) {
                newDashboardConfig = JSON.parse(saved)
              }
            } catch (e) {
              console.error('Error loading dashboard config:', e)
            }
          }

          // Set both at once to avoid multiple renders
          set({ connectionId: id, dashboardConfig: newDashboardConfig })
        },

        setDashboardConfig: (config) => {
          const { connectionId } = get()
          set({ dashboardConfig: config })

          // Persist to localStorage
          if (config && connectionId && typeof window !== 'undefined') {
            localStorage.setItem(`dashboard-${connectionId}`, JSON.stringify(config))
          }
        },

        clearDashboard: () => {
          const { connectionId } = get()
          set({ dashboardConfig: null })

          if (connectionId && typeof window !== 'undefined') {
            localStorage.removeItem(`dashboard-${connectionId}`)
          }
        },

        setIsBuildingDashboard: (building) => {
          const current = get().isBuildingDashboard
          if (current === building) return
          set({ isBuildingDashboard: building })
        },
      }),
      {
        name: 'dashboard-store',
        // Only persist connectionId and activeTab
        partialize: (state) => ({
          connectionId: state.connectionId,
          activeTab: state.activeTab,
        }),
      }
    )
  )
)

// Selector hooks for optimized re-renders
export const useActiveTab = () => useDashboardStore((state) => state.activeTab)
export const useConnectionId = () => useDashboardStore((state) => state.connectionId)
export const useDashboardConfig = () => useDashboardStore((state) => state.dashboardConfig)
export const useIsBuildingDashboard = () => useDashboardStore((state) => state.isBuildingDashboard)

/**
 * Get action functions directly from the store.
 * These are stable references since they're defined in the store closure.
 * Call this OUTSIDE of render to get stable references.
 */
const getActions = () => {
  const store = useDashboardStore.getState()
  return {
    setActiveTab: store.setActiveTab,
    setConnectionId: store.setConnectionId,
    setDashboardConfig: store.setDashboardConfig,
    clearDashboard: store.clearDashboard,
    setIsBuildingDashboard: store.setIsBuildingDashboard,
  }
}

// Stable action references - these never change
let cachedActions = null
export const getDashboardActions = () => {
  if (!cachedActions) {
    cachedActions = getActions()
  }
  return cachedActions
}

/**
 * Hook to get actions - uses the cached stable references
 */
export const useDashboardActions = () => {
  return getDashboardActions()
}
