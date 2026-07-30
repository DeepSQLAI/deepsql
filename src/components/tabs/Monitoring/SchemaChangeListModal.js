"use client";

import { useMemo } from "react";
import {
  X,
  GitBranch,
  Plus,
  Minus,
  Pencil,
} from "lucide-react";
import styles from "./SchemaChangeListModal.module.css";

const CHANGE_TYPE_META = {
  TABLE_ADDED:       { label: "Table added",       color: "added" },
  TABLE_REMOVED:     { label: "Table removed",     color: "removed" },
  COLUMN_ADDED:      { label: "Column added",      color: "added" },
  COLUMN_REMOVED:    { label: "Column removed",    color: "removed" },
  COLUMN_MODIFIED:   { label: "Column modified",   color: "modified" },
  INDEX_ADDED:       { label: "Index added",        color: "added" },
  INDEX_REMOVED:     { label: "Index removed",      color: "removed" },
  INDEX_MODIFIED:    { label: "Index modified",      color: "modified" },
  CONSTRAINT_ADDED:  { label: "Constraint added",   color: "added" },
  CONSTRAINT_REMOVED:{ label: "Constraint removed",  color: "removed" },
  FOREIGN_KEY_ADDED: { label: "FK added",           color: "added" },
  FOREIGN_KEY_REMOVED:{ label: "FK removed",         color: "removed" },
};

const CHANGE_COLOR_ICON = {
  added: Plus,
  removed: Minus,
  modified: Pencil,
};

const SEVERITY_LABELS = {
  CRITICAL: "Critical",
  WARNING: "Warning",
  INFO: "Info",
};

function formatDate(ts) {
  if (!ts) return "—";
  const d = new Date(ts);
  return d.toLocaleDateString(undefined, { month: "short", day: "numeric" });
}

export default function SchemaChangeListModal({ open, onClose, changes }) {
  const sorted = useMemo(() => {
    if (!changes?.length) return [];
    return [...changes].sort(
      (a, b) => new Date(b.detectedAt || 0) - new Date(a.detectedAt || 0),
    );
  }, [changes]);

  const summary = useMemo(() => {
    const counts = {
      tablesAdded: 0,
      tablesRemoved: 0,
      columnsAdded: 0,
      columnsRemoved: 0,
      columnsModified: 0,
      indexes: 0,
    };
    for (const c of sorted) {
      switch (c.changeType) {
        case "TABLE_ADDED":       counts.tablesAdded++; break;
        case "TABLE_REMOVED":     counts.tablesRemoved++; break;
        case "COLUMN_ADDED":      counts.columnsAdded++; break;
        case "COLUMN_REMOVED":    counts.columnsRemoved++; break;
        case "COLUMN_MODIFIED":   counts.columnsModified++; break;
        case "INDEX_ADDED":
        case "INDEX_REMOVED":
        case "INDEX_MODIFIED":    counts.indexes++; break;
        default: break;
      }
    }
    return counts;
  }, [sorted]);

  if (!open) return null;

  return (
    <div className={styles.overlay} onClick={onClose}>
      <div className={styles.modal} onClick={(e) => e.stopPropagation()}>
        {/* Header */}
        <div className={styles.header}>
          <div>
            <h2 className={styles.title}>
              <GitBranch size={18} />
              Schema Changes
            </h2>
            <p className={styles.subtitle}>
              {sorted.length} change{sorted.length !== 1 ? "s" : ""} detected
            </p>
          </div>
          <button type="button" className={styles.closeBtn} onClick={onClose}>
            <X size={18} />
          </button>
        </div>

        {/* Summary bar */}
        <div className={styles.summaryBar}>
          {summary.tablesAdded > 0 && (
            <div className={styles.summaryItem}>
              <span className={styles.summaryLabel}>Tables +</span>
              <span className={styles.summaryValueAdded}>{summary.tablesAdded}</span>
            </div>
          )}
          {summary.tablesRemoved > 0 && (
            <div className={styles.summaryItem}>
              <span className={styles.summaryLabel}>Tables −</span>
              <span className={styles.summaryValueRemoved}>{summary.tablesRemoved}</span>
            </div>
          )}
          {summary.columnsAdded > 0 && (
            <div className={styles.summaryItem}>
              <span className={styles.summaryLabel}>Columns +</span>
              <span className={styles.summaryValueAdded}>{summary.columnsAdded}</span>
            </div>
          )}
          {summary.columnsRemoved > 0 && (
            <div className={styles.summaryItem}>
              <span className={styles.summaryLabel}>Columns −</span>
              <span className={styles.summaryValueRemoved}>{summary.columnsRemoved}</span>
            </div>
          )}
          {summary.columnsModified > 0 && (
            <div className={styles.summaryItem}>
              <span className={styles.summaryLabel}>Modified</span>
              <span className={styles.summaryValueModified}>{summary.columnsModified}</span>
            </div>
          )}
          {summary.indexes > 0 && (
            <div className={styles.summaryItem}>
              <span className={styles.summaryLabel}>Indexes</span>
              <span className={styles.summaryValue}>{summary.indexes}</span>
            </div>
          )}
          {sorted.length === 0 && (
            <div className={styles.summaryItem}>
              <span className={styles.summaryLabel}>Total</span>
              <span className={styles.summaryValue}>0</span>
            </div>
          )}
        </div>

        {/* Change list */}
        <div className={styles.list}>
          {sorted.length === 0 ? (
            <div className={styles.noResults}>
              No schema changes detected.
            </div>
          ) : (
            sorted.map((c) => {
              const meta = CHANGE_TYPE_META[c.changeType] || {
                label: c.changeType,
                color: "modified",
              };
              const Icon = CHANGE_COLOR_ICON[meta.color] || Pencil;
              return (
                <div key={c.id} className={styles.row}>
                  <div className={styles.rowLeft}>
                    <div className={styles.rowHeader}>
                      <span className={styles.objectName}>
                        {c.objectName || "—"}
                      </span>
                      <span className={`${styles.changeBadge} ${styles[`change${meta.color.charAt(0).toUpperCase() + meta.color.slice(1)}`]}`}>
                        <Icon size={10} />
                        {meta.label}
                      </span>
                      <span className={`${styles.severityBadge} ${styles[`severity${(c.severity || "INFO").charAt(0).toUpperCase() + (c.severity || "INFO").slice(1).toLowerCase()}`]}`}>
                        {SEVERITY_LABELS[c.severity] || c.severity || "Info"}
                      </span>
                    </div>
                    {c.tableName && (
                      <p className={styles.description}>
                        Table: {c.tableName}
                      </p>
                    )}
                    {c.description && (
                      <p className={styles.description}>{c.description}</p>
                    )}
                  </div>
                  <div className={styles.rowRight}>
                    <div className={styles.metric}>
                      <span className={styles.metricLabel}>Type</span>
                      <span className={styles.metricValue}>
                        {(c.objectType || "").replace(/_/g, " ")}
                      </span>
                    </div>
                    <div className={styles.metric}>
                      <span className={styles.metricLabel}>Detected</span>
                      <span className={styles.metricValue}>
                        {formatDate(c.detectedAt)}
                      </span>
                    </div>
                  </div>
                </div>
              );
            })
          )}
        </div>
      </div>
    </div>
  );
}
