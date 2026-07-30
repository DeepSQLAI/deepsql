import { create } from 'zustand'
import { persist } from 'zustand/middleware'

/**
 * Connection Store - Manages database connection selection
 *
 * State:
 * - selectedConnectionId: Currently selected database connection
 * - lastConnections: Recent connections for quick access
 */
export const useConnectionStore = create(
  persist(
    (set, get) => ({
      // State
      selectedConnectionId: null,
      lastConnections: [], // Track recently used connections

      // Actions
      setSelectedConnectionId: (id) => {
        const { lastConnections } = get()

        // Update last connections list (max 5)
        const updated = id
          ? [id, ...lastConnections.filter((c) => c !== id)].slice(0, 5)
          : lastConnections

        set({
          selectedConnectionId: id,
          lastConnections: updated,
        })
      },

      clearSelectedConnection: () => {
        set({ selectedConnectionId: null })
      },

      // Remove a connection from history (e.g., when deleted)
      removeFromHistory: (id) => {
        const { selectedConnectionId, lastConnections } = get()
        set({
          selectedConnectionId: selectedConnectionId === id ? null : selectedConnectionId,
          lastConnections: lastConnections.filter((c) => c !== id),
        })
      },
    }),
    {
      name: 'connection-store',
      // Migrate from old localStorage key
      onRehydrateStorage: () => (state) => {
        // Migrate from old 'selectedConnectionId' key if needed
        if (typeof window !== 'undefined' && !state?.selectedConnectionId) {
          const oldId = localStorage.getItem('selectedConnectionId')
          if (oldId) {
            state?.setSelectedConnectionId(oldId)
            // Clean up old key after migration
            localStorage.removeItem('selectedConnectionId')
          }
        }
      },
    }
  )
)

// Selector hooks
export const useSelectedConnectionId = () =>
  useConnectionStore((state) => state.selectedConnectionId)
export const useLastConnections = () =>
  useConnectionStore((state) => state.lastConnections)

// Actions
export const useConnectionActions = () =>
  useConnectionStore((state) => ({
    setSelectedConnectionId: state.setSelectedConnectionId,
    clearSelectedConnection: state.clearSelectedConnection,
    removeFromHistory: state.removeFromHistory,
  }))
