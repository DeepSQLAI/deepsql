"use client";

import { useState, useEffect } from "react";
import { X, Users, Settings, Shield, Activity, KeyRound, Coins } from "lucide-react";
import { useAuth } from "@/hooks/useAuth";
import { PERMISSIONS } from "@/lib/permissions";
import AdminWorkspaceSettings from "@/components/settings/AdminWorkspaceSettings";
import SlackAccessCodePanel from "@/components/settings/SlackAccessCodePanel";
import McpTokensPanel from "@/components/settings/McpTokensPanel";
import UsersTab from "./tabs/admin/UsersTab";
import AuditLogsTab from "./tabs/admin/AuditLogsTab";
import LlmUsageTab from "./tabs/admin/LlmUsageTab";
import styles from "./SettingsModal.module.css";

export default function SettingsModal({ isOpen, onClose }) {
  const { isAdmin, role, hasPermission } = useAuth();
  const canManageWorkspaceSettings = hasPermission(PERMISSIONS.MANAGE_SETTINGS);
  const canOpenSettings =
    canManageWorkspaceSettings ||
    hasPermission(PERMISSIONS.MANAGE_USERS) ||
    hasPermission(PERMISSIONS.MANAGE_PERMISSIONS);
  const [activeSection, setActiveSection] = useState(() =>
    isAdmin ? "users" : "mcp-tokens",
  );

  // Handle Escape key to close
  useEffect(() => {
    const handleEscape = (e) => {
      if (e.key === "Escape" && isOpen) {
        onClose();
      }
    };
    document.addEventListener("keydown", handleEscape);
    return () => document.removeEventListener("keydown", handleEscape);
  }, [isOpen, onClose]);

  if (!isOpen) return null;

  // Settings is administrative only. Developer and Data Engineer hold none of these
  // permissions and must not reach this panel at all. Enforced inside the modal, not
  // just at the call site, because any future entry point would otherwise reopen it.
  // Note this also removes MCP tokens from those roles, which is the intended trade.
  if (!canOpenSettings) return null;

  const sections = [
    ...(isAdmin
      ? [
          {
            id: "users",
            icon: Users,
            label: "User Management",
            description: "Manage users and roles",
          },
          {
            id: "audit-logs",
            icon: Activity,
            label: "Audit Logs",
            description: "Review editor and security activity",
          },
          {
            id: "llm-usage",
            icon: Coins,
            label: "AI Usage & Cost",
            description: "Track model spend by feature and user",
          },
        ]
      : []),
    {
      id: "mcp-tokens",
      icon: KeyRound,
      label: "MCP Tokens",
      description: "Create and revoke personal access tokens",
    },
    // The Workspace tab is admin configuration (SMTP, Slack, workspace security).
    // Roles without MANAGE_SETTINGS — Developer and Data Engineer — only see the
    // personal surfaces above (MCP tokens), so Settings stays useful to them without
    // exposing workspace configuration.
    ...(canManageWorkspaceSettings
      ? [
          {
            id: "general",
            icon: Settings,
            label: "Workspace",
            description: "SMTP, Slack, and security",
          },
        ]
      : []),
  ];

  const renderContent = () => {
    switch (activeSection) {
      case "users":
        return isAdmin ? <UsersTab /> : null;
      case "audit-logs":
        return isAdmin ? <AuditLogsTab /> : null;
      case "llm-usage":
        return isAdmin ? <LlmUsageTab /> : null;
      case "mcp-tokens":
        return <McpTokensPanel />;
      case "general":
        return isAdmin ? (
          <AdminWorkspaceSettings />
        ) : (
          <div className={styles.generalSettings}>
            <div className={styles.settingGroup}>
              <h3 className={styles.settingGroupTitle}>Account</h3>
              <div className={styles.settingItem}>
                <div className={styles.settingInfo}>
                  <span className={styles.settingLabel}>Role</span>
                  <span className={styles.settingValue}>
                    <Shield size={14} />
                    {role || "User"}
                  </span>
                </div>
              </div>
            </div>
            <SlackAccessCodePanel compact />
          </div>
        );
      default:
        return null;
    }
  };

  return (
    <div className={styles.overlay}>
      <div className={styles.modal}>
        {/* Header */}
        <div className={styles.header}>
          <h2 className={styles.title}>Settings</h2>
          <button
            className={styles.closeButton}
            onClick={onClose}
            title="Close settings"
          >
            <X size={20} />
          </button>
        </div>

        {/* Body */}
        <div className={styles.body}>
          {/* Sidebar */}
          <div className={styles.sidebar}>
            {sections.map((section) => (
              <button
                key={section.id}
                className={`${styles.sidebarItem} ${activeSection === section.id ? styles.sidebarItemActive : ""}`}
                onClick={() => setActiveSection(section.id)}
              >
                <section.icon size={18} />
                <div className={styles.sidebarItemText}>
                  <span className={styles.sidebarItemLabel}>
                    {section.label}
                  </span>
                  <span className={styles.sidebarItemDesc}>
                    {section.description}
                  </span>
                </div>
              </button>
            ))}
          </div>

          {/* Content */}
          <div className={styles.content}>{renderContent()}</div>
        </div>
      </div>
    </div>
  );
}
