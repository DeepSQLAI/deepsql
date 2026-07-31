/**
 * Slow-query analytics settings — deliberately minimal.
 *
 * DeepSQL auto-detects the tenant column and the customer name-lookup from the
 * schema (see SlowQueryAnalyticsService.effectiveConfig), so this panel opens
 * with a sensible default already selected. Changing it is a single dropdown
 * pick that saves immediately. Manual lookup fields are tucked behind an
 * "Advanced" disclosure for the rare case the auto-detection is wrong.
 */

import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Sparkles, ChevronDown, ChevronRight, Check, RotateCcw } from "lucide-react";
import { slowQueryAnalyticsAPI } from "@/lib/api/client";
import styles from "./SlowQuerySettingsPanel.module.css";

export default function SlowQuerySettingsPanel({ connectionId }) {
  const queryClient = useQueryClient();
  const [advancedOpen, setAdvancedOpen] = useState(false);
  const [savedFlash, setSavedFlash] = useState(false);
  const [advForm, setAdvForm] = useState(null);
  const [resetState, setResetState] = useState("idle"); // idle | confirm | resetting | done

  const configQ = useQuery({
    queryKey: ["slowQueryAnalytics", "config", connectionId],
    queryFn: () => slowQueryAnalyticsAPI.getConfig(connectionId),
    enabled: Boolean(connectionId),
  });

  const suggestionsQ = useQuery({
    queryKey: ["slowQueryAnalytics", "tenantSuggestions", connectionId],
    queryFn: () => slowQueryAnalyticsAPI.getTenantColumnSuggestions(connectionId),
    enabled: Boolean(connectionId),
  });

  const config = configQ.data;
  const suggestions = suggestionsQ.data ?? [];

  /** Wipe all analytics data and reset the ingestion cursor. */
  async function handleReset() {
    if (resetState === "idle") {
      setResetState("confirm");
      return;
    }
    if (resetState === "confirm") {
      setResetState("resetting");
      try {
        await slowQueryAnalyticsAPI.resetAnalytics(connectionId);
        // Invalidate every slow-query query so all tabs reload fresh
        await queryClient.invalidateQueries({ queryKey: ["slowQueryAnalytics"] });
        setResetState("done");
        setTimeout(() => setResetState("idle"), 2500);
      } catch (err) {
        console.error("Reset failed", err);
        setResetState("idle");
      }
    }
  }

  /** Persist a patch on top of the current config. */
  async function save(patch) {
    const next = { ...(config ?? {}), connectionId, ...patch };
    await slowQueryAnalyticsAPI.putConfig(connectionId, next);
    await queryClient.invalidateQueries({
      queryKey: ["slowQueryAnalytics", "config", connectionId],
    });
    setSavedFlash(true);
    setTimeout(() => setSavedFlash(false), 1800);
  }

  if (!connectionId) {
    return <div className={styles.empty}>Select a connection to configure analytics.</div>;
  }
  if (configQ.isLoading) {
    return <div className={styles.empty}>Detecting customer attribution…</div>;
  }

  const tenantColumn = config?.tenantColumn ?? "";
  const autoDetected = Boolean(config?.autoDetected);

  // dropdown options: the ranked recommendations, plus the current value if
  // it isn't one of them (e.g. a hand-typed column).
  const optionNames = suggestions.map((s) => s.columnName);
  if (tenantColumn && !optionNames.includes(tenantColumn)) {
    optionNames.unshift(tenantColumn);
  }

  const lookupSummary =
    config?.customerLookupTable && config?.customerLookupNameCol
      ? `Names resolved from ${config.customerLookupTable}.${config.customerLookupNameCol}`
      : "Names not resolved — showing raw tenant ids";

  const adv = advForm ?? {
    customerLookupTable: config?.customerLookupTable ?? "",
    customerLookupIdCol: config?.customerLookupIdCol ?? "",
    customerLookupNameCol: config?.customerLookupNameCol ?? "",
    dailyAnalysisEnabled: config?.dailyAnalysisEnabled ?? true,
  };
  const setAdv = (patch) => setAdvForm({ ...adv, ...patch });

  return (
    <div className={styles.container}>
      <div className={styles.card}>
        <div className={styles.cardHead}>
          <div>
            <h3 className={styles.h3}>Customer attribution</h3>
            <p className={styles.lead}>
              The column that identifies a tenant. DeepSQL picks the best
              candidate automatically — change it only if it guessed wrong.
            </p>
          </div>
          {autoDetected && (
            <span className={styles.autoBadge}>
              <Sparkles size={12} /> Auto-detected
            </span>
          )}
          {savedFlash && (
            <span className={styles.savedBadge}>
              <Check size={12} /> Saved
            </span>
          )}
        </div>

        <div className={styles.tenantRow}>
          <label className={styles.inlineLabel} htmlFor="sq-tenant-select">
            Tenant column
          </label>
          {optionNames.length > 0 ? (
            <select
              id="sq-tenant-select"
              className={styles.select}
              value={tenantColumn}
              onChange={(e) => save({ tenantColumn: e.target.value })}
            >
              {!tenantColumn && <option value="">— select —</option>}
              {optionNames.map((name) => {
                const s = suggestions.find((x) => x.columnName === name);
                return (
                  <option key={name} value={name}>
                    {name}
                    {s ? ` · ${s.tableCount} tables` : ""}
                  </option>
                );
              })}
            </select>
          ) : (
            <input
              id="sq-tenant-select"
              className={styles.select}
              value={tenantColumn}
              placeholder="e.g. customer_id"
              onChange={(e) => setAdvForm({ ...adv })}
              onBlur={(e) => e.target.value && save({ tenantColumn: e.target.value })}
            />
          )}
        </div>

        <div className={styles.lookupLine}>{lookupSummary}</div>
      </div>

      {/* ── advanced ─────────────────────────────────────────────── */}
      <button
        type="button"
        className={styles.advancedToggle}
        onClick={() => setAdvancedOpen((v) => !v)}
      >
        {advancedOpen ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
        Advanced — customer name lookup &amp; scheduling
      </button>

      {advancedOpen && (
        <div className={styles.card}>
          <p className={styles.lead}>
            To show readable names instead of ids, DeepSQL runs a single
            <code> SELECT name FROM table WHERE id = ?</code> against this
            database. These are auto-filled when detection succeeds.
          </p>
          <div className={styles.row}>
            <div className={styles.field}>
              <label className={styles.inlineLabel}>Customer table</label>
              <input
                className={styles.select}
                value={adv.customerLookupTable}
                placeholder="e.g. customers"
                onChange={(e) => setAdv({ customerLookupTable: e.target.value })}
              />
            </div>
            <div className={styles.field}>
              <label className={styles.inlineLabel}>Id column</label>
              <input
                className={styles.select}
                value={adv.customerLookupIdCol}
                placeholder="e.g. id"
                onChange={(e) => setAdv({ customerLookupIdCol: e.target.value })}
              />
            </div>
            <div className={styles.field}>
              <label className={styles.inlineLabel}>Name column</label>
              <input
                className={styles.select}
                value={adv.customerLookupNameCol}
                placeholder="e.g. name"
                onChange={(e) => setAdv({ customerLookupNameCol: e.target.value })}
              />
            </div>
          </div>
          <label className={styles.toggleRow}>
            <input
              type="checkbox"
              checked={adv.dailyAnalysisEnabled}
              onChange={(e) => setAdv({ dailyAnalysisEnabled: e.target.checked })}
            />
            <span>Include this connection in the daily 01:30 slow-query analysis</span>
          </label>
          <button
            type="button"
            className={styles.saveButton}
            onClick={() => save(adv)}
          >
            Save advanced settings
          </button>
        </div>
      )}

      {/* ── danger zone: reset ──────────────────────────────────── */}
      <div className={styles.card}>
        <div className={styles.cardHead}>
          <div>
            <h3 className={`${styles.h3} ${styles.dangerH3}`}>Reset analysis</h3>
            <p className={styles.lead}>
              Deletes all tracked queries, samples, customer data, and history
              for this connection. The log source and tenant-column config are
              kept. Use this to start fresh after changing the ingestion window
              or tenant column.
            </p>
          </div>
        </div>

        {resetState === "confirm" && (
          <div className={styles.resetWarning}>
            This will permanently delete all slow-query analytics for this
            connection. This cannot be undone.
          </div>
        )}

        <div className={styles.resetRow}>
          <button
            type="button"
            className={
              resetState === "confirm"
                ? styles.resetButtonDanger
                : styles.resetButton
            }
            disabled={resetState === "resetting" || resetState === "done"}
            onClick={handleReset}
          >
            <RotateCcw size={13} />
            {resetState === "idle"     && "Reset analysis data"}
            {resetState === "confirm"  && "Confirm — delete everything"}
            {resetState === "resetting"&& "Resetting…"}
            {resetState === "done"     && "Reset complete"}
          </button>

          {resetState === "confirm" && (
            <button
              type="button"
              className={styles.cancelButton}
              onClick={() => setResetState("idle")}
            >
              Cancel
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
