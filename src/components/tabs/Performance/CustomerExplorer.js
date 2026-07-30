/**
 * The Customer-axis view of slow-query analytics — the second dimension
 * alongside Query Trends.
 *
 * Left pane: every customer attributed to slow queries on this connection,
 * ranked by total slow time.
 * Right pane: when a customer is selected, every slow query attributed to
 * them. Clicking a query opens a modal with the literal-bearing samples
 * for that (customer, query) pair.
 */

import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Clock, Users, Copy, Check, X } from "lucide-react";
import { slowQueryAnalyticsAPI } from "@/lib/api/client";
import styles from "./CustomerExplorer.module.css";

const fmtDuration = (v) => {
  if (v == null) return "—";
  if (v < 1000) return `${Math.round(v)} ms`;
  if (v < 60000) return `${(v / 1000).toFixed(1)} s`;
  return `${(v / 60000).toFixed(1)} min`;
};
const fmtNum = (v) => (v == null ? "—" : Number(v).toLocaleString());

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
      {copied ? <Check size={11} /> : <Copy size={11} />}
      {copied ? "Copied" : "Copy"}
    </button>
  );
}

/** Modal showing literal-bearing samples for a (customer, query) pair. */
function QuerySamplesModal({ connectionId, customerId, customerName, fingerprint, label, onClose }) {
  const samplesQ = useQuery({
    queryKey: ["slowQueryAnalytics", "customerQuerySamples", connectionId, customerId, fingerprint],
    queryFn: () =>
      slowQueryAnalyticsAPI.getCustomerQuerySamples(connectionId, customerId, fingerprint),
    enabled: Boolean(connectionId && customerId && fingerprint),
  });
  const samples = samplesQ.data ?? [];

  return (
    <div className={styles.overlay} onClick={onClose}>
      <div className={styles.modal} onClick={(e) => e.stopPropagation()}>
        <div className={styles.modalHead}>
          <div>
            <div className={styles.modalTitle}>{label || "Query"}</div>
            <div className={styles.modalSub}>
              {customerName || customerId} · {fingerprint.slice(0, 12)}
            </div>
          </div>
          <button type="button" className={styles.closeBtn} onClick={onClose}>
            <X size={14} />
          </button>
        </div>

        {samplesQ.isLoading && <div className={styles.muted}>Loading samples…</div>}
        {!samplesQ.isLoading && samples.length === 0 && (
          <div className={styles.muted}>
            No literal-bearing samples captured for this customer + query yet.
            Slow-log ingestion gives the fullest coverage.
          </div>
        )}

        <div className={styles.sampleList}>
          {samples.map((s, i) => (
            <div key={i} className={styles.sampleItem}>
              <div className={styles.sampleHead}>
                <span className={styles.sampleMeta}>
                  Sample {i + 1} · {fmtDuration(s.execMs)}
                  {s.capturedAt ? ` · ${String(s.capturedAt).replace("T", " ").slice(0, 16)}` : ""}
                </span>
                <CopyButton text={s.rawSql} />
              </div>
              <pre className={styles.sampleSql}>{s.rawSql}</pre>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

export default function CustomerExplorer({ connectionId }) {
  const [selected, setSelected] = useState(null);
  const [openSample, setOpenSample] = useState(null);

  const customersQ = useQuery({
    queryKey: ["slowQueryAnalytics", "customerList", connectionId],
    queryFn: () => slowQueryAnalyticsAPI.listCustomers(connectionId),
    enabled: Boolean(connectionId),
  });

  const queriesQ = useQuery({
    queryKey: ["slowQueryAnalytics", "customerQueries", connectionId, selected],
    queryFn: () => slowQueryAnalyticsAPI.getCustomerQueries(connectionId, selected),
    enabled: Boolean(connectionId) && Boolean(selected),
  });

  const customers = customersQ.data ?? [];
  const queries = queriesQ.data ?? [];
  const selectedCustomer = customers.find((c) => c.customerId === selected);

  if (!connectionId) {
    return <div className={styles.empty}>Select a connection to see customers.</div>;
  }

  return (
    <div className={styles.container}>
      <div className={styles.body}>
        {/* ── Left: customer list ───────────────────────────────────── */}
        <div className={styles.listPane}>
          <div className={styles.paneTitle}>
            Customers {customers.length > 0 && <span>({customers.length})</span>}
          </div>
          {customersQ.isLoading && <div className={styles.muted}>Loading…</div>}
          {!customersQ.isLoading && customers.length === 0 && (
            <div className={styles.muted}>
              No customer-attributed slow queries yet. Configure the tenant
              column under Settings, then run an analysis.
            </div>
          )}
          <div className={styles.customerList}>
            {customers.map((c) => (
              <button
                key={c.customerId}
                className={`${styles.customerRow} ${
                  selected === c.customerId ? styles.customerRowActive : ""
                }`}
                onClick={() => setSelected(c.customerId)}
              >
                <div className={styles.customerName}>
                  <Users size={12} />
                  <span>{c.customerName || c.customerId}</span>
                </div>
                <div className={styles.customerMeta}>
                  {fmtNum(c.queryCount)} quer{c.queryCount === 1 ? "y" : "ies"} ·{" "}
                  {fmtNum(c.sampleCount)} executions
                </div>
                <div className={styles.customerMeta}>
                  <Clock size={11} /> {fmtDuration(c.totalSlowMs)} total ·{" "}
                  worst {fmtDuration(c.slowestMeanMs)}
                </div>
              </button>
            ))}
          </div>
        </div>

        {/* ── Right: that customer's queries ─────────────────────────── */}
        <div className={styles.detailPane}>
          {!selected && (
            <div className={styles.empty}>
              Pick a customer to see the slow queries attributed to them.
            </div>
          )}

          {selected && (
            <>
              <div className={styles.detailHeader}>
                <div className={styles.detailTitle}>
                  <Users size={15} />
                  {selectedCustomer?.customerName || selected}
                </div>
                {selectedCustomer && (
                  <div className={styles.detailMeta}>
                    {fmtNum(selectedCustomer.queryCount)} slow quer
                    {selectedCustomer.queryCount === 1 ? "y" : "ies"} ·{" "}
                    {fmtNum(selectedCustomer.sampleCount)} executions ·{" "}
                    {fmtDuration(selectedCustomer.totalSlowMs)} total
                  </div>
                )}
                <div className={styles.detailHint}>
                  Click a row to see the full literal SQL captured for this customer.
                </div>
              </div>

              {queriesQ.isLoading && (
                <div className={styles.muted}>Loading queries…</div>
              )}
              {!queriesQ.isLoading && queries.length === 0 && (
                <div className={styles.muted}>
                  No queries rolled up yet for this customer.
                </div>
              )}
              {queries.length > 0 && (
                <table className={styles.queryTable}>
                  <thead>
                    <tr>
                      <th>Query</th>
                      <th>Mean</th>
                      <th>Max</th>
                      <th>Executions</th>
                      <th>Last seen</th>
                    </tr>
                  </thead>
                  <tbody>
                    {queries.map((q) => (
                      <tr
                        key={q.fingerprint}
                        className={styles.queryRow}
                        onClick={() =>
                          setOpenSample({
                            fingerprint: q.fingerprint,
                            label: q.label,
                          })
                        }
                      >
                        <td className={styles.qLabel}>
                          <div className={styles.qLabelText}>{q.label || "Query"}</div>
                          <div className={styles.qLabelId}>
                            {q.fingerprint.slice(0, 12)}
                          </div>
                        </td>
                        <td>{fmtDuration(q.meanExecMs)}</td>
                        <td>{fmtDuration(q.maxExecMs)}</td>
                        <td>{fmtNum(q.sampleCount)}</td>
                        <td className={styles.qDay}>{q.latestDay || "—"}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </>
          )}
        </div>
      </div>

      {openSample && (
        <QuerySamplesModal
          connectionId={connectionId}
          customerId={selected}
          customerName={selectedCustomer?.customerName}
          fingerprint={openSample.fingerprint}
          label={openSample.label}
          onClose={() => setOpenSample(null)}
        />
      )}
    </div>
  );
}
