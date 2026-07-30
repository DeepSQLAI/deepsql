"use client";

import { useState, useEffect } from "react";
import { X, Users, Settings, Shield, Activity, KeyRound } from "lucide-react";
import { useAuth } from "@/hooks/useAuth";
import AdminWorkspaceSettings from "@/components/settings/AdminWorkspaceSettings";
import SlackAccessCodePanel from "@/components/settings/SlackAccessCodePanel";
import McpTokensPanel from "@/components/settings/McpTokensPanel";
import UsersTab from "./tabs/admin/UsersTab";
import AuditLogsTab from "./tabs/admin/AuditLogsTab";
import styles from "./SettingsModal.module.css";

export default function SettingsModal({ isOpen, onClose }) {
  const { isAdmin, role } = useAuth();
  const [activeSection, setActiveSection] = useState(() =>
    isAdmin ? "users" : "general",
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
        ]
      : []),
    {
      id: "mcp-tokens",
      icon: KeyRound,
      label: "MCP Tokens",
      description: "Create and revoke personal access tokens",
    },
    {
      id: "general",
      icon: Settings,
      label: "Workspace",
      description: isAdmin ? "SMTP, Slack, and security" : "Application preferences",
    },
  ];

  const renderContent = () => {
    switch (activeSection) {
      case "users":
        return isAdmin ? <UsersTab /> : null;
      case "audit-logs":
        return isAdmin ? <AuditLogsTab /> : null;
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
