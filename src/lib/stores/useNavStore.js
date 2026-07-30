import { create } from 'zustand'
import { persist } from 'zustand/middleware'

export const useNavStore = create(
  persist(
    (set, get) => ({
      activeSection: 'agent-chat', // first sidebar tab: Agent | dashboards | digest | brain | …
      isNavigating: false,
      // Immersive (chrome-less) mode — hides the app sidebar so a focused
      // surface (e.g. the dashboard builder) gets the full viewport. Transient,
      // never persisted: a reload always returns to the normal shell.
      immersive: false,
      setImmersive: (v) => set({ immersive: !!v }),
      setActiveSection: (section) => {
        if (get().activeSection === section) return
        set({ activeSection: section, isNavigating: true, immersive: false })
      },
      clearNavigating: () => set({ isNavigating: false }),
    }),
    {
      name: 'nav-store',
      partialize: (state) => ({ activeSection: state.activeSection }),
    }
  )
)

export const useActiveSection = () => useNavStore((s) => s.activeSection)
export const useIsNavigating = () => useNavStore((s) => s.isNavigating)
export const useImmersive = () => useNavStore((s) => s.immersive)
export const useSetImmersive = () => useNavStore((s) => s.setImmersive)
// Return the action directly (stable function reference — no new object, no infinite loop)
export const useSetActiveSection = () => useNavStore((s) => s.setActiveSection)
export const useClearNavigating = () => useNavStore((s) => s.clearNavigating)
