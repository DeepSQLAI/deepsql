"use client";

import { useMemo, useState, useEffect } from "react";
import {
  X,
  AlertTriangle,
  TrendingUp,
  BarChart3,
  Activity,
  Clock,
  Database,
} from "lucide-react";
import { resourceLimitsAPI } from "@/lib/api/client";
import styles from "./AnomalyListModal.module.css";

const SEVERITY_ORDER = { CRITICAL: 0, WARNING: 1, INFO: 2 };

const SEVERITY_LABELS = {
  CRITICAL: "Critical",
  WARNING: "Warning",
  INFO: "Info",
};

const TYPE_ICONS = {
  PERCENTAGE_GROWTH: TrendingUp,
  ABSOLUTE_GROWTH: BarChart3,
  STATISTICAL_ANOMALY: Activity,
  ROW_SPIKE: AlertTriangle,
  NEW_TABLE: Database,
};

const TYPE_LABELS = {
  PERCENTAGE_GROWTH: "Growth %",
  ABSOLUTE_GROWTH: "Size Growth",
  STATISTICAL_ANOMALY: "Statistical",
  ROW_SPIKE: "Row Spike",
  NEW_TABLE: "New Table",
};

function formatBytes(bytes) {
  if (bytes == null) return "—";
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 ** 2) return `${(bytes / 1024).toFixed(1)} KB`;
  if (bytes < 1024 ** 3) return `${(bytes / 1024 ** 2).toFixed(1)} MB`;
  return `${(bytes / 1024 ** 3).toFixed(1)} GB`;
}

function formatRows(count) {
  if (count == null) return "—";
  if (count >= 1e9) return `${(count / 1e9).toFixed(1)}B`;
  if (count >= 1e6) return `${(count / 1e6).toFixed(1)}M`;
  if (count >= 1e3) return `${(count / 1e3).toFixed(1)}K`;
  return count.toLocaleString();
}

function formatDate(ts) {
  if (!ts) return "—";
  const d = new Date(ts);
  return d.toLocaleDateString(undefined, { month: "short", day: "numeric" });
}

function formatGrowthPct(pct) {
  if (pct == null) return "—";
  const sign = pct > 0 ? "+" : "";
  return `${sign}${Number(pct).toFixed(1)}%`;
}

const CADENCE_LABELS = {
  MINUTE: "Every minute",
  HOUR: "Hourly",
  DAY: "Daily",
};

