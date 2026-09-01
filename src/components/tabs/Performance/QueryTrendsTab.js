/**
 * Query Trends — the 30-day slow-query analytics surface.
 *
 * Shows every tracked query with its day-by-day timeline, surfaces the ones
 * that regressed, and drills into the per-customer breakdown for a query.
 * Backed by /api/slow-query-analytics/** (slowQueryAnalyticsAPI).
 */

import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  LineChart,
  Line,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
} from "recharts";
import { RefreshCw, TrendingUp, AlertTriangle, Clock, Users, Copy, Check, FileText } from "lucide-react";
import { slowQueryAnalyticsAPI } from "@/lib/api/client";
import { useConnectionManager } from "@/lib/hooks/useConnectionManager";
import SlowQuerySourceModal from "@/components/SlowQuerySourceModal";
import { QueryError } from "./components";
import styles from "./QueryTrendsTab.module.css";

/** Human-readable duration: ms under a second, seconds under a minute, else minutes. */
const fmtDuration = (v) => {
  if (v == null) return "—";
  if (v < 1000) return `${Math.round(v)} ms`;
  if (v < 60000) return `${(v / 1000).toFixed(1)} s`;
  return `${(v / 60000).toFixed(1)} min`;
};
const fmtNum = (v) => (v == null ? "—" : Number(v).toLocaleString());

/** Classify a regression factor into a severity bucket for badge styling. */
function regressionTone(factor) {
  if (factor == null) return null;
  if (factor >= 1.5) return "high";
  if (factor >= 1.2) return "medium";
  return null;
}

function RegressionBadge({ factor }) {
  const tone = regressionTone(factor);
  if (!tone) return null;
  return (
    <span className={`${styles.badge} ${styles[`badge_${tone}`]}`}>
      <TrendingUp size={12} />
      {factor.toFixed(2)}× slower
    </span>
  );
}

function CopyButton({ text }) {
  const [copied, setCopied] = useState(false);
  return (
    <button
      type="button"
      className={styles.copyBtn}
      onClick={async () => {
        try {
          await navigator.clipboard.writeText(text || "");
          setCopied(true);
          setTimeout(() => setCopied(false), 1400);
        } catch {
          /* clipboard unavailable */
        }
      }}
    >
      {copied ? <Check size={12} /> : <Copy size={12} />}
      {copied ? "Copied" : "Copy"}
    </button>
  );
}

