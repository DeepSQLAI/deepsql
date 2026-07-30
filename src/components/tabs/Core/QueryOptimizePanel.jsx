/**
 * QueryOptimizePanel — renders the AI rewrite for an editor query.
 *
 * Shows a verdict (rewrite found vs already optimal), the original and
 * rewritten SQL side-by-side (formatted), the estimated improvement, and the
 * AI explanation — which includes a "why the indexes aren't kicking in"
 * diagnosis (function-wrapped predicates, type/collation mismatch,
 * non-leading composite columns, non-sargable forms) and what the rewrite
 * changed. Backed by /api/slow-queries/optimize (explainAPI.optimizeQuery).
 */

import { useState } from "react";
import { format as sqlFormat } from "sql-formatter";
import { Sparkles, Copy, Check, CheckCircle2, TrendingUp } from "lucide-react";
import styles from "./ExplainAnalysisPanel.module.css";

const formatSqlSafe = (sql) => {
  if (!sql || typeof sql !== "string") return sql || "";
  try {
    return sqlFormat(sql, { language: "mysql", tabWidth: 2, keywordCase: "upper" });
  } catch {
    return sql;
  }
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
          /* clipboard blocked */
        }
      }}
      title="Copy SQL"
    >
      {copied ? <Check size={12} /> : <Copy size={12} />}
      {copied ? "Copied" : "Copy"}
    </button>
  );
}

function Narrative({ text }) {
  if (!text) return null;
  return (
    <div className={styles.narrative}>
      {text.split("\n").map((line, i) => {
        const trimmed = line.trim();
        if (!trimmed) return <div key={i} className={styles.narrativeGap} />;
        const parts = trimmed.split(/(\*\*[^*]+\*\*)/g).filter(Boolean);
        return (
          <p key={i}>
            {parts.map((p, j) =>
              p.startsWith("**") && p.endsWith("**") ? <strong key={j}>{p.slice(2, -2)}</strong> : <span key={j}>{p}</span>,
            )}
          </p>
        );
      })}
    </div>
  );
}

export default function QueryOptimizePanel({ result }) {
  if (!result) return null;

  const rewrite = result.optimizedQuery;
  const original = result.originalQuery;
  const hasRewrite =
    rewrite && rewrite.trim() && original && rewrite.trim().toLowerCase() !== original.trim().toLowerCase();
  const improvement = result.estimatedImprovement;

  return (
    <div className={styles.panel}>
      <div className={`${styles.banner} ${hasRewrite ? styles.banner_warn : styles.banner_ok}`}>
        {hasRewrite ? <Sparkles size={16} /> : <CheckCircle2 size={16} />}
        <div className={styles.bannerBody}>
          <div className={styles.bannerTitle}>
            {hasRewrite ? "AI suggested a rewrite" : "No beneficial rewrite — the query already looks well-formed"}
          </div>
          <div className={styles.bannerNote}>
            {hasRewrite
              ? "Review the diff and explanation below before applying. Validate against your data."
              : "The optimizer didn't find a sargable rewrite that would help. See the explanation for why."}
          </div>
        </div>
        {hasRewrite && improvement != null && improvement > 0 && (
          <div className={styles.bannerStat} title="Estimated improvement">
            <TrendingUp size={13} />
            <span className={styles.bannerStatValue}>~{Math.round(improvement)}%</span>
            <span className={styles.bannerStatLabel}>faster</span>
          </div>
        )}
      </div>

      {hasRewrite && (
        <div className={styles.diffGrid}>
          <div className={styles.ddlBlock}>
            <div className={styles.ddlHead}>
              <span>Original</span>
              <CopyButton text={original} />
            </div>
            <pre className={styles.ddlPre}>{formatSqlSafe(original)}</pre>
          </div>
          <div className={styles.ddlBlock}>
            <div className={styles.ddlHead}>
              <span>AI rewrite</span>
              <CopyButton text={rewrite} />
            </div>
            <pre className={styles.ddlPre}>{formatSqlSafe(rewrite)}</pre>
          </div>
        </div>
      )}

      {result.explanation && (
        <div className={styles.optSection}>
          <h4 className={styles.optHeading}>Explanation</h4>
          <Narrative text={result.explanation} />
        </div>
      )}

      {Array.isArray(result.suggestions) && result.suggestions.length > 0 && (
        <div className={styles.optSection}>
          <h4 className={styles.optHeading}>Suggestions</h4>
          {result.suggestions.map((s, i) => (
            <div key={i} className={`${styles.card} ${styles.card_warn}`}>
              <div className={styles.cardHead}>
                <code className={styles.cardTitle}>
                  {(s.category || s.type || "SUGGESTION")}{s.title ? ` — ${s.title}` : ""}
                </code>
                {s.priority && <span className={`${styles.chip} ${styles.chip_warn}`}>{s.priority}</span>}
              </div>
              {s.description && <div className={styles.cardText}>{s.description}</div>}
              {s.implementationSQL && (
                <div className={styles.ddlBlock} style={{ marginTop: 8 }}>
                  <div className={styles.ddlHead}>
                    <span>SQL</span>
                    <CopyButton text={s.implementationSQL} />
                  </div>
                  <pre className={styles.ddlPre}>{formatSqlSafe(s.implementationSQL)}</pre>
                </div>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