export default function AnomalyListModal({
  open,
  onClose,
  anomalies,
  statistics,
  connectionId,
  growthHistoryData,
}) {
  const [cadence, setCadence] = useState(null);

  useEffect(() => {
    if (!connectionId || !open) return;
    let cancelled = false;
    resourceLimitsAPI
      .getResourceLimits(connectionId)
      .then((data) => {
        if (!cancelled) setCadence(data?.collectionCadence ?? "HOUR");
      })
      .catch(() => {
        if (!cancelled) setCadence("HOUR");
      });
    return () => {
      cancelled = true;
    };
  }, [connectionId, open]);

  const snapshotInfo = useMemo(() => {
    const history = growthHistoryData?.history ?? [];
    if (!history.length) return null;
    let newest = history[0].snapshotTimestamp;
    const tableNames = new Set();
    for (const h of history) {
      if (h.snapshotTimestamp > newest) newest = h.snapshotTimestamp;
      tableNames.add(h.tableName);
    }
    return {
      totalSnapshots: history.length,
      tablesMonitored: tableNames.size,
      lastSnapshot: newest,
    };
  }, [growthHistoryData]);

  const sorted = useMemo(() => {
    if (!anomalies?.length) return [];
    return [...anomalies].sort((a, b) => {
      const sevDiff =
        (SEVERITY_ORDER[a.severity] ?? 9) - (SEVERITY_ORDER[b.severity] ?? 9);
      if (sevDiff !== 0) return sevDiff;
      return (
        new Date(b.detectionTimestamp || 0) -
        new Date(a.detectionTimestamp || 0)
      );
    });
  }, [anomalies]);

  const summary = useMemo(() => {
    const counts = { CRITICAL: 0, WARNING: 0, INFO: 0 };
    for (const a of sorted) {
      counts[a.severity] = (counts[a.severity] || 0) + 1;
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
              <AlertTriangle size={18} />
              Growth Anomalies (30d)
            </h2>
            <p className={styles.subtitle}>
              {sorted.length} anomal{sorted.length === 1 ? "y" : "ies"} detected
              across tables
            </p>
          </div>
          <button type="button" className={styles.closeBtn} onClick={onClose}>
            <X size={18} />
          </button>
        </div>

        {/* Summary bar */}
        <div className={styles.summaryBar}>
          <div className={styles.summaryItem}>
            <span className={styles.summaryLabel}>Critical</span>
            <span
              className={
                summary.CRITICAL > 0
                  ? styles.summaryValueCritical
                  : styles.summaryValue
              }
            >
              {summary.CRITICAL}
            </span>
          </div>
          <div className={styles.summaryItem}>
            <span className={styles.summaryLabel}>Warning</span>
            <span
              className={
                summary.WARNING > 0
                  ? styles.summaryValueWarn
                  : styles.summaryValue
              }
            >
              {summary.WARNING}
            </span>
          </div>
          <div className={styles.summaryItem}>
            <span className={styles.summaryLabel}>Info</span>
            <span className={styles.summaryValue}>{summary.INFO}</span>
          </div>
          {statistics?.totalGrowthBytes != null && (
            <div className={styles.summaryItem}>
              <span className={styles.summaryLabel}>Total Growth</span>
              <span className={styles.summaryValue}>
                {formatBytes(statistics.totalGrowthBytes)}
              </span>
            </div>
          )}
        </div>

        {/* Snapshot info */}
        {snapshotInfo && (
          <div className={styles.snapshotInfo}>
            <Clock size={12} className={styles.snapshotInfoIcon} />
            <span>{CADENCE_LABELS[cadence] || cadence || "—"} collection</span>
            <span className={styles.snapshotInfoDot} />
            <span>Last: {formatDate(snapshotInfo.lastSnapshot)}</span>
            <span className={styles.snapshotInfoDot} />
            <span>{snapshotInfo.totalSnapshots} snapshots</span>
            <span className={styles.snapshotInfoDot} />
            <span>{snapshotInfo.tablesMonitored} tables</span>
          </div>
        )}

        {/* Anomaly list */}
        <div className={styles.list}>
          {sorted.length === 0 ? (
            <div className={styles.noResults}>
              No growth anomalies detected in the last 30 days.
            </div>
          ) : (
            sorted.map((a) => {
              const Icon = TYPE_ICONS[a.anomalyType] || AlertTriangle;
              const sevClass =
                a.severity === "CRITICAL"
                  ? styles.severityCritical
                  : a.severity === "WARNING"
                    ? styles.severityWarning
                    : styles.severityInfo;
              return (
                <div key={a.id} className={styles.row}>
                  <div className={styles.rowLeft}>
                    <div className={styles.rowHeader}>
                      <span className={styles.tableName}>
                        {a.tableName || "—"}
                      </span>
                      <span className={`${styles.severityBadge} ${sevClass}`}>
                        {SEVERITY_LABELS[a.severity] || a.severity}
                      </span>
                      <span className={styles.typeBadge}>
                        <Icon size={11} />
                        {TYPE_LABELS[a.anomalyType] || a.anomalyType}
                      </span>
                    </div>
                    <p className={styles.description}>
                      {a.description || a.reason || "Growth anomaly detected"}
                    </p>
                  </div>
                  <div className={styles.rowRight}>
                    <div className={styles.metric}>
                      <span className={styles.metricLabel}>Growth</span>
                      <span className={styles.metricValue}>
                        {a.sizeGrowthPercent != null
                          ? formatGrowthPct(a.sizeGrowthPercent)
                          : a.sizeGrowthBytes != null
                            ? formatBytes(a.sizeGrowthBytes)
                            : "—"}
                      </span>
                    </div>
                    <div className={styles.metric}>
                      <span className={styles.metricLabel}>Current</span>
                      <span className={styles.metricValue}>
                        {a.currentSizeBytes != null
                          ? formatBytes(a.currentSizeBytes)
                          : a.currentRowCount != null
                            ? formatRows(a.currentRowCount)
                            : "—"}
                      </span>
                    </div>
                    <div className={styles.metric}>
                      <span className={styles.metricLabel}>Detected</span>
                      <span className={styles.metricValue}>
                        {formatDate(a.detectionTimestamp)}
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
