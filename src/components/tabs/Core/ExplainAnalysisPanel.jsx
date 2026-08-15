/**
 * ExplainAnalysisPanel — organized, tabbed view of a query execution plan.
 *
 * The AI narrative lives in Summary; the rest is deterministic analysis derived
 * by walking the parsed plan tree (works for both Postgres and MySQL shapes):
 *   Summary · Plan Tree · Indexes · Joins · Grouping · Filters · Metrics
 *
 * Plans report table ALIASES (ub, hs, pb); we resolve them back to real table
 * names using an alias→table map parsed from the query, and surface the
 * join/lookup predicates and grouping/sort expressions so a technical reader
 * can see exactly what each step operates on.
 */

import { useMemo, useState } from "react";
import {
  ChevronDown,
  ChevronRight,
  CheckCircle2,
  AlertTriangle,
  Clock,
  Database,
  GitMerge,
  Layers,
  Filter as FilterIcon,
  Gauge,
  ListTree,
  FileText,
} from "lucide-react";
import styles from "./ExplainAnalysisPanel.module.css";
import treeStyles from "../Performance/ExplainPlanTab.module.css";

const fmtNum = (n) => (n == null ? "—" : Number(n).toLocaleString());
const fmtMs = (n) => {
  if (n == null) return "—";
  if (n < 1000) return `${Number(n).toFixed(2)} ms`;
  if (n < 60000) return `${(n / 1000).toFixed(2)} s`;
  return `${(n / 60000).toFixed(1)} min`;
};
const lc = (s) => (s || "").toLowerCase();

const walk = (node, out = []) => {
  if (!node) return out;
  out.push(node);
  (node.children || []).forEach((c) => walk(c, out));
  return out;
};

// ── alias → real table resolution ──────────────────────────────────
const STOP_WORDS = new Set([
  "on", "where", "group", "order", "left", "right", "inner", "outer",
  "join", "using", "limit", "having", "set", "values", "select", "as", "and", "or",
]);