export default function QueryTrendsTab({ connectionId }) {
  const queryClient = useQueryClient();
  const { selectedConnection } = useConnectionManager();
  const [selected, setSelected] = useState(null);
  const [analyzing, setAnalyzing] = useState(false);
  const [analyzeStatus, setAnalyzeStatus] = useState(null);
  // Surfaces "Configure log source" CTA when analyze-now reports
  // LOG_INGESTION_NOT_CONFIGURED, and opens the existing SlowQuerySourceModal.
  const [needsLogSource, setNeedsLogSource] = useState(false);
  const [logSourceModalOpen, setLogSourceModalOpen] = useState(false);

  const enabled = Boolean(connectionId);

  const queriesQ = useQuery({
    queryKey: ["slowQueryAnalytics", "queries", connectionId],
    queryFn: () => slowQueryAnalyticsAPI.getQueries(connectionId),
    enabled,
  });

  const regressionsQ = useQuery({
    queryKey: ["slowQueryAnalytics", "regressions", connectionId],
    queryFn: () => slowQueryAnalyticsAPI.getRegressions(connectionId, 1.5),
    enabled,
  });

  const timelineQ = useQuery({
    queryKey: ["slowQueryAnalytics", "timeline", connectionId, selected],
    queryFn: () => slowQueryAnalyticsAPI.getTimeline(connectionId, selected),
    enabled: enabled && Boolean(selected),
  });

  const customersQ = useQuery({
    queryKey: ["slowQueryAnalytics", "customers", connectionId, selected],
    queryFn: () => slowQueryAnalyticsAPI.getCustomers(connectionId, selected),
    enabled: enabled && Boolean(selected),
  });

  const samplesQ = useQuery({
    queryKey: ["slowQueryAnalytics", "samples", connectionId, selected],
    queryFn: () => slowQueryAnalyticsAPI.getSamples(connectionId, selected),
    enabled: enabled && Boolean(selected),
  });

  const queries = queriesQ.data ?? [];
  const regressions = regressionsQ.data ?? [];
  const timeline = timelineQ.data ?? [];
  const customers = customersQ.data ?? [];
  const samples = samplesQ.data ?? [];

  const selectedQuery = queries.find((q) => q.fingerprint === selected);

  async function handleAnalyzeNow() {
    if (!connectionId || analyzing) return;
    setAnalyzing(true);
    setAnalyzeStatus(null);
    try {
      const res = await slowQueryAnalyticsAPI.analyzeNow(connectionId);
      if (res?.success === false && res?.code === "LOG_INGESTION_NOT_CONFIGURED") {
        // Surface the empty-state CTA rather than a red error toast — this is
        // a configuration step, not a failure.
        setNeedsLogSource(true);
        setAnalyzeStatus(null);
        return;
      }
      setNeedsLogSource(false);
      await queryClient.invalidateQueries({ queryKey: ["slowQueryAnalytics"] });
      setAnalyzeStatus({
        tone: "ok",
        text: res?.success === false
          ? res?.message || "No new slow-query events since the last run."
          : `Analysis complete${res?.overallHealth ? ` — health: ${res.overallHealth}` : ""}.`,
      });
    } catch (err) {
      // 412 from backend = no log source configured. Axios surfaces it via
      // response.status; the body carries the structured code.
      if (err?.response?.status === 412
          && err?.response?.data?.code === "LOG_INGESTION_NOT_CONFIGURED") {
        setNeedsLogSource(true);
        setAnalyzeStatus(null);
        return;
      }
      const msg =
        err?.response?.data?.message || err?.message || "the request failed";
      setAnalyzeStatus({ tone: "error", text: `Analysis failed — ${msg}` });
    } finally {
      setAnalyzing(false);
    }
  }

  if (!connectionId) {
    return <div className={styles.empty}>Select a database connection to see query trends.</div>;
  }

  return (
    <div className={styles.container}>
      <div className={styles.header}>
        <div>
          <h2 className={styles.title}>Query Trends</h2>
          <p className={styles.subtitle}>
            Per-query performance over the last 30 days. The daily job runs at 01:30 —
            use “Analyze now” to capture a fresh data point.
          </p>
        </div>
        <button
          className={styles.analyzeButton}
          onClick={handleAnalyzeNow}
          disabled={analyzing}
        >
          <RefreshCw size={14} className={analyzing ? styles.spin : ""} />
          {analyzing ? "Analyzing…" : "Analyze now"}
        </button>
      </div>

      {analyzing && (
        <div className={`${styles.notice} ${styles.notice_busy}`}>
          <RefreshCw size={14} className={styles.spin} />
          Running a fresh analysis against the database — this can take 1–3
          minutes depending on workload size. The view refreshes automatically
          when it’s done.
        </div>
      )}

      {!analyzing && analyzeStatus && (
        <div
          className={`${styles.notice} ${
            analyzeStatus.tone === "error" ? styles.notice_error : styles.notice_ok
          }`}
        >
          {analyzeStatus.tone === "error" ? (
            <AlertTriangle size={14} />
          ) : (
            <RefreshCw size={14} />
          )}
          {analyzeStatus.text}
        </div>
      )}

      {needsLogSource && queries.length === 0 && (
        <div className={styles.emptyState}>
          <FileText size={32} style={{ opacity: 0.4, marginBottom: 8 }} />
          <h3 style={{ margin: "0 0 6px", fontSize: 15, fontWeight: 600 }}>
            Slow queries come from your database's slow-query log
          </h3>
          <p style={{ margin: "0 0 14px", fontSize: 13, color: "#6b7280", maxWidth: 520, textAlign: "center", lineHeight: 1.5 }}>
            Configure a log source — CloudWatch, S3, Azure Blob, GCP, Datadog,
            or Elasticsearch — and DeepSQL will pull fresh slow queries every
            day, build the 30-day timeline, and attribute load per customer
            automatically.
          </p>
          <button
            type="button"
            className={styles.analyzeButton}
            onClick={() => setLogSourceModalOpen(true)}
          >
            <FileText size={14} /> Configure log source
          </button>
        </div>
      )}

      {regressions.length > 0 && (
        <div className={styles.regressionStrip}>
          <div className={styles.regressionHeader}>
            <AlertTriangle size={15} />
            {regressions.length} quer{regressions.length === 1 ? "y" : "ies"} regressed
            on the latest run
          </div>
          <div className={styles.regressionList}>
            {regressions.slice(0, 6).map((r) => (
              <button
                key={r.fingerprint}
                className={styles.regressionItem}
                onClick={() => setSelected(r.fingerprint)}
                title={r.normalizedSql || r.fingerprint}
              >
                <RegressionBadge factor={r.regressionFactor} />
                <span className={styles.regressionSql}>
                  {r.label || "Query"}
                </span>
              </button>
            ))}
          </div>
        </div>
      )}

      <div className={styles.body}>
        {/* ── Left: tracked query list ─────────────────────────────── */}
        <div className={styles.listPane}>
          <div className={styles.paneTitle}>
            Tracked queries {queries.length > 0 && <span>({queries.length})</span>}
          </div>
          {queriesQ.isLoading && <div className={styles.muted}>Loading…</div>}
          {queriesQ.isError && (
            <div className={styles.muted}>Failed to load tracked queries.</div>
          )}
          {!queriesQ.isLoading && queries.length === 0 && (
            <div className={styles.muted}>
              No tracked queries yet. Run “Analyze now” to capture the first data point.
            </div>
          )}
          <div className={styles.queryList}>
            {queries.map((q) => (
              <button
                key={q.fingerprint}
                className={`${styles.queryRow} ${
                  selected === q.fingerprint ? styles.queryRowActive : ""
                }`}
                onClick={() => setSelected(q.fingerprint)}
              >
                <div className={styles.queryRowTop}>
                  <span className={styles.queryLabel}>{q.label || "Query"}</span>
                  <RegressionBadge factor={q.regressionFactor} />
                </div>
                <div className={styles.queryMeta}>
                  <Clock size={11} /> {fmtDuration(q.meanExecMs)} · {fmtNum(q.callsDelta)} calls
                </div>
              </button>
            ))}
          </div>
        </div>

        {/* ── Right: selected query detail ─────────────────────────── */}
        <div className={styles.detailPane}>
          {!selected && (
            <div className={styles.empty}>Pick a query to see its 30-day timeline.</div>
          )}

          {selected && (
            <>
              <div className={styles.detailHeader}>
                {selectedQuery?.label && (
                  <div className={styles.detailLabel}>{selectedQuery.label}</div>
                )}
                {selected && (
                  <div className={styles.detailHashRow}>
                    <span className={styles.detailHashLabel}>Query hash</span>
                    <code className={styles.detailHash}>{selected}</code>
                    <CopyButton text={selected} />
                  </div>
                )}
                {samples.length > 0 ? (
                  <div className={styles.sampleBlock}>
                    <div className={styles.sampleHead}>
                      <span className={styles.sampleHint}>
                        Example execution
                        {samples[0].customerName || samples[0].customerId
                          ? ` · ${samples[0].customerName || samples[0].customerId}`
                          : ""}
                        {samples[0].execMs != null
                          ? ` · ${fmtDuration(samples[0].execMs)}`
                          : ""}
                      </span>
                      <CopyButton text={samples[0].rawSql} />
                    </div>
                    <code className={styles.detailSql}>{samples[0].rawSql}</code>
                    {samples[0].truncated && (
                      <div className={styles.truncatedNote}>
                        Query text is truncated at the source — MySQL's
                        Performance Schema caps captured queries at
                        <code> performance_schema_max_sql_text_length </code>
                        (default 1024 bytes). The full text isn't available
                        because this specific query wasn't found in the
                        slow-query log either. Enable the slow log on the
                        instance running this query (master, not just the
                        read replica) for full-text recovery.
                      </div>
                    )}
                  </div>
                ) : (
                  <>
                    <code className={styles.detailSql}>
                      {selectedQuery?.normalizedSql || selected}
                    </code>
                    <div className={styles.sampleHint}>
                      No literal-bearing example captured yet — showing the
                      normalized form.
                    </div>
                  </>
                )}
              </div>

              <div className={styles.chartCard}>
                <div className={styles.cardTitle}>Mean execution time / day</div>
                {timelineQ.isLoading && <div className={styles.muted}>Loading timeline…</div>}
                {timelineQ.isError && (
                  <QueryError error={timelineQ.error} what="timeline" className={styles.muted} />
                )}
                {!timelineQ.isLoading && !timelineQ.isError && timeline.length === 0 && (
                  <div className={styles.muted}>No timeline data yet for this query.</div>
                )}
                {timeline.length > 0 && (
                  <ResponsiveContainer width="100%" height={240}>
                    <LineChart data={timeline} margin={{ top: 8, right: 16, bottom: 4, left: 4 }}>
                      <CartesianGrid strokeDasharray="3 3" stroke="#eee" />
                      <XAxis dataKey="day" tick={{ fontSize: 11 }} />
                      <YAxis
                        tick={{ fontSize: 11 }}
                        width={64}
                        domain={[0, "auto"]}
                        allowDecimals={false}
                        tickFormatter={fmtDuration}
                      />
                      <Tooltip
                        formatter={(value, key) =>
                          key === "Mean" || key === "Max"
                            ? fmtDuration(value)
                            : fmtNum(value)
                        }
                      />
                      <Line
                        type="monotone"
                        dataKey="meanExecMs"
                        name="Mean"
                        stroke="#111827"
                        strokeWidth={2}
                        dot={{ r: 2 }}
                      />
                      <Line
                        type="monotone"
                        dataKey="maxExecMs"
                        name="Max"
                        stroke="#9ca3af"
                        strokeWidth={1}
                        strokeDasharray="4 3"
                        dot={false}
                      />
                    </LineChart>
                  </ResponsiveContainer>
                )}
              </div>

              <div className={styles.customerCard}>
                <div className={styles.cardTitle}>
                  <Users size={14} /> Per-customer breakdown
                </div>
                {customersQ.isLoading && <div className={styles.muted}>Loading…</div>}
                {customersQ.isError && (
                  <QueryError error={customersQ.error} what="customer breakdown" className={styles.muted} />
                )}
                {!customersQ.isLoading && !customersQ.isError && customers.length === 0 && (
                  <div className={styles.muted}>
                    No per-customer data for this query — it isn’t filtered by the
                    tenant column, or no literal-bearing sample was captured.
                    Set the tenant column under Settings if it looks wrong.
                  </div>
                )}
                {customers.length > 0 && (
                  <table className={styles.customerTable}>
                    <thead>
                      <tr>
                        <th>Customer</th>
                        <th>Samples</th>
                        <th>Mean</th>
                        <th>Max</th>
                        <th>Trend</th>
                      </tr>
                    </thead>
                    <tbody>
                      {customers.map((c) => (
                        <tr key={c.customerId}>
                          <td>{c.customerName || c.customerId}</td>
                          <td>{fmtNum(c.sampleCount)}</td>
                          <td>{fmtDuration(c.meanExecMs)}</td>
                          <td>{fmtDuration(c.maxExecMs)}</td>
                          <td><RegressionBadge factor={c.regressionFactor} /></td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
              </div>
            </>
          )}
        </div>
      </div>

      {logSourceModalOpen && (
        <SlowQuerySourceModal
          connectionId={connectionId}
          connectionName={selectedConnection?.connectionName}
          dbType={selectedConnection?.dbType || "mysql"}
          onClose={() => {
            setLogSourceModalOpen(false);
            // Re-check by trying analyze-now once the user finishes configuring.
            // If they saved a source row, the next click on the button will
            // succeed and the empty state will clear on its own.
          }}
        />
      )}
    </div>
  );
}
