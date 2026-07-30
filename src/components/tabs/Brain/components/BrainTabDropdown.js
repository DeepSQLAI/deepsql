"use client";

import { useState, useRef, useEffect } from "react";
import {
  BookOpen,
  Columns,
  BarChart3,
  FileText,
  ChevronDown,
} from "lucide-react";
import styles from "./BrainTabDropdown.module.css";

/**
 * Dropdown navigation for Brain tabs (Overview, Analytics, Knowledge)
 */
export function BrainTabDropdown({ activeTab, onTabChange }) {
  const [dropdownOpen, setDropdownOpen] = useState(false);
  const dropdownRef = useRef(null);

  const sections = [
    { id: "overview", label: "Overview", icon: BookOpen },
    { id: "analytics", label: "Analytics", icon: BarChart3 },
    { id: "knowledge", label: "Schema Docs", icon: FileText },
    { id: "key-columns", label: "Key Column Analysis", icon: Columns },
  ];

  const currentSection =
    sections.find((s) => s.id === activeTab) || sections[0];

  // Close dropdown when clicking outside
  useEffect(() => {
    const handleClickOutside = (event) => {
      if (dropdownRef.current && !dropdownRef.current.contains(event.target)) {
        setDropdownOpen(false);
      }
    };

    if (dropdownOpen) {
      document.addEventListener("mousedown", handleClickOutside);
      return () =>
        document.removeEventListener("mousedown", handleClickOutside);
    }
  }, [dropdownOpen]);

  return (
    <div
      className={`${styles.dropdownContainer} ${dropdownOpen ? styles.open : ""}`}
      ref={dropdownRef}
    >
      <button
        className={styles.dropdownButton}
        onClick={() => setDropdownOpen(!dropdownOpen)}
        aria-label="Select section"
        title="Select section (Overview, Analytics, Knowledge)"
      >
        {currentSection.icon && <currentSection.icon size={14} />}
        <span className={styles.dropdownLabel}>{currentSection.label}</span>
        <ChevronDown size={12} className={styles.dropdownChevron} />
      </button>
      {dropdownOpen && (
        <div className={styles.dropdownMenu}>
          {sections.map((section) => {
            const IconComponent = section.icon;
            return (
              <button
                key={section.id}
                className={`${styles.dropdownItem} ${activeTab === section.id ? styles.dropdownItemActive : ""}`}
                onClick={() => {
                  onTabChange(section.id);
                  setDropdownOpen(false);
                }}
                title={section.label}
              >
                {IconComponent && <IconComponent size={14} />}
                <span>{section.label}</span>
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
}
