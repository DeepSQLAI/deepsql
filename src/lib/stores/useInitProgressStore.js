import { create } from "zustand";
import { shallow } from "zustand/shallow";
import { isInitRunning } from "@/lib/initStage";

const useInitProgressStore = create((set) => ({
  connectionId: null,
  stage: null,
  progress: 0,
  message: "",
  startedAt: null,
  completedAt: null,
  error: null,
  stageTimings: {},
  stageDetails: {},
  hasLoaded: false,

  setInitProgress: (data) =>
    set((state) => ({
      connectionId: data.connectionId || state.connectionId,
      stage: data.currentStage,
      progress: data.progressPercent ?? state.progress,
      message: data.stageMessage || "",
      startedAt: data.startedAt || state.startedAt,
      completedAt: data.completedAt || null,
      error: data.errorMessage || null,
      stageTimings: data.stageTimings || state.stageTimings,
      stageDetails:
        "stageDetails" in data ? (data.stageDetails ?? {}) : state.stageDetails,
      hasLoaded: true,
    })),

  markLoaded: () => set({ hasLoaded: true }),

  clearInit: () =>
    set({
      connectionId: null,
      stage: null,
      progress: 0,
      message: "",
      startedAt: null,
      completedAt: null,
      error: null,
      stageTimings: {},
      stageDetails: {},
      hasLoaded: false,
    }),
}));

// Selectors — shallow comparison prevents unnecessary re-renders
export const useInitProgress = () =>
  useInitProgressStore(
    (s) => ({
      stage: s.stage,
      progress: s.progress,
      message: s.message,
      error: s.error,
      stageTimings: s.stageTimings,
      stageDetails: s.stageDetails,
      startedAt: s.startedAt,
      hasLoaded: s.hasLoaded,
    }),
    shallow,
  );
// Scoped to connectionId — returns false if store holds state for a different connection
export const useIsInitActive = (connectionId) =>
  useInitProgressStore(
    (s) =>
      !!s.stage &&
      s.connectionId === connectionId &&
      isInitRunning(s.stage),
  );
export default useInitProgressStore;
