"use client";

import { useEffect, useMemo } from "react";
import { BarChart2, Play, Sparkles, PanelLeftOpen } from "lucide-react";
import { InitProgressIndicator } from "./InitProgressIndicator";
import { useAuth, PERMISSIONS } from "@/hooks/useAuth";
import { useActiveTab, useDashboardStore } from "@/lib/stores";
import PermissionGuard from "./PermissionGuard";
import SqlRunnerTab from "./tabs/Core/SqlRunnerTab";
import AnalyticsTab from "./tabs/Monitoring/AnalyticsTab";
import RagTrainingTab from "./tabs/Core/RagTrainingTab";
import styles from "./Workspace.module.css";

// Dashboard (Preview) tab hidden for V1 release. Set to true to re-enable.
const SHOW_DASHBOARD_TAB = false;

// Define tabs outside component to prevent recreation on every render
const ALL_TABS = [
  {
    id: "rag-training",
    icon: Sparkles,
    label: "Brain",
    permission: PERMISSIONS.VIEW_BRAIN,
  },
  {
    id: "analytics",
    icon: BarChart2,
    label: "Monitor",
    permission: PERMISSIONS.VIEW_DASHBOARD,
  },
  {
    id: "code",
    icon: Play,
    label: "SQL Runner",
    permission: PERMISSIONS.EXECUTE_QUERIES,
  },
];

export default function Workspace({
  connectionId,
  isChatCollapsed,
  onExpandChat,
}) {
  // Zustand store - selective subscriptions for optimal performance
  const activeTab = useActiveTab();
  // Get setActiveTab directly from store to avoid action object recreation
  const setActiveTab = useDashboardStore((state) => state.setActiveTab);
  const { hasPermission } = useAuth();

  // Memoize visible tabs to prevent recreation on every render
  const visibleTabs = useMemo(() => {
    return ALL_TABS.filter((tab) => {
      if (tab.permission) {
        return hasPermission(tab.permission);
      }
      return true;
    });
  }, [hasPermission]);

  // Memoize valid tab IDs
  const validTabIds = useMemo(
    () => visibleTabs.map((t) => t.id),
    [visibleTabs],
  );

  // Ensure active tab is valid (only when it's actually invalid)
  useEffect(() => {
    if (activeTab && !validTabIds.includes(activeTab)) {
      setActiveTab(validTabIds[0] || "rag-training");
    }
  }, [validTabIds, activeTab, setActiveTab]);

  return (
    <div className={styles.workspace}>
      {/* Tabs Header */}
      <div className={styles.tabsHeader}>
        {isChatCollapsed && onExpandChat && (
          <button
            onClick={onExpandChat}
            className="panelToggleButton"
            title="Expand chat panel"
            style={{ marginLeft: "12px" }}
          >
            <PanelLeftOpen size={16} />
          </button>
        )}
        <div className={`${styles.tabs} scrollbar-hide`}>
          {visibleTabs.map((tab) => (
            <button
              key={tab.id}
              data-testid={`tab-${tab.id}`}
              className={`${styles.tabButton} ${activeTab === tab.id ? styles.tabButtonActive : ""}`}
              onClick={() => setActiveTab(tab.id)}
              title={tab.label}
            >
              <tab.icon size={16} />
              <span className={styles.tabLabel}>{tab.label}</span>
            </button>
          ))}
        </div>
        <div style={{ marginLeft: "auto", marginRight: "12px" }}>
          <InitProgressIndicator connectionId={connectionId} />
        </div>
      </div>

      {/* Content Area - Render all tabs but hide inactive ones to preserve state */}
      <div className={styles.contentArea}>
        <div
          style={{
            display: activeTab === "code" ? "block" : "none",
            height: "100%",
          }}
        >
          <PermissionGuard permission={PERMISSIONS.EXECUTE_QUERIES}>
            <SqlRunnerTab connectionId={connectionId} />
          </PermissionGuard>
        </div>
        <div
          style={{
            display: activeTab === "rag-training" ? "block" : "none",
            height: "100%",
          }}
        >
          <RagTrainingTab connectionId={connectionId} />
        </div>
        <div
          style={{
            display: activeTab === "analytics" ? "block" : "none",
            height: "100%",
          }}
        >
          <AnalyticsTab connectionId={connectionId} />
        </div>
      </div>
    </div>
  );
}