const buildAliasMap = (query) => {
  const map = {};
  if (!query || typeof query !== "string") return map;
  const q = query.replace(/`/g, "").replace(/\s+/g, " ");
  // FROM/JOIN <table> [AS] <alias>
  const re = /\b(?:from|join)\s+(\w+)\s+(?:as\s+)?(\w+)/gi;
  let m;
  while ((m = re.exec(q)) !== null) {
    const alias = m[2].toLowerCase();
    if (!STOP_WORDS.has(alias) && m[1].toLowerCase() !== alias) map[alias] = m[1];
  }
  // comma-separated FROM list: FROM a x, b y
  const fromMatch = /\bfrom\s+(.+?)(?:\bwhere\b|\bgroup\b|\border\b|\bhaving\b|\blimit\b|$)/i.exec(q);
  if (fromMatch) {
    fromMatch[1].split(",").forEach((part) => {
      const mm = /^\s*(\w+)\s+(?:as\s+)?(\w+)\s*$/i.exec(part.trim());
      if (mm && !STOP_WORDS.has(mm[2].toLowerCase())) map[mm[2].toLowerCase()] = mm[1];
    });
  }
  return map;
};

const resolveAlias = (name, map) => (name ? map[name.toLowerCase()] || name : name);
const resolveText = (text, map) =>
  !text ? text : text.replace(/\b(\w+)\./g, (full, a) => (map[a.toLowerCase()] ? `${map[a.toLowerCase()]}.` : full));

const nodeLabel = (n, map = {}) => {
  let label = n.nodeType || n.selectType || "Step";
  if (n.tableName) {
    const real = resolveAlias(n.tableName, map);
    label += ` on ${real}`;
    if (real.toLowerCase() !== n.tableName.toLowerCase()) label += ` (${n.tableName})`;
  }
  if (n.key) label += ` using ${n.key}`;
  return label;
};

const nearestTable = (node, map) => {
  if (!node) return null;
  if (node.tableName) return resolveAlias(node.tableName, map);
  for (const c of node.children || []) {
    const t = nearestTable(c, map);
    if (t) return t;
  }
  return null;
};

// Bottlenecks are judged from what the query ACTUALLY did when it ran — index
// not kicking in (full scan), predicate not index-served (filter waste), bad
// query shape (temp table / filesort), or a step that genuinely processes huge
// row volumes. We deliberately do NOT surface planner estimate-vs-actual skew:
// that's a stats/optimizer artifact, not something the end user can act on.
const FULL_SCAN_ROW_FLOOR = 1000;       // a full scan under this is harmless
const HEAVY_ROWS = 1_000_000;           // a step touching this many rows is a real cost

function analyzePlan(planTree, map) {
  const nodes = walk(planTree).filter((n) => n && (n.nodeType || n.tableName));
  const indexes = [];
  const joins = [];
  const grouping = [];
  const filters = [];
  const concerns = [];
  let indexBottleneck = false;

  for (const n of nodes) {
    const t = lc(n.nodeType);
    if (t.includes("join") || t.includes("nested loop")) {
      joins.push({ n, ...joinVerdict(n, concerns), detail: joinDetail(n, map) });
    } else if (
      t.includes("aggregate") || t.includes("group") || t.includes("sort") ||
      t.includes("materialize") || t.includes("temporary") || t.includes("window")
    ) {
      grouping.push({ n, ...groupingVerdict(n, concerns), detail: n.extra ? resolveText(n.extra, map) : null });
    } else if (t.includes("scan") || t.includes("lookup") || t.includes("index")) {
      const v = indexVerdict(n, concerns);
      if (v.fullScan) indexBottleneck = true;
      indexes.push({ n, level: v.level, text: v.text, detail: n.indexCondition ? resolveText(n.indexCondition, map) : null });
    }

    const isFilter =
      lc(n.nodeType) === "filter" || (n.filter && n.filter.trim()) || (n.rowsRemovedByFilter > 0);
    if (isFilter) filters.push({ n, ...filterVerdict(n, map, concerns) });
  }

  return { indexes, joins, grouping, filters, concerns, indexBottleneck };
}

function joinDetail(n, map) {
  const kids = n.children || [];
  const tables = [...new Set(kids.map((k) => nearestTable(k, map)).filter(Boolean))];
  const cond = kids.map((k) => k.indexCondition).find(Boolean);
  let s = "";
  if (tables.length >= 2) s = `Joins ${tables.join(" ⋈ ")}`;
  else if (tables.length === 1) s = `Joins into ${tables[0]}`;
  if (cond) s += `${s ? " on " : "Condition: "}${resolveText(cond, map)}`;
  return s || null;
}

function indexVerdict(n, concerns) {
  const t = lc(n.nodeType);
  const access = lc(n.accessType);
  const fullScan = !n.key && (t.includes("seq scan") || t.includes("table scan") || t.includes("full") || access === "all");
  if (fullScan) {
    const rows = n.actualRows ?? n.planRows ?? 0;
    const big = rows >= FULL_SCAN_ROW_FLOOR;
    if (big) concerns.push(`Full scan on ${n.tableName || "a table"} — no index is kicking in (${fmtNum(rows)} rows read)`);
    return {
      fullScan: true,
      level: big ? "bad" : "warn",
      text: `Full ${n.tableName || "table"} scan — no index used${big ? ". The query reads the whole table instead of targeting rows; the filter/join columns aren't index-served." : "."}`,
    };
  }
  if (n.key) {
    return { fullScan: false, level: "good", text: `Uses index ${n.key}.` };
  }
  return { fullScan: false, level: "warn", text: `${n.nodeType || "Scan"} — no index reported for this access.` };
}

function joinVerdict(n, concerns) {
  const loops = n.actualLoops || 1;
  const perLoop = n.actualRows;
  const rows = loops > 1 && perLoop != null ? loops * perLoop : perLoop;
  const parts = [n.nodeType];
  let level = "good";
  if (loops > 1) parts.push(`runs ${fmtNum(loops)}× (inner side)`);
  if (rows != null) parts.push(`~${fmtNum(rows)} rows processed`);
  if (rows != null && rows >= HEAVY_ROWS) {
    level = "bad";
    concerns.push(`A join processes ~${fmtNum(rows)} rows — a join-key index or different join order would cut this`);
  }
  if (level === "good" && parts.length === 1) parts.push("no bottleneck");
  return { level, text: parts.join(" · ") };
}

function groupingVerdict(n, concerns) {
  const extra = lc(n.extra);
  const tmp = extra.includes("temporary");
  const filesort = extra.includes("filesort");
  const parts = [n.nodeType];
  let level = "good";
  if (tmp || filesort) {
    level = "warn";
    parts.push(`${[tmp && "uses a temporary table", filesort && "sorts on disk (filesort)"].filter(Boolean).join(" and ")} — an index matching the GROUP BY / ORDER BY can avoid it`);
    concerns.push(`${n.nodeType} ${tmp ? "materializes a temporary table" : "sorts on disk"} — the query shape forces extra work`);
  } else {
    parts.push("no bottleneck");
  }
  return { level, text: parts.join(" · ") };
}

function filterVerdict(n, map, concerns) {
  const removed = n.rowsRemovedByFilter;
  const kept = n.actualRows;
  const cond = n.filter ? `Condition: ${resolveText(n.filter, map)}` : "";
  if (removed != null && kept != null && removed + kept > 0) {
    const wastePct = (removed / (removed + kept)) * 100;
    if (wastePct >= 90) {
      concerns.push(`A filter discards ${wastePct.toFixed(0)}% of the rows it reads — the predicate isn't index-served`);
      return { level: "bad", text: `Discards ${wastePct.toFixed(0)}% of rows read (${fmtNum(removed)} removed, ${fmtNum(kept)} kept) — predicate isn't index-served. ${cond}` };
    }
    return { level: wastePct >= 50 ? "warn" : "good", text: `Removes ${wastePct.toFixed(0)}% of rows read (${fmtNum(removed)} of ${fmtNum(removed + kept)}). ${cond}` };
  }
  return { level: "good", text: cond || "Filter applied." };
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

