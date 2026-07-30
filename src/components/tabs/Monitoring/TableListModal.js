"use client";

import { useState, useMemo, useRef, useEffect } from "react";
import {
  X,
  Search,
  Database,
  ChevronDown,
  SlidersHorizontal,
} from "lucide-react";
import styles from "./TableListModal.module.css";

const COLUMNS = [
  { key: "size", label: "Size", defaultVisible: true },
  { key: "rows", label: "Rows", defaultVisible: true },
  { key: "growth", label: "Size Δ%", defaultVisible: true },
  { key: "rowGrowthPct", label: "Row Δ%", defaultVisible: true },
  { key: "rowGrowth", label: "Row Δ", defaultVisible: false },
  { key: "bloatPct", label: "Bloat %", defaultVisible: false },
  { key: "bloatGb", label: "Bloat", defaultVisible: false },
];

const DEFAULT_VISIBLE = new Set(
  COLUMNS.filter((c) => c.defaultVisible).map((c) => c.key),
);

function formatBytes(bytes) {
  if (bytes == null) return "—";
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 ** 2) return `${(bytes / 1024).toFixed(1)} KB`;
  if (bytes < 1024 ** 3) return `${(bytes / 1024 ** 2).toFixed(1)} MB`;
  return `${(bytes / 1024 ** 3).toFixed(1)} GB`;
}

function formatPct(value) {
  if (value == null || Number.isNaN(value)) return "—";
  return `${Number(value).toFixed(1)}%`;
}

function formatRows(count) {
  if (count == null) return "—";
  if (count >= 1e9) return `${(count / 1e9).toFixed(1)}B`;
  if (count >= 1e6) return `${(count / 1e6).toFixed(1)}M`;
  if (count >= 1e3) return `${(count / 1e3).toFixed(1)}K`;
  return count.toLocaleString();
}

function bloatSeverity(pct) {
  if (pct == null) return "none";
  if (pct >= 40) return "critical";
  if (pct >= 20) return "warning";
  return "ok";
}

function growthSeverity(pct) {
  if (pct == null || pct <= 0) return "none";
  if (pct >= 100) return "critical";
  if (pct >= 50) return "warning";
  return "ok";
}

