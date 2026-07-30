"use client";

import { BookOpen, FileText, BarChart3 } from "lucide-react";
import styles from "./BrainTabNavigation.module.css";

/**
 * Tab navigation for Brain sections with collapsible labels
 * Icons are always visible, labels expand on hover/active
 */
export function BrainTabNavigation({ activeTab, onTabChange }) {
  const sections = [
    { id: "overview", label: "Overview", icon: BookOpen },
    { id: "analytics", label: "Analytics", icon: BarChart3 },
    { id: "knowledge", label: "Schema Docs", icon: FileText },
  ];

  return (
    <div className={styles.sectionTabs}>
      {sections.map((section) => {
        const IconComponent = section.icon;
        return (
          <button
            key={section.id}
            onClick={() => onTabChange(section.id)}
            className={`${styles.sectionTab} ${activeTab === section.id ? styles.sectionTabActive : ""}`}
            title={section.label}
            data-testid={`brain-tab-${section.id}`}
          >
            {IconComponent && <IconComponent size={14} />}
            <span className={styles.sectionTabLabel}>{section.label}</span>
          </button>
        );
      })}
    </div>
  );
}
