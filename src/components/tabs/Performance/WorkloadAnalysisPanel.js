/**
 * Workload Analysis — the holistic, on-demand report.
 *
 * Triggers a long-running backend job that composes one report from four
 * sources: index recommendations (the workload-weighted advisor), query
 * rewrites (the rewrites-only optimizer), pre-aggregation / materialized-view
 * recommendations (the net-new analyzer), and index-health cleanup. The job
 * runs in the background; this panel polls status while it runs and renders
 * the latest report when it lands.
 *
 * Backed by /api/workload-analysis/** (workloadAnalysisAPI).
 */

import { useRef, useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { format as sqlFormat } from "sql-formatter";
import {
  Zap,
  RefreshCw,
  AlertTriangle,
  Database,
  Wrench,
  Layers,
  Trash2,
  Copy,
  Check,
  CheckCircle2,
} from "lucide-react";
import { workloadAnalysisAPI } from "@/lib/api/client";
import { HelpTooltip } from "@/components/tabs/Brain/components/HelpTooltip";
import styles from "./WorkloadAnalysisPanel.module.css";

const fmtMs = (v) => {
  if (v == null) return "—";
  if (v < 1000) return `${Math.round(v)} ms`;
  if (v < 60000) return `${(v / 1000).toFixed(1)} s`;
  return `${(v / 60000).toFixed(1)} min`;
};

const RUNNING = new Set(["PENDING", "RUNNING"]);

/** Best-effort SQL pretty-printer; returns the input untouched if it can't parse. */
function formatSqlSafe(sql) {
  if (!sql || typeof sql !== "string") return sql || "";
  try {
    return sqlFormat(sql, { language: "mysql", tabWidth: 2, keywordCase: "upper" });
  } catch {
    return sql;
  }
}

const REASON_LABEL = {
  MOST_AFFECTED: "Most affected",
  REGRESSED: "Regressed",
  HIGH_VOLUME: "High volume",
};

function CopyButton({ text }) {
  const [copied, setCopied] = useState(false);
  return (
    <button
      type="button"
      className={styles.copyBtn}
      onClick={async () => {
        try {
          await navigator.clipboard.writeText(text);
          setCopied(true);
          setTimeout(() => setCopied(false), 1500);
        } catch {
          /* clipboard blocked — no-op */
        }
      }}
      title="Copy SQL"
    >
      {copied ? <Check size={12} /> : <Copy size={12} />}
      {copied ? "Copied" : "Copy"}
    </button>
  );
}

export default function WorkloadAnalysisPanel({ connectionId }) {
  const queryClient = useQueryClient();
  const [runError, setRunError] = useState(null);

  // Section anchors so the summary tiles can jump straight to a section
  // instead of forcing the user to scroll past every candidate.
  const sectionRefs = useRef({});
  const scrollToSection = (key) => {
    const el = sectionRefs.current[key];
    if (el) el.scrollIntoView({ behavior: "smooth", block: "start" });
  };

  // Latest report (the full body). Refetched when a run completes.
  const latestQ = useQuery({
    queryKey: ["workloadAnalysis", "latest", connectionId],
    queryFn: () => workloadAnalysisAPI.latest(connectionId),
    enabled: Boolean(connectionId),
  });

  const latest = latestQ.data;
  const isRunning = latest && RUNNING.has(latest.status);

  // Poll status only while a run is in flight; stop when it settles.
  useQuery({
    queryKey: ["workloadAnalysis", "status", connectionId],
    queryFn: async () => {
      const s = await workloadAnalysisAPI.status(connectionId);
      // When the status flips out of running, pull the full report once.
      if (s && !RUNNING.has(s.status)) {
        queryClient.invalidateQueries({
          queryKey: ["workloadAnalysis", "latest", connectionId],
        });
      } else if (s) {
        // Cheap progress update without refetching the whole blob.
        queryClient.setQueryData(["workloadAnalysis", "latest", connectionId], (prev) =>
          prev ? { ...prev, ...s } : prev,
        );
      }
      return s ?? null;
    },
    enabled: Boolean(connectionId) && Boolean(isRunning),
    refetchInterval: 3000,
    refetchIntervalInBackground: false,
  });

  const runMutation = useMutation({
    mutationFn: () => workloadAnalysisAPI.run(connectionId),
    onSuccess: () => {
      setRunError(null);
      queryClient.invalidateQueries({
        queryKey: ["workloadAnalysis", "latest", connectionId],
      });
    },
    onError: (err) => {
      setRunError(
        err?.response?.data?.message || err?.message || "Failed to start analysis",
      );
    },
  });

  if (!connectionId) {
    return <div className={styles.empty}>Select a database connection to analyze its workload.</div>;
  }

  const data = latest?.reportData;
  const hasReport = latest && latest.status === "COMPLETED" && data;

  return (
    <div className={styles.container}>
      <div className={styles.actionBar}>
        <button
          className={styles.analyzeButton}
          onClick={() => runMutation.mutate()}
          disabled={isRunning || runMutation.isPending}
        >
          <Zap size={14} className={isRunning ? styles.spin : ""} />
          {isRunning ? "Analyzing…" : "Analyze workload"}
        </button>
      </div>

      {/* progress while running */}
      {isRunning && (
        <div className={`${styles.notice} ${styles.notice_busy}`}>
          <RefreshCw size={14} className={styles.spin} />
          <div className={styles.progressWrap}>
            <div className={styles.progressLabel}>
              {latest.currentStep || "Working"}… {latest.progress ?? 0}%
            </div>
            <div className={styles.progressTrack}>
              <div
                className={styles.progressBar}
                style={{ width: `${Math.max(2, Math.min(100, latest.progress ?? 0))}%` }}
              />
            </div>
            <div className={styles.progressHint}>
              This triggers a fresh index-advisor cycle and runs AI rewrites for
              the top queries — typically 1–3 minutes. The report appears here
              automatically.
            </div>
          </div>
        </div>
      )}

      {runError && (
        <div className={`${styles.notice} ${styles.notice_error}`}>
          <AlertTriangle size={14} /> {runError}
        </div>
      )}

      {latest?.status === "FAILED" && !isRunning && (
        <div className={`${styles.notice} ${styles.notice_error}`}>
          <AlertTriangle size={14} />
          Last analysis failed{latest.message ? ` — ${latest.message}` : ""}. Try again.
        </div>
      )}

      {/* empty state — never run */}
      {!latest && !latestQ.isLoading && (
        <div className={styles.emptyState}>
          <Zap size={30} style={{ opacity: 0.4, marginBottom: 8 }} />
          <h3 className={styles.emptyTitle}>No workload analysis yet</h3>
          <p className={styles.emptyText}>
            Run a holistic analysis to get index recommendations, query
            rewrites, and pre-aggregation opportunities across your most
            impactful queries — in one report.
          </p>
        </div>
      )}

      {/* the report */}
      {hasReport && (
        <>
          {data.summary?.headline && (
            <div className={styles.headline}>
              <CheckCircle2 size={15} className={styles.headlineIcon} />
              <span>{data.summary.headline}</span>
              {latest.completedAt && (
                <span className={styles.headlineMeta}>
                  {new Date(latest.completedAt).toLocaleString()}
                </span>
              )}
            </div>
          )}

          {(() => {
            const unusedCount = (data.indexHealth ?? []).filter((h) => h.issue === "UNUSED").length;
            return (
              <div className={styles.statRow}>
                <Stat label="Candidates" value={latest.candidateCount} />
                <Stat label="Index recs" value={latest.indexRecCount} onClick={() => scrollToSection("index")} />
                <Stat label="Rewrites" value={latest.rewriteRecCount} onClick={() => scrollToSection("rewrites")} />
                <Stat label="Pre-aggregations" value={latest.preAggRecCount} onClick={() => scrollToSection("preagg")} />
                <Stat label="Unused indexes" value={unusedCount} onClick={() => scrollToSection("health")} />
              </div>
            );
          })()}

          {/* Index recommendations */}
          <Section
            icon={Database}
            title="Index recommendations"
            count={data.indexRecommendations?.length}
            anchorRef={(el) => (sectionRefs.current.index = el)}
          >
            {(data.indexRecommendations ?? []).length === 0 ? (
              <Muted>No index recommendations from the workload advisor right now.</Muted>
            ) : (
              data.indexRecommendations.map((rec, i) => (
                <div key={rec.recommendationId || i} className={styles.recCard}>
                  <div className={styles.recHead}>
                    <span className={`${styles.kindBadge} ${rec.kind === "DROP_INDEX" ? styles.kindDrop : styles.kindCreate}`}>
                      {rec.kind === "DROP_INDEX" ? "DROP" : "CREATE"}
                    </span>
                    <code className={styles.recTitle}>
                      {rec.tableName} ({rec.columnNames})
                    </code>
                    {rec.priority && (
                      <span className={`${styles.prio} ${styles[`prio_${(rec.priority || "").toLowerCase()}`] || ""}`}>
                        {rec.priority}
                      </span>
                    )}
                  </div>
                  <div className={styles.recMetaRow}>
                    <span>Net benefit: <strong>{fmtMs(rec.netBenefit)}</strong></span>
                    <span>Write cost: {fmtMs(rec.writeCostScore)}</span>
                    {rec.hypopgReductionPct != null && (
                      <span>HypoPG: −{rec.hypopgReductionPct.toFixed(0)}% cost</span>
                    )}
                    {rec.contributingQueries > 0 && (
                      <span>{rec.contributingQueries} contributing quer{rec.contributingQueries === 1 ? "y" : "ies"}</span>
                    )}
                  </div>
                  {rec.rationale && <div className={styles.recBody}>{rec.rationale}</div>}
                  {rec.createStatement && rec.kind !== "DROP_INDEX" && (
                    <div className={styles.ddlBlock}>
                      <div className={styles.ddlHead}>
                        <span>CREATE statement</span>
                        <CopyButton text={rec.createStatement} />
                      </div>
                      <pre className={styles.ddlPre}>{rec.createStatement}</pre>
                    </div>
                  )}
                </div>
              ))
            )}
          </Section>

          {/* Pre-aggregation recommendations */}
          <Section
            icon={Layers}
            title="Pre-aggregation opportunities"
            count={data.preAggregationRecommendations?.length}
            anchorRef={(el) => (sectionRefs.current.preagg = el)}
          >
            {(data.preAggregationRecommendations ?? []).length === 0 ? (
              <Muted>No shared GROUP BY / aggregate patterns worth materializing.</Muted>
            ) : (
              data.preAggregationRecommendations.map((rec, i) => (
                <div key={i} className={styles.recCard}>
                  <div className={styles.recHead}>
                    <span className={styles.mvBadge}>MATERIALIZE</span>
                    <code className={styles.recTitle}>{rec.suggestedName}</code>
                    <span className={styles.prio}>{rec.sharedByQueries} quer{rec.sharedByQueries === 1 ? "y" : "ies"}</span>
                  </div>
                  <div className={styles.recMetaRow}>
                    <span>On: <strong>{(rec.sourceTables || []).join(", ")}</strong></span>
                    <span>Group by: {(rec.dimensions || []).join(", ")}</span>
                    <span>Saves: {fmtMs(rec.workloadScoreMs)}/period</span>
                  </div>
                  {rec.rationale && <div className={styles.recBody}>{rec.rationale}</div>}
                  {rec.materializationDdl && (
                    <div className={styles.ddlBlock}>
                      <div className={styles.ddlHead}>
                        <span>DDL</span>
                        <CopyButton text={rec.materializationDdl} />
                      </div>
                      <pre className={styles.ddlPre}>{rec.materializationDdl}</pre>
                    </div>
                  )}
                  {rec.refreshStrategy && (
                    <div className={styles.refreshNote}>↻ {rec.refreshStrategy}</div>
                  )}
                </div>
              ))
            )}
          </Section>

          {/* Query rewrites */}
          <Section
            icon={Wrench}
            title="Query rewrites"
            count={data.rewriteRecommendations?.length}
            anchorRef={(el) => (sectionRefs.current.rewrites = el)}
          >
            {(data.rewriteRecommendations ?? []).length === 0 ? (
              <Muted>No beneficial rewrites found for the top candidates.</Muted>
            ) : (
              data.rewriteRecommendations.map((rec, i) => (
                <div key={rec.fingerprint || i} className={styles.recCard}>
                  <div className={styles.recHead}>
                    {rec.candidateReason && (
                      <span className={styles.reasonBadge}>{REASON_LABEL[rec.candidateReason] || rec.candidateReason}</span>
                    )}
                    {rec.fingerprint && (
                      <HelpTooltip content={{
                        title: 'Query fingerprint',
                        description: 'Look this up under Performance → Query Trends to see historical performance.',
                      }}>
                        <span className={styles.fingerprint}>
                          <span className={styles.fingerprintLabel}>fingerprint</span>
                          <code>{rec.fingerprint}</code>
                          <CopyButton text={rec.fingerprint} />
                        </span>
                      </HelpTooltip>
                    )}
                    {rec.estimatedImprovementPct != null && (
                      <span className={styles.improve}>~{Math.round(rec.estimatedImprovementPct)}% faster</span>
                    )}
                  </div>
                  {rec.diagnosis && <div className={styles.recBody}>{rec.diagnosis}</div>}
                  <div className={styles.diffGrid}>
                    {rec.originalQuery && (
                      <div className={`${styles.ddlBlock} ${styles.ddlOriginal}`}>
                        <div className={styles.ddlHead}>
                          <span>Original</span>
                          <CopyButton text={rec.originalQuery} />
                        </div>
                        <pre className={styles.ddlPre}>{formatSqlSafe(rec.originalQuery)}</pre>
                      </div>
                    )}
                    <div className={styles.ddlBlock}>
                      <div className={styles.ddlHead}>
                        <span>Rewrite</span>
                        <CopyButton text={rec.rewrittenQuery} />
                      </div>
                      <pre className={styles.ddlPre}>{formatSqlSafe(rec.rewrittenQuery)}</pre>
                    </div>
                  </div>
                </div>
              ))
            )}
          </Section>

          {/* Index health */}
          <Section
            icon={Trash2}
            title="Index cleanup"
            count={data.indexHealth?.length}
            anchorRef={(el) => (sectionRefs.current.health = el)}
          >
            {(data.indexHealth ?? []).length === 0 ? (
              <Muted>No unused or duplicate indexes detected.</Muted>
            ) : (
              data.indexHealth.map((h, i) => (
                <div key={i} className={styles.healthRow}>
                  <span className={`${styles.healthBadge} ${h.issue === "DUPLICATE" ? styles.healthDup : styles.healthUnused}`}>
                    {h.issue}
                  </span>
                  <code className={styles.healthName}>
                    {h.tableName || "—"}{h.indexName ? `.${h.indexName}` : ""}
                  </code>
                  {h.detail && <span className={styles.healthDetail}>{h.detail}</span>}
                  {h.suggestedAction && <span className={styles.healthAction}>{h.suggestedAction}</span>}
                </div>
              ))
            )}
          </Section>
        </>
      )}
    </div>
  );
}

function Stat({ label, value, onClick }) {
  const clickable = typeof onClick === "function" && (value ?? 0) > 0;
  return (
    <div
      className={`${styles.stat} ${clickable ? styles.statClickable : ""}`}
      onClick={clickable ? onClick : undefined}
      role={clickable ? "button" : undefined}
      tabIndex={clickable ? 0 : undefined}
      onKeyDown={clickable ? (e) => (e.key === "Enter" || e.key === " ") && onClick() : undefined}
    >
      <div className={styles.statValue}>{value ?? 0}</div>
      <div className={styles.statLabel}>{label}</div>
    </div>
  );
}

function Section({ icon: Icon, title, count, children, anchorRef }) {
  return (
    <div className={styles.section} ref={anchorRef} style={{ scrollMarginTop: 12 }}>
      <div className={styles.sectionHead}>
        <Icon size={15} />
        <span className={styles.sectionTitle}>{title}</span>
        {typeof count === "number" && <span className={styles.sectionCount}>{count}</span>}
      </div>
      <div className={styles.sectionBody}>{children}</div>
    </div>
  );
}

function Muted({ children }) {
  return <div className={styles.muted}>{children}</div>;
}