function VerdictChip({ level }) {
  const label = level === "bad" ? "Problem" : level === "warn" ? "Watch" : "Efficient";
  return <span className={`${styles.chip} ${styles[`chip_${level}`]}`}>{label}</span>;
}

function NodeCard({ item, aliasMap }) {
  const { n, level, text, detail } = item;
  return (
    <div className={`${styles.card} ${styles[`card_${level}`]}`}>
      <div className={styles.cardHead}>
        <code className={styles.cardTitle}>{nodeLabel(n, aliasMap)}</code>
        <VerdictChip level={level} />
      </div>
      {detail && <div className={styles.cardDetail}>{detail}</div>}
      <div className={styles.cardMetrics}>
        {n.planRows != null && <span>est rows: <b>{fmtNum(n.planRows)}</b></span>}
        {n.actualRows != null && <span>actual rows: <b>{fmtNum(n.actualRows)}</b></span>}
        {n.actualLoops != null && n.actualLoops > 1 && <span>loops: <b>{fmtNum(n.actualLoops)}</b></span>}
        {n.actualTotalTime != null && <span>time: <b>{fmtMs(n.actualTotalTime)}</b></span>}
        {n.totalCost != null && <span>cost: <b>{Number(n.totalCost).toLocaleString()}</b></span>}
      </div>
      <div className={styles.cardText}>{text}</div>
    </div>
  );
}

function buildExpanded(node, path = ["root"], map = {}) {
  if (!node) return map;
  map[`node-${path.join("-")}`] = true;
  (node.children || []).forEach((c, i) => buildExpanded(c, [...path, i], map));
  return map;
}

