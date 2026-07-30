import { create } from 'zustand'

/**
 * UI Store - Manages transient UI state like modals, panels, and menus
 *
 * This store handles ephemeral UI state that doesn't need persistence.
 * For persisted UI preferences, see useDashboardStore.
 */
export const useUIStore = create((set, get) => ({
  // Modal state
  modals: {
    manageConnections: false,
    addConnection: false,
    feedback: false,
    settings: false,
    inviteCodes: false,
    userProfile: false,
  },

  // Panel state
  panels: {
    chat: true,
    sidebar: true,
    details: false,
  },

  // Menu state
  menus: {
    chat: false,
    user: false,
  },

  // Drag and drop state
  isDragging: false,

  // Screen capture state
  isCapturing: false,

  // Image preview state
  expandedImage: null,

  // Actions - Modals
  openModal: (modalName) =>
    set((state) => ({
      modals: { ...state.modals, [modalName]: true },
    })),

  closeModal: (modalName) =>
    set((state) => ({
      modals: { ...state.modals, [modalName]: false },
    })),

  toggleModal: (modalName) =>
    set((state) => ({
      modals: { ...state.modals, [modalName]: !state.modals[modalName] },
    })),

  closeAllModals: () =>
    set((state) => ({
      modals: Object.keys(state.modals).reduce((acc, key) => {
        acc[key] = false
        return acc
      }, {}),
    })),

  // Actions - Panels
  togglePanel: (panelName) =>
    set((state) => ({
      panels: { ...state.panels, [panelName]: !state.panels[panelName] },
    })),

  setPanel: (panelName, isOpen) =>
    set((state) => ({
      panels: { ...state.panels, [panelName]: isOpen },
    })),

  // Actions - Menus
  openMenu: (menuName) =>
    set((state) => ({
      menus: { ...state.menus, [menuName]: true },
    })),

  closeMenu: (menuName) =>
    set((state) => ({
      menus: { ...state.menus, [menuName]: false },
    })),

  toggleMenu: (menuName) =>
    set((state) => ({
      menus: { ...state.menus, [menuName]: !state.menus[menuName] },
    })),

  closeAllMenus: () =>
    set((state) => ({
      menus: Object.keys(state.menus).reduce((acc, key) => {
        acc[key] = false
        return acc
      }, {}),
    })),

  // Actions - Drag and Drop
  setIsDragging: (isDragging) => set({ isDragging }),

  // Actions - Screen Capture
  setIsCapturing: (isCapturing) => set({ isCapturing }),

  // Actions - Image Preview
  setExpandedImage: (image) => set({ expandedImage: image }),
  clearExpandedImage: () => set({ expandedImage: null }),
}))

// Selector hooks for modals
export const useModal = (modalName) =>
  useUIStore((state) => state.modals[modalName])

export const useModals = () => useUIStore((state) => state.modals)

// Selector hooks for panels
export const usePanel = (panelName) =>
  useUIStore((state) => state.panels[panelName])

export const usePanels = () => useUIStore((state) => state.panels)

// Selector hooks for menus
export const useMenu = (menuName) =>
  useUIStore((state) => state.menus[menuName])

// Other selectors
export const useIsDragging = () => useUIStore((state) => state.isDragging)
export const useIsCapturing = () => useUIStore((state) => state.isCapturing)
export const useExpandedImage = () => useUIStore((state) => state.expandedImage)

// Actions hook
export const useUIActions = () =>
  useUIStore((state) => ({
    openModal: state.openModal,
    closeModal: state.closeModal,
    toggleModal: state.toggleModal,
    closeAllModals: state.closeAllModals,
    togglePanel: state.togglePanel,
    setPanel: state.setPanel,
    openMenu: state.openMenu,
    closeMenu: state.closeMenu,
    toggleMenu: state.toggleMenu,
    closeAllMenus: state.closeAllMenus,
    setIsDragging: state.setIsDragging,
    setIsCapturing: state.setIsCapturing,
    setExpandedImage: state.setExpandedImage,
    clearExpandedImage: state.clearExpandedImage,
  }))