export default function TableListModal({ open, onClose, growthHistoryData }) {
  const [sortBy, setSortBy] = useState("size");
  const [searchTerm, setSearchTerm] = useState("");
  const [visibleCols, setVisibleCols] = useState(DEFAULT_VISIBLE);
  const [showColPicker, setShowColPicker] = useState(false);
  const colPickerRef = useRef(null);

  // Close column picker on outside click
  useEffect(() => {
    if (!showColPicker) return;
    const handler = (e) => {
      if (colPickerRef.current && !colPickerRef.current.contains(e.target)) {
        setShowColPicker(false);
      }
    };
    document.addEventListener("mousedown", handler);
    return () => document.removeEventListener("mousedown", handler);
  }, [showColPicker]);

  const toggleCol = (key) => {
    setVisibleCols((prev) => {
      const next = new Set(prev);
      if (next.has(key)) {
        next.delete(key);
        if (sortBy === key) setSortBy("size");
      } else {
        next.add(key);
      }
      return next;
    });
  };

  // Compute growth from oldest→newest snapshots per table
  const tables = useMemo(() => {
    const history = growthHistoryData?.history ?? [];
    if (!history.length) return [];
    // Group by tableName, track oldest + newest
    const grouped = new Map();
    for (const h of history) {
      const entry = grouped.get(h.tableName);
      if (!entry) {
        grouped.set(h.tableName, { oldest: h, newest: h, count: 1 });
      } else {
        entry.count++;
        if (h.snapshotTimestamp < entry.oldest.snapshotTimestamp)
          entry.oldest = h;
        if (h.snapshotTimestamp > entry.newest.snapshotTimestamp)
          entry.newest = h;
      }
    }
    return [...grouped.entries()].map(
      ([tableName, { oldest, newest, count }]) => {
        const hasGrowthData = count >= 2;
        let sizeGrowthPct = null;
        let rowGrowthPct = null;
        let rowGrowthAbs = null;
        if (hasGrowthData) {
          const oldSize = oldest.sizeBytes ?? 0;
          const newSize = newest.sizeBytes ?? 0;
          sizeGrowthPct =
            oldSize > 0 ? ((newSize - oldSize) / oldSize) * 100 : 0;
          const oldRows = oldest.rowCount ?? 0;
          const newRows = newest.rowCount ?? 0;
          rowGrowthPct =
            oldRows > 0 ? ((newRows - oldRows) / oldRows) * 100 : 0;
          rowGrowthAbs = newRows - oldRows;
        }
        return {
          ...newest,
          tableName,
          hasGrowthData,
          sizeGrowthPct,
          rowGrowthPct,
          rowGrowthAbs,
          snapshotCount: count,
        };
      },
    );
  }, [growthHistoryData]);

  // Summary stats
  const summary = useMemo(() => {
    const totalSize = tables.reduce((sum, t) => sum + (t.sizeBytes ?? 0), 0);
    const totalRows = tables.reduce((sum, t) => sum + (t.rowCount ?? 0), 0);
    const totalBloat = tables.reduce((sum, t) => sum + (t.bloatBytes ?? 0), 0);
    const bloatedCount = tables.filter(
      (t) => (t.bloatPercent ?? 0) >= 20,
    ).length;
    return {
      totalSize,
      totalRows,
      totalBloat,
      bloatedCount,
      tableCount: tables.length,
    };
  }, [tables]);

  // Filter + sort
  const filteredTables = useMemo(() => {
    let result = tables;

    if (searchTerm.trim()) {
      const term = searchTerm.toLowerCase();
      result = result.filter((t) =>
        (t.tableName || "").toLowerCase().includes(term),
      );
    }

    const sortFns = {
      size: (a, b) => (b.sizeBytes ?? 0) - (a.sizeBytes ?? 0),
      rows: (a, b) => (b.rowCount ?? 0) - (a.rowCount ?? 0),
      growth: (a, b) =>
        Math.abs(b.sizeGrowthPct ?? 0) - Math.abs(a.sizeGrowthPct ?? 0),
      rowGrowthPct: (a, b) =>
        Math.abs(b.rowGrowthPct ?? 0) - Math.abs(a.rowGrowthPct ?? 0),
      rowGrowth: (a, b) =>
        Math.abs(b.rowGrowthAbs ?? 0) - Math.abs(a.rowGrowthAbs ?? 0),
      bloatPct: (a, b) => (b.bloatPercent ?? 0) - (a.bloatPercent ?? 0),
      bloatGb: (a, b) => (b.bloatBytes ?? 0) - (a.bloatBytes ?? 0),
    };

    return [...result].sort(sortFns[sortBy] || sortFns.size);
  }, [tables, searchTerm, sortBy]);

  if (!open) return null;

  return (
    <div className={styles.overlay} onClick={onClose}>
      <div className={styles.modal} onClick={(e) => e.stopPropagation()}>
        {/* Header */}
        <div className={styles.header}>
          <div>
            <h2 className={styles.title}>
              <Database size={18} />
              Database Tables
            </h2>
            <p className={styles.subtitle}>
              {summary.tableCount} tables · {formatBytes(summary.totalSize)}{" "}
              total
              {summary.bloatedCount > 0 && ` · ${summary.bloatedCount} bloated`}
            </p>
          </div>
          <button type="button" className={styles.closeBtn} onClick={onClose}>
            <X size={18} />
          </button>
        </div>

        {/* Summary bar */}
        <div className={styles.summaryBar}>
          <div className={styles.summaryItem}>
            <span className={styles.summaryLabel}>Total Size</span>
            <span className={styles.summaryValue}>
              {formatBytes(summary.totalSize)}
            </span>
          </div>
          <div className={styles.summaryItem}>
            <span className={styles.summaryLabel}>Total Rows</span>
            <span className={styles.summaryValue}>
              {formatRows(summary.totalRows)}
            </span>
          </div>
          <div className={styles.summaryItem}>
            <span className={styles.summaryLabel}>Bloat Waste</span>
            <span
              className={
                summary.totalBloat > 0
                  ? styles.summaryValueWarn
                  : styles.summaryValue
              }
            >
              {formatBytes(summary.totalBloat)}
            </span>
          </div>
          <div className={styles.summaryItem}>
            <span className={styles.summaryLabel}>Bloated Tables</span>
            <span
              className={
                summary.bloatedCount > 0
                  ? styles.summaryValueWarn
                  : styles.summaryValue
              }
            >
              {summary.bloatedCount}
            </span>
          </div>
        </div>

        {/* Search + column picker */}
        <div className={styles.searchRow}>
          <Search size={14} className={styles.searchIcon} />
          <input
            type="text"
            placeholder="Search tables..."
            value={searchTerm}
            onChange={(e) => setSearchTerm(e.target.value)}
            className={styles.searchInput}
          />
          <div className={styles.colPickerWrap} ref={colPickerRef}>
            <button
              type="button"
              className={styles.colPickerBtn}
              onClick={() => setShowColPicker((v) => !v)}
              title="Toggle columns"
            >
              <SlidersHorizontal size={14} />
            </button>
            {showColPicker && (
              <div className={styles.colPickerDropdown}>
                {COLUMNS.map((col) => (
                  <label key={col.key} className={styles.colPickerItem}>
                    <input
                      type="checkbox"
                      checked={visibleCols.has(col.key)}
                      onChange={() => toggleCol(col.key)}
                    />
                    {col.label}
                  </label>
                ))}
              </div>
            )}
          </div>
        </div>

        {/* Table list */}
        <div className={styles.tableList}>
          {/* Column header */}
          <div className={styles.columnHeader}>
            <span className={styles.colName}>Table</span>
            {COLUMNS.filter((c) => visibleCols.has(c.key)).map(
              ({ key, label }) => (
                <button
                  key={key}
                  type="button"
                  className={`${styles.colMetricBtn} ${sortBy === key ? styles.colMetricBtnActive : ""}`}
                  onClick={() => setSortBy(key)}
                >
                  {label}
                  <ChevronDown
                    size={10}
                    className={
                      sortBy === key ? styles.sortArrowActive : styles.sortArrow
                    }
                  />
                </button>
              ),
            )}
          </div>

          {filteredTables.length === 0 ? (
            <div className={styles.noResults}>
              {tables.length === 0
                ? "No table data available. Snapshots may still be collecting."
                : "No tables match the search."}
            </div>
          ) : (
            filteredTables.map((table) => {
              const bloatLevel = bloatSeverity(table.bloatPercent);
              const sizeGrowthLevel = growthSeverity(table.sizeGrowthPct);
              const rowGrowthLevel = growthSeverity(table.rowGrowthPct);
              return (
                <div key={table.tableName} className={styles.tableRow}>
                  <div className={styles.tableNameCol}>
                    <span className={styles.tableName}>{table.tableName}</span>
                    <span className={styles.tableHint}>
                      {table.dataSizeBytes != null &&
                      table.indexSizeBytes != null
                        ? `Data: ${formatBytes(table.dataSizeBytes)} · Index: ${formatBytes(table.indexSizeBytes)}`
                        : table.snapshotTimestamp
                          ? `Snapshot: ${new Date(table.snapshotTimestamp).toLocaleDateString()}`
                          : ""}
                    </span>
                  </div>
                  {visibleCols.has("size") && (
                    <span className={styles.tableMetric}>
                      {formatBytes(table.sizeBytes)}
                    </span>
                  )}
                  {visibleCols.has("rows") && (
                    <span className={styles.tableMetric}>
                      {formatRows(table.rowCount)}
                    </span>
                  )}
                  {visibleCols.has("growth") && (
                    <span
                      className={`${styles.tableMetric} ${sizeGrowthLevel === "critical" ? styles.metricCritical : sizeGrowthLevel === "warning" ? styles.metricWarn : ""}`}
                    >
                      {table.hasGrowthData
                        ? `${table.sizeGrowthPct > 0 ? "+" : ""}${formatPct(table.sizeGrowthPct)}`
                        : "—"}
                    </span>
                  )}
                  {visibleCols.has("rowGrowthPct") && (
                    <span
                      className={`${styles.tableMetric} ${rowGrowthLevel === "critical" ? styles.metricCritical : rowGrowthLevel === "warning" ? styles.metricWarn : ""}`}
                    >
                      {table.hasGrowthData
                        ? `${table.rowGrowthPct > 0 ? "+" : ""}${formatPct(table.rowGrowthPct)}`
                        : "—"}
                    </span>
                  )}
                  {visibleCols.has("rowGrowth") && (
                    <span
                      className={`${styles.tableMetric} ${rowGrowthLevel === "critical" ? styles.metricCritical : rowGrowthLevel === "warning" ? styles.metricWarn : ""}`}
                    >
                      {table.hasGrowthData
                        ? `${table.rowGrowthAbs > 0 ? "+" : ""}${formatRows(table.rowGrowthAbs)}`
                        : "—"}
                    </span>
                  )}
                  {visibleCols.has("bloatPct") && (
                    <span
                      className={`${styles.tableMetric} ${bloatLevel === "critical" ? styles.metricCritical : bloatLevel === "warning" ? styles.metricWarn : ""}`}
                    >
                      {table.bloatPercent != null
                        ? formatPct(table.bloatPercent)
                        : "—"}
                    </span>
                  )}
                  {visibleCols.has("bloatGb") && (
                    <span
                      className={`${styles.tableMetric} ${bloatLevel === "critical" ? styles.metricCritical : bloatLevel === "warning" ? styles.metricWarn : ""}`}
                    >
                      {formatBytes(table.bloatBytes)}
                    </span>
                  )}
                </div>
              );
            })
          )}
        </div>
      </div>
    </div>
  );
}