function PlanTree({ planTree, aliasMap }) {
  const [expanded, setExpanded] = useState(() => buildExpanded(planTree));
  const toggle = (id) => setExpanded((p) => ({ ...p, [id]: !p[id] }));

  const renderNode = (node, path = ["root"]) => {
    const id = `node-${path.join("-")}`;
    const hasKids = node.children && node.children.length > 0;
    const open = expanded[id] !== false;
    const realTable = node.tableName ? resolveAlias(node.tableName, aliasMap) : null;
    const cond = node.indexCondition || (lc(node.nodeType) !== "filter" ? node.extra : null);
    return (
      <div key={id} className={treeStyles.planNode}>
        <div className={treeStyles.nodeHeader}>
          {hasKids ? (
            <button className={treeStyles.expandButton} onClick={() => toggle(id)}>
              {open ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
            </button>
          ) : (
            <div className={treeStyles.expandPlaceholder} />
          )}
          <div className={treeStyles.nodeContent}>
            <div className={treeStyles.nodeTitle}>
              {node.nodeType || node.selectType || "Step"}
              {realTable && (
                <span className={treeStyles.tableName}>
                  {" "}on {realTable}
                  {realTable.toLowerCase() !== node.tableName.toLowerCase() ? ` (${node.tableName})` : ""}
                </span>
              )}
              {node.key && <span className={styles.treeIndex}> using {node.key}</span>}
              {cond && <span className={styles.treeCond}> {resolveText(cond, aliasMap)}</span>}
            </div>
            <div className={treeStyles.nodeMetrics}>
              {node.planRows != null && <span className={treeStyles.metric}>est: {fmtNum(node.planRows)}</span>}
              {node.actualRows != null && <span className={treeStyles.metric}>actual: {fmtNum(node.actualRows)}</span>}
              {node.actualLoops != null && node.actualLoops > 1 && <span className={treeStyles.metric}>×{fmtNum(node.actualLoops)}</span>}
              {node.totalCost != null && <span className={treeStyles.metric}>cost: {Number(node.totalCost).toLocaleString()}</span>}
              {node.actualTotalTime != null && <span className={treeStyles.metric}>{fmtMs(node.actualTotalTime)}</span>}
            </div>
          </div>
        </div>
        {open && hasKids && (
          <div className={treeStyles.nodeChildren}>
            {node.children.map((c, i) => renderNode(c, [...path, i]))}
          </div>
        )}
      </div>
    );
  };

  return <div className={treeStyles.planTree}>{renderNode(planTree)}</div>;
}

export default function ExplainAnalysisPanel({ analysis }) {
  const aliasMap = useMemo(() => buildAliasMap(analysis?.query), [analysis]);
  const insights = useMemo(() => analyzePlan(analysis?.planTree, aliasMap), [analysis, aliasMap]);
  const [tab, setTab] = useState("summary");

  if (!analysis) return null;

  const { indexes, joins, grouping, filters, concerns, indexBottleneck } = insights;
  const hasScope = concerns.length > 0;
  const totalTime = analysis.executionTimeMs;

  const TABS = [
    { id: "summary", label: "Summary", icon: FileText, show: true },
    { id: "tree", label: "Plan Tree", icon: ListTree, show: Boolean(analysis.planTree) },
    { id: "indexes", label: "Indexes", icon: Database, show: indexes.length > 0, count: indexes.length },
    { id: "joins", label: "Joins", icon: GitMerge, show: joins.length > 0, count: joins.length },
    { id: "grouping", label: "Grouping & Sort", icon: Layers, show: grouping.length > 0, count: grouping.length },
    { id: "filters", label: "Filters", icon: FilterIcon, show: filters.length > 0, count: filters.length },
    { id: "metrics", label: "Metrics", icon: Gauge, show: true },
  ].filter((t) => t.show);

  return (
    <div className={styles.panel}>
      {/* Verdict: real execution bottlenecks only (no estimate-skew noise). */}
      <div className={`${styles.banner} ${hasScope ? styles.banner_warn : styles.banner_ok}`}>
        {hasScope ? <AlertTriangle size={16} /> : <CheckCircle2 size={16} />}
        <div className={styles.bannerBody}>
          <div className={styles.bannerTitle}>
            {hasScope
              ? `${concerns.length} bottleneck${concerns.length === 1 ? "" : "s"} found`
              : analysis.wasExecuted
                ? "No obvious bottlenecks — the query runs efficiently"
                : "No obvious bottlenecks in the estimated plan"}
          </div>
          {hasScope && (
            <ul className={styles.bannerList}>
              {concerns.slice(0, 5).map((c, i) => <li key={i}>{c}</li>)}
            </ul>
          )}
          {indexBottleneck && (
            <div className={styles.bannerNote}>
              Note: index recommendations are workload-weighted across all queries — see Performance → Workload,
              not this single query.
            </div>
          )}
          {!analysis.wasExecuted && (
            <div className={styles.bannerNote}>
              Estimated plan only (query not executed) — run on read-only SQL for actual rows &amp; timings.
            </div>
          )}
        </div>
        {analysis.wasExecuted && totalTime != null && (
          <div className={styles.bannerStat} title="Actual total execution time">
            <Clock size={13} />
            <span className={styles.bannerStatValue}>{fmtMs(totalTime)}</span>
            <span className={styles.bannerStatLabel}>total</span>
          </div>
        )}
      </div>

      <div className={styles.tabBar} role="tablist">
        {TABS.map((t) => {
          const Icon = t.icon;
          return (
            <button
              key={t.id}
              role="tab"
              aria-selected={tab === t.id}
              className={`${styles.tab} ${tab === t.id ? styles.tabActive : ""}`}
              onClick={() => setTab(t.id)}
            >
              <Icon size={14} />
              <span>{t.label}</span>
              {typeof t.count === "number" && <span className={styles.tabCount}>{t.count}</span>}
            </button>
          );
        })}
      </div>

      <div className={styles.tabBody}>
        {tab === "summary" &&
          (analysis.aiSummary ? <Narrative text={analysis.aiSummary} /> : <div className={styles.muted}>No narrative available for this plan.</div>)}

        {tab === "tree" && <PlanTree planTree={analysis.planTree} aliasMap={aliasMap} />}

        {tab === "indexes" && (
          <CategoryList items={indexes} aliasMap={aliasMap} empty="No index/scan nodes in this plan." hint="How each table is accessed and whether the chosen index is effective." />
        )}
        {tab === "joins" && (
          <CategoryList items={joins} aliasMap={aliasMap} empty="No joins in this plan." hint="Which tables are joined, on which keys, and how efficient each join is." />
        )}
        {tab === "grouping" && (
          <CategoryList items={grouping} aliasMap={aliasMap} empty="No grouping, aggregation, or sort steps." hint="Aggregation/sort steps, what they operate on, and whether they spill to a temp table or filesort." />
        )}
        {tab === "filters" && (
          <CategoryList items={filters} aliasMap={aliasMap} empty="No filter predicates applied in the plan." hint="Predicates applied after access, and how much of the read data they discard." />
        )}

        {tab === "metrics" && (
          <div className={styles.metricsGrid}>
            <Metric label="Plan type" value={analysis.wasExecuted ? "Actual (EXPLAIN ANALYZE)" : "Estimated (EXPLAIN)"} />
            <Metric label="Execution time" value={analysis.executionTimeMs != null ? fmtMs(analysis.executionTimeMs) : "—"} />
            <Metric label="Planning time" value={analysis.planningTimeMs != null ? fmtMs(analysis.planningTimeMs) : "—"} />
            <Metric label="Estimated rows" value={analysis.estimatedRows != null ? fmtNum(Math.round(analysis.estimatedRows)) : "—"} />
            <Metric label="Estimated cost" value={analysis.estimatedCost != null ? Number(analysis.estimatedCost).toLocaleString() : "—"} />
            <Metric label="Plan nodes" value={analysis.nodeCount != null ? fmtNum(analysis.nodeCount) : "—"} />
          </div>
        )}
      </div>
    </div>
  );
}

function CategoryList({ items, empty, hint, aliasMap }) {
  if (!items || items.length === 0) return <div className={styles.muted}>{empty}</div>;
  return (
    <div className={styles.category}>
      <div className={styles.categoryHint}>{hint}</div>
      {items.map((item, i) => <NodeCard key={i} item={item} aliasMap={aliasMap} />)}
    </div>
  );
}

function Metric({ label, value }) {
  return (
    <div className={styles.metricBox}>
      <div className={styles.metricValue}>{value}</div>
      <div className={styles.metricLabel}>{label}</div>
    </div>
  );
}
