import { useState, useEffect, useRef, useCallback } from "react";
import { Database, ArrowUp, SquarePen, Plus, Pencil, Trash2, MessageSquareText, ChevronRight, ChevronDown } from "lucide-react";
import ManageConnectionsModal from "@/components/ManageConnectionsModal";
import BrainInitModal from "@/components/BrainInitModal";
import {
  agentRunAPI,
  chatAPI,
  chatHistoryAPI,
  queryAPI,
} from "@/lib/api/client";
import { AGENTS_ENABLED } from "@/lib/features";
import { useConnectionManager } from "@/lib/hooks/useConnectionManager";
import { useNavStore } from "@/lib/stores/useNavStore";
import { useAuth } from "@/hooks/useAuth";
import FeedbackButtons from "@/components/FeedbackButtons";
import CreateAgentModal from "@/components/Brain/CreateAgentModal";
import { saveAgent } from "@/components/Brain/brainAgentStore";
import styles from "./AgentView.module.css";

const FALLBACK_PROMPTS = [
  {
    text: "For the last 30 days, show daily revenue and order trends with no more than 31 rows.",
    sub: "BI Trend",
  },
  {
    text: "Show top 20 customers by revenue in the last 30 days, including order count and average order value.",
    sub: "BI Join",
  },
  {
    text: "Compare this week vs last week for key KPIs (orders, revenue, active users) using recent data only.",
    sub: "BI Comparison",
  },
  {
    text: "Using recent 14-day data, find top 10 products by sales and margin with category breakdown.",
    sub: "BI Product",
  },
  {
    text: "Build a conversion funnel for the last 14 days (visit → signup → purchase) with drop-off percentages.",
    sub: "BI Funnel",
  },
  {
    text: "Using only the most recent month, detect unusual day-over-day KPI changes and list the top 10 anomalies.",
    sub: "BI Anomaly",
  },
];

const AUTO_THREAD_TITLE_PLACEHOLDERS = new Set([
  "active chat",
  "new chat",
  "untitled chat",
]);

function normalizeName(name) {
  return (name || "").toLowerCase();
}

function scoreBusinessTable(table) {
  const n = normalizeName(table?.name);
  const businessKeywords = [
    "order",
    "sale",
    "invoice",
    "payment",
    "transaction",
    "booking",
    "customer",
    "client",
    "user",
    "account",
    "product",
    "item",
    "subscription",
    "shipment",
    "session",
    "event",
  ];
  const dimensionKeywords = [
    "dim_",
    "fact_",
    "fct_",
    "orders",
    "customers",
    "products",
  ];

  let score = 0;
  if (table?.type === "table") score += 4;
  if (table?.rowCount && table.rowCount > 0)
    score += Math.min(3, Math.log10(table.rowCount + 1));
  if (businessKeywords.some((k) => n.includes(k))) score += 6;
  if (dimensionKeywords.some((k) => n.includes(k))) score += 2;
  if (
    n.includes("tmp") ||
    n.includes("backup") ||
    n.includes("audit") ||
    n.includes("log")
  )
    score -= 3;
  return score;
}

function pickKeyBusinessTables(objects = []) {
  return objects
    .filter((o) => o?.type === "table" && o?.name)
    .sort((a, b) => scoreBusinessTable(b) - scoreBusinessTable(a))
    .slice(0, 6)
    .map((t) => t.name);
}

function buildBiPromptsFromTables(tables = []) {
  if (!tables.length) return FALLBACK_PROMPTS;

  const fact = tables[0];
  const dimA = tables[1] || tables[0];
  const dimB = tables[2] || tables[1] || tables[0];
  const dimC = tables[3] || tables[2] || tables[1] || tables[0];

  return [
    {
      text: `For the last 30 days, show daily KPI trends from ${fact} (revenue, transaction count, avg value) with max 31 rows.`,
      sub: "BI Trend",
    },
    {
      text: `Using ${fact} JOIN ${dimA}, show top 20 customers/accounts by revenue in the last 30 days with avg order value.`,
      sub: "BI Join",
    },
    {
      text: `Using ${fact}, ${dimA}, and ${dimB}, compare this week vs last week KPIs by segment and show biggest movers.`,
      sub: "BI Multi-Join",
    },
    {
      text: `Using ${fact} JOIN ${dimB}, find top 10 products/services in the last 14 days with contribution % and growth vs prior 14 days.`,
      sub: "BI Product",
    },
    {
      text: `Build a recent 14-day funnel using ${fact}, ${dimA}, ${dimC} (visit → signup → purchase) with conversion and drop-off by day.`,
      sub: "BI Funnel",
    },
    {
      text: `Using only the most recent 30 days from ${fact}, detect top 10 day-over-day KPI anomalies and possible drivers from ${dimA}/${dimB}.`,
      sub: "BI Anomaly",
    },
  ];
}

// Strip AGENT_CONTEXT prefix that was mistakenly prepended to user messages in older versions.
// The backend already has its own system prompt; we should only send the user's raw text.
function cleanUserMessage(content) {
  if (!content) return content;
  // Old messages look like: "You are DBA Agent...\n\nUSER: <actual message>"
  const userPrefix = "\n\nUSER: ";
  const idx = content.indexOf(userPrefix);
  if (idx !== -1) return content.slice(idx + userPrefix.length);
  return content;
}

function parseMessageMetadata(rawMetadata) {
  if (!rawMetadata) return {};
  if (typeof rawMetadata === "object") return rawMetadata;
  try {
    return JSON.parse(rawMetadata);
  } catch {
    return {};
  }
}

function getSessionChatStorageKey(connectionId, username) {
  if (!connectionId || !username) return null;
  return `agent-view-chat-${String(username).toLowerCase()}-${connectionId}`;
}

function readSessionChatId(connectionId, username) {
  if (typeof window === "undefined") return null;
  const key = getSessionChatStorageKey(connectionId, username);
  const legacyKey = connectionId ? `agent-view-chat-${connectionId}` : null;
  if (!key) return null;
  const value = window.sessionStorage.getItem(key);
  if (!value && legacyKey) {
    window.sessionStorage.removeItem(legacyKey);
  }
  return value;
}

function writeSessionChatId(connectionId, username, chatId) {
  if (typeof window === "undefined") return;
  const key = getSessionChatStorageKey(connectionId, username);
  if (!key) return;
  if (chatId) {
    window.sessionStorage.setItem(key, chatId);
  } else {
    window.sessionStorage.removeItem(key);
  }
}

function hydrateChatMessages(chat) {
  return Array.isArray(chat?.messages)
    ? chat.messages.map((m) => ({
        ...applyAssistantMetadata(
          {
            id: m.id || `h-${Date.now()}-${Math.random()}`,
            role: m.role?.toLowerCase() === "user" ? "user" : "assistant",
            content:
              m.role?.toLowerCase() === "user"
                ? cleanUserMessage(m.content || "")
                : m.content || "",
            sql: m.sql || null,
            timestamp: m.createdAt || new Date().toISOString(),
          },
          m.role?.toLowerCase() === "assistant"
            ? parseMessageMetadata(m.metadata)
            : null,
        ),
      }))
    : [];
}

function formatThreadTimestamp(value) {
  if (!value) return "";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "";
  const now = new Date();
  const diffMs = now.getTime() - date.getTime();
  const diffHours = diffMs / (1000 * 60 * 60);
  if (diffHours < 24) {
    return date.toLocaleTimeString([], { hour: "numeric", minute: "2-digit" });
  }
  return date.toLocaleDateString([], { month: "short", day: "numeric" });
}

function shouldAutoThreadTitle(title) {
  if (!title) return true;
  return AUTO_THREAD_TITLE_PLACEHOLDERS.has(title.trim().toLowerCase());
}

function summarizeThreadTitle(message) {
  if (!message) return "New chat";
  const normalized = message
    .replace(/\s+/g, " ")
    .replace(/^[\-*>\s]+/, "")
    .trim();
  if (!normalized) return "New chat";

  const trimmed = normalized.replace(/[\s?.!,;:]+$/, "");
  const candidate = trimmed || normalized;
  const maxLength = 44;
  if (candidate.length <= maxLength) return candidate;

  let boundary = candidate.lastIndexOf(" ", maxLength);
  if (boundary < 18) boundary = maxLength;
  return `${candidate.slice(0, boundary).trim()}...`;
}

function applyAssistantMetadata(message, metadata = {}) {
  if (!metadata || typeof metadata !== "object") return message;
  const artifacts =
    metadata.artifacts && typeof metadata.artifacts === "object"
      ? metadata.artifacts
      : {};
  const run =
    metadata.run && typeof metadata.run === "object" ? metadata.run : null;
  const ui = metadata.ui && typeof metadata.ui === "object" ? metadata.ui : null;
  return {
    ...message,
    mode: metadata.mode || message.mode || null,
    answer: metadata.answer || message.answer || null,
    plan: metadata.plan || message.plan || null,
    executedQueries: Array.isArray(metadata.executedQueries)
      ? metadata.executedQueries
      : Array.isArray(artifacts.sql)
        ? artifacts.sql
      : message.executedQueries || [],
    toolsUsed: Array.isArray(metadata.toolsUsed)
      ? metadata.toolsUsed
      : message.toolsUsed || [],
    confidence:
      typeof metadata.confidence === "number"
        ? metadata.confidence
        : message.confidence ?? null,
    agentRunId: metadata.agentRunId || message.agentRunId || null,
    sql: metadata.sql || message.sql || null,
    resultSets: Array.isArray(metadata.resultSets)
      ? metadata.resultSets
      : Array.isArray(artifacts.resultSets)
        ? artifacts.resultSets
      : message.resultSets || [],
    evidenceSummaries: Array.isArray(artifacts.evidenceSummaries)
      ? artifacts.evidenceSummaries
      : message.evidenceSummaries || [],
    run: run || message.run || null,
    ui: ui || message.ui || null,
    activityStartedAt:
      metadata.activityStartedAt ||
      metadata.startedAt ||
      message.activityStartedAt ||
      null,
    completedAt: metadata.completedAt || message.completedAt || null,
  };
}

function applyAssistantResult(message, result = {}) {
  if (!result || typeof result !== "object") return message;
  const withMetadata = applyAssistantMetadata(message, result);
  const resolvedResultSets = Array.isArray(result.resultSets)
    ? result.resultSets
    : withMetadata.resultSets || [];
  const firstResultSetData =
    resolvedResultSets.find((item) => item?.data)?.data || null;
  return {
    ...withMetadata,
    chatId: result.chatId || message.chatId || null,
    answer: result.answer || withMetadata.answer || withMetadata.message || null,
    queryResult:
      result.data !== undefined
        ? result.data
        : firstResultSetData || message.queryResult || null,
    success:
      typeof result.success === "boolean"
        ? result.success
        : message.success ?? null,
    serverMessage: result.message || message.serverMessage || null,
    resultSets: resolvedResultSets,
  };
}

function shouldShowTrace(message) {
  if (message?.ui?.adminTraceAvailable === false) {
    return false;
  }
  return Boolean(
    message?.mode === "agentic" ||
      message?.mode === "unified" ||
      message?.run?.runId ||
      message?.agentRunId ||
      message?.plan ||
      message?.executedQueries?.length ||
      message?.toolsUsed?.length,
  );
}

function formatActivityDuration(durationMs) {
  if (!Number.isFinite(durationMs) || durationMs < 1000) return "under 1s";

  const totalSeconds = Math.round(durationMs / 1000);
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;

  if (hours > 0) {
    return `${hours}h ${minutes}m`;
  }
  if (minutes > 0) {
    return `${minutes}m ${seconds}s`;
  }
  return `${seconds}s`;
}

function resolveActivityDurationMs(message, nowMs) {
  const startedAtValue =
    message?.activityStartedAt || message?.startedAt || message?.timestamp;
  const startedAt = startedAtValue ? Date.parse(startedAtValue) : Number.NaN;
  if (Number.isNaN(startedAt)) return null;

  if (message?.streaming) {
    return Math.max(0, nowMs - startedAt);
  }

  const completedAtValue = message?.completedAt || message?.finishedAt;
  if (!completedAtValue) return null;

  const completedAt = Date.parse(completedAtValue);
  if (Number.isNaN(completedAt)) return null;

  return Math.max(0, completedAt - startedAt);
}

function buildActivityStatusLabel(message, nowMs) {
  const durationMs = resolveActivityDurationMs(message, nowMs);
  if (message?.streaming) {
    return durationMs != null && durationMs >= 1000
      ? `Working for ${formatActivityDuration(durationMs)}`
      : "Working…";
  }
  return durationMs != null
    ? `Worked for ${formatActivityDuration(durationMs)}`
    : "Worked";
}

function formatConfidence(confidence) {
  if (typeof confidence !== "number") return null;
  return `${Math.round(confidence * 100)}% confidence`;
}

function humanizeToolName(toolName) {
  if (!toolName) return "Tool step";
  return toolName
    .replace(/_tool$/i, "")
    .split("_")
    .filter(Boolean)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(" ");
}

function parsePlanSummary(plan) {
  if (!plan) return { goal: null, steps: [] };
  const lines = String(plan)
    .split("\n")
    .map((line) => line.trim())
    .filter(Boolean);

  const goalLine = lines.find((line) => line.startsWith("Goal:"));
  const steps = lines
    .filter((line) => line.startsWith("- "))
    .map((line) => line.replace(/^- /, "").trim())
    .filter(Boolean);

  return {
    goal: goalLine ? goalLine.replace(/^Goal:\s*/, "").trim() : null,
    steps,
  };
}

function normalizeProgressEvent(progress, index) {
  if (!progress) return null;
  if (typeof progress === "string") {
    return {
      key: `progress-${index}`,
      label: progress,
      detail: null,
      status: "active",
    };
  }

  const label =
    progress.label || progress.step || progress.title || progress.message;
  if (!label) return null;

  return {
    key: progress.key || `progress-${index}`,
    label,
    detail: progress.detail || progress.reason || null,
    status:
      progress.status === "completed" ||
      progress.status === "pending" ||
      progress.status === "active"
        ? progress.status
        : "active",
  };
}

function buildLiveProgressSteps(progressEvents = []) {
  const orderedKeys = [];
  const latestByKey = new Map();

  progressEvents.forEach((progress, index) => {
    const normalized = normalizeProgressEvent(progress, index);
    if (!normalized) return;
    if (!latestByKey.has(normalized.key)) {
      orderedKeys.push(normalized.key);
    }
    latestByKey.set(normalized.key, normalized);
  });

  return orderedKeys
    .map((key) => latestByKey.get(key))
    .filter(Boolean);
}

function buildPlannedActivitySteps(message, nowMs) {
  const { goal, steps: plannedSteps } = parsePlanSummary(message?.plan);
  const taskSteps =
    plannedSteps.length > 0
      ? plannedSteps
      : Array.isArray(message?.toolsUsed)
        ? message.toolsUsed.map(humanizeToolName)
        : [];

  const baseSteps = [
    {
      key: "planning",
      label: "Planning workflow",
      detail: goal || "Preparing the agent workflow for this request",
    },
    ...taskSteps.map((step, index) => ({
      key: `task-${index}`,
      label: step,
      detail:
        Array.isArray(message?.toolsUsed) && message.toolsUsed[index]
          ? humanizeToolName(message.toolsUsed[index])
          : null,
    })),
  ];

  if (message?.executedQueries?.length) {
    baseSteps.push({
      key: "analysis",
      label: "Analyzing query results",
      detail: `${message.executedQueries.length} SQL statement${message.executedQueries.length !== 1 ? "s" : ""} executed`,
    });
  }

  baseSteps.push({
    key: "response",
    label: "Composing response",
    detail: message?.streaming ? "Packaging the final answer" : "Final answer ready",
  });

  const steps = baseSteps.map((step) => ({ ...step, status: "completed" }));

  if (message?.streaming) {
    const startedAt = Date.parse(message?.timestamp || "");
    const elapsedMs = Number.isNaN(startedAt) ? 0 : Math.max(0, nowMs - startedAt);
    const responseIndex = steps.length - 1;

    if (message?.content) {
      steps[responseIndex] = {
        ...steps[responseIndex],
        status: "active",
        detail: "Streaming the final answer",
      };
    } else {
      const taskWindowMs = 1800;
      const activeTaskIndex = Math.min(
        Math.max(steps.length - 2, 0),
        Math.floor(elapsedMs / taskWindowMs),
      );

      steps.forEach((step, index) => {
        if (index < activeTaskIndex) {
          step.status = "completed";
        } else if (index === activeTaskIndex) {
          step.status = "active";
        } else {
          step.status = "pending";
        }
      });
    }
  }

  return { goal, steps };
}

function buildTraceActivitySteps(traceSteps = []) {
  return traceSteps.map((step, index) => ({
    key: step.id || `trace-step-${index}`,
    label: step.title || humanizeToolName(step.toolName),
    detail: step.observation?.summary || step.toolName || null,
    status: "completed",
  }));
}

function buildAgentActivity(message, nowMs) {
  const liveProgressSteps = Array.isArray(message?.progressEvents)
    ? buildLiveProgressSteps(message.progressEvents)
    : [];
  const traceSteps = Array.isArray(message?.trace?.steps)
    ? message.trace.steps
    : [];
  const { goal: planGoal } = parsePlanSummary(message?.plan);
  const hasAgentMarkers = Boolean(
    message?.mode === "agentic" ||
      message?.agentRunId ||
      message?.plan ||
      message?.toolsUsed?.length ||
      traceSteps.length,
  );

  let steps = [];
  let goal = planGoal;

  if (liveProgressSteps.length > 0) {
    steps = liveProgressSteps;
  } else if (traceSteps.length > 0) {
    steps = [
      {
        key: "planning",
        label: "Planning workflow",
        detail: planGoal || "Agent workflow planned and executed",
        status: "completed",
      },
      ...buildTraceActivitySteps(traceSteps),
      {
        key: "response",
        label: "Composed response",
        detail: "Final answer ready",
        status: "completed",
      },
    ];
  } else if (hasAgentMarkers) {
    const planned = buildPlannedActivitySteps(message, nowMs);
    goal = planned.goal;
    steps = planned.steps;
  } else if (liveProgressSteps.length > 0) {
    steps = liveProgressSteps;
  }

  if (!steps.length) return null;

  const currentStep =
    steps.find((step) => step.status === "active") || steps[steps.length - 1];
  const sqlCount = Array.isArray(message?.executedQueries)
    ? message.executedQueries.length
    : 0;
  const toolCount = Array.isArray(message?.toolsUsed) ? message.toolsUsed.length : 0;

  return {
    goal: goal || "Working through the request",
    currentLabel:
      message?.progressText ||
      currentStep?.label ||
      (message?.streaming ? "Thinking through the request" : "Completed"),
    status: message?.streaming ? "running" : "completed",
    steps,
    metaItems: [
      toolCount > 0
        ? `${toolCount} step${toolCount !== 1 ? "s" : ""}`
        : null,
      sqlCount > 0 ? `${sqlCount} SQL` : null,
      formatConfidence(message?.confidence),
    ].filter(Boolean),
  };
}

function AgentTraceContent({ message }) {
  return (
    <div className={styles.activityTrace}>
      {message.plan && (
        <div className={styles.traceSection}>
          <div className={styles.traceSectionLabel}>Plan</div>
          <pre className={styles.tracePre}>{message.plan}</pre>
        </div>
      )}
      {message.toolsUsed?.length ? (
        <div className={styles.traceSection}>
          <div className={styles.traceSectionLabel}>Tools</div>
          <div className={styles.traceChips}>
            {message.toolsUsed.map((toolName) => (
              <span
                key={`${message.id}-${toolName}`}
                className={styles.traceChip}
              >
                {toolName}
              </span>
            ))}
          </div>
        </div>
      ) : null}
      {message.executedQueries?.length ? (
        <div className={styles.traceSection}>
          <div className={styles.traceSectionLabel}>Executed SQL</div>
          {message.executedQueries.map((query, queryIndex) => (
            <pre
              key={`${message.id}-sql-${queryIndex}`}
              className={styles.traceSql}
            >
              {query}
            </pre>
          ))}
        </div>
      ) : null}
      {message.traceLoading && (
        <div className={styles.traceLoading}>Loading full trace…</div>
      )}
      {message.traceError && (
        <div className={styles.traceError}>{message.traceError}</div>
      )}
      {message.trace?.steps?.length ? (
        <div className={styles.traceSection}>
          <div className={styles.traceSectionLabel}>Evidence</div>
          {message.trace?.tasks?.length ? (
            <div className={styles.traceTasksList}>
              {message.trace.tasks.map((task, taskIndex) => (
                <div
                  key={task.taskId || `trace-task-${taskIndex}`}
                  className={styles.traceTaskItem}
                >
                  <div className={styles.traceTaskTitle}>
                    {task.title || task.taskId || `Task ${taskIndex + 1}`}
                  </div>
                  <div className={styles.traceTaskMeta}>
                    {task.kind || "TASK"}
                    {Array.isArray(task.dependsOn) && task.dependsOn.length
                      ? ` · depends on ${task.dependsOn.join(", ")}`
                      : ""}
                  </div>
                </div>
              ))}
            </div>
          ) : null}
          <div className={styles.traceSteps}>
            {message.trace.steps.map((step) => (
              <div key={step.id} className={styles.traceStep}>
                <div className={styles.traceStepHeader}>
                  <span className={styles.traceStepTitle}>
                    {step.stepIndex + 1}. {step.title}
                  </span>
                  <span className={styles.traceStepTool}>{step.toolName}</span>
                </div>
                {step.dependsOn?.length ? (
                  <p className={styles.traceObservation}>
                    Depends on {step.dependsOn.join(", ")}
                  </p>
                ) : null}
                {step.observation?.summary && (
                  <p className={styles.traceObservation}>
                    {step.observation.summary}
                  </p>
                )}
                {step.executedQueries?.length
                  ? step.executedQueries.map((query, queryIndex) => (
                      <pre
                        key={`${step.id || step.stepKey || "step"}-query-${queryIndex}`}
                        className={styles.traceSql}
                      >
                        {query}
                      </pre>
                    ))
                  : step.executedSql && (
                      <pre className={styles.traceSql}>{step.executedSql}</pre>
                    )}
                {step.artifacts?.length
                  ? step.artifacts.map((artifact, artifactIndex) => (
                      <div
                        key={`${step.id || step.stepKey || "step"}-artifact-${artifactIndex}`}
                        className={styles.traceArtifact}
                      >
                        {artifact?.payload?.title ? (
                          <div className={styles.traceTaskTitle}>
                            {artifact.payload.title}
                          </div>
                        ) : null}
                        {artifact?.payload?.summary ? (
                          <p className={styles.traceObservation}>
                            {artifact.payload.summary}
                          </p>
                        ) : null}
                        {artifact?.payload?.resultPreview ? (
                          <pre className={styles.traceData}>
                            {JSON.stringify(
                              artifact.payload.resultPreview,
                              null,
                              2,
                            )}
                          </pre>
                        ) : null}
                      </div>
                    ))
                  : null}
                {step.observation?.data && (
                  <pre className={styles.traceData}>
                    {JSON.stringify(step.observation.data, null, 2)}
                  </pre>
                )}
              </div>
            ))}
          </div>
        </div>
      ) : null}
    </div>
  );
}

function AgentActivityCard({ message, nowMs, isAdmin, onTraceOpen }) {
  const [isExpanded, setIsExpanded] = useState(false);
  const activity = buildAgentActivity(message, nowMs);
  if (!activity) return null;
  const compactStatusLabel = buildActivityStatusLabel(message, nowMs);
  const canShowTrace = !message?.streaming && shouldShowTrace(message);

  const handleToggleExpanded = () => {
    if (!isAdmin) return;
    setIsExpanded((current) => {
      const next = !current;
      if (next && canShowTrace) {
        onTraceOpen?.(message.id);
      }
      return next;
    });
  };

  return (
    <div className={styles.activityCardShell}>
      <button
        type="button"
        className={`${styles.activitySummaryButton} ${
          isAdmin
            ? styles.activitySummaryButtonInteractive
            : styles.activitySummaryButtonStatic
        }`}
        onClick={isAdmin ? handleToggleExpanded : undefined}
        aria-expanded={isAdmin ? isExpanded : undefined}
      >
        <span
          className={`${styles.activitySummaryDot} ${
            activity.status === "running"
              ? styles.activitySummaryDotRunning
              : styles.activitySummaryDotCompleted
          }`}
        />
        <span className={styles.activitySummaryLabel}>{compactStatusLabel}</span>
        {isAdmin ? (
          isExpanded ? (
            <ChevronDown size={16} className={styles.activitySummaryChevron} />
          ) : (
            <ChevronRight size={16} className={styles.activitySummaryChevron} />
          )
        ) : null}
      </button>

      {isAdmin && isExpanded && (
        <div className={styles.activityCard}>
          <div className={styles.activityHeader}>
            <div className={styles.activityHeaderText}>
              <span className={styles.activityEyebrow}>Agent activity</span>
              <div className={styles.activityTitle}>{activity.goal}</div>
              <div className={styles.activitySubtitle}>{activity.currentLabel}</div>
            </div>
            <span
              className={`${styles.activityState} ${
                activity.status === "running"
                  ? styles.activityStateRunning
                  : styles.activityStateCompleted
              }`}
            >
              {activity.status === "running" ? "Working" : "Completed"}
            </span>
          </div>
          {activity.metaItems.length > 0 && (
            <div className={styles.activityMetaRow}>
              {activity.metaItems.map((item) => (
                <span key={item} className={styles.activityMetaChip}>
                  {item}
                </span>
              ))}
            </div>
          )}
          <div className={styles.activityTimeline}>
            {activity.steps.map((step) => (
              <div key={step.key} className={styles.activityStep}>
                <span
                  className={`${styles.activityStepDot} ${
                    step.status === "completed"
                      ? styles.activityStepDotCompleted
                      : step.status === "active"
                        ? styles.activityStepDotActive
                        : styles.activityStepDotPending
                  }`}
                />
                <div className={styles.activityStepBody}>
                  <div className={styles.activityStepLabel}>{step.label}</div>
                  {step.detail && (
                    <div className={styles.activityStepDetail}>{step.detail}</div>
                  )}
                </div>
              </div>
            ))}
          </div>
          {canShowTrace ? <AgentTraceContent message={message} /> : null}
        </div>
      )}
    </div>
  );
}

/**
 * Renders progress text with a darkening wave animation.
 * Each character is wrapped in a span with a staggered animation delay
 * so the dark color sweeps left-to-right continuously.
 */
function WaveText({ text, className }) {
  return (
    <p className={className}>
      {text.split("").map((char, i) => (
        <span
          key={i}
          className={styles.waveChar}
          style={{ animationDelay: `${i * 0.07}s` }}
        >
          {char === " " ? "\u00A0" : char}
        </span>
      ))}
    </p>
  );
}

function boldify(text) {
  return text
    .replace(/\*\*(.*?)\*\*/g, "<strong>$1</strong>")
    .replace(/`([^`]+)`/g, "<code>$1</code>");
}

function renderMarkdownLine(line, i) {
  if (!line.trim()) return <br key={i} />;
  if (line.match(/^#{1,3}\s/)) {
    const text = line.replace(/^#{1,3}\s/, "");
    return (
      <p key={i} style={{ fontWeight: 700, marginTop: 8, marginBottom: 4 }}>
        {text}
      </p>
    );
  }
  if (line.startsWith("- ") || line.startsWith("• ")) {
    const text = line.replace(/^[-•]\s/, "");
    return (
      <p key={i} style={{ display: "flex", gap: 6, marginBottom: 3 }}>
        <span>•</span>
        <span dangerouslySetInnerHTML={{ __html: boldify(text) }} />
      </p>
    );
  }
  return <p key={i} dangerouslySetInnerHTML={{ __html: boldify(line) }} />;
}

// Parses content into segments: plain text lines and fenced code blocks
function renderMarkdown(content, styles) {
  const lines = content.split("\n");
  const segments = [];
  let i = 0;
  while (i < lines.length) {
    const line = lines[i];
    if (line.startsWith("```")) {
      const lang = line.slice(3).trim() || "sql";
      const codeLines = [];
      i++;
      while (i < lines.length && !lines[i].startsWith("```")) {
        codeLines.push(lines[i]);
        i++;
      }
      i++; // skip closing ```
      segments.push({ type: "code", lang, code: codeLines.join("\n") });
    } else {
      segments.push({ type: "line", line });
      i++;
    }
  }

  return segments.map((seg, si) => {
    if (seg.type === "code") {
      return (
        <div key={si} className={styles.inlineCodeBlock}>
          <div className={styles.sqlBlockHeader}>
            <span className={styles.sqlBlockLang}>
              {seg.lang.toUpperCase()}
            </span>
            <button
              className={styles.sqlBlockCopy}
              onClick={() => navigator.clipboard.writeText(seg.code)}
            >
              Copy
            </button>
          </div>
          <pre className={styles.sqlBlockCode}>{seg.code}</pre>
        </div>
      );
    }
    return renderMarkdownLine(seg.line, si);
  });
}

function sanitizeAssistantContent(content) {
  if (!content || typeof content !== "string") return content;

  const lines = content.split("\n");
  const cutoffIndex = lines.findIndex((line) =>
    /^(Evidence|Assumption|Supporting evidence|Verification notes):\s*$/i.test(
      line.trim(),
    ),
  );

  if (cutoffIndex === -1) {
    return content;
  }

  return lines
    .slice(0, cutoffIndex)
    .join("\n")
    .replace(/\n{3,}/g, "\n\n")
    .trim();
}

export default function AgentView() {
  const { connectionId, connections, changeConnection } = useConnectionManager();
  const { username, isAdmin } = useAuth();
  const [threads, setThreads] = useState([]);
  const [threadsLoading, setThreadsLoading] = useState(false);
  const [editingChatId, setEditingChatId] = useState(null);
  const [editingChatTitle, setEditingChatTitle] = useState("");
  const [confirmDeleteChatId, setConfirmDeleteChatId] = useState(null);
  const [messages, setMessages] = useState([]);
  const [sending, setSending] = useState(false);
  const [streaming, setStreaming] = useState(false);
  const [agentActivityNow, setAgentActivityNow] = useState(Date.now());
  const [prompt, setPrompt] = useState("");
  const [examplePrompts, setExamplePrompts] = useState(FALLBACK_PROMPTS);
  const [currentChatId, setCurrentChatId] = useState(null);
  const [buildAgentData, setBuildAgentData] = useState(null);
  const [showAddConnection, setShowAddConnection] = useState(false);
  const [brainInitConnectionId, setBrainInitConnectionId] = useState(null);
  const messagesEndRef = useRef(null);
  const textareaRef = useRef(null);
  const streamAbortRef = useRef(null);
  const streamContentRef = useRef("");
  const streamResultRef = useRef(null);

  const getVisibleAssistantContent = useCallback((message) => {
    if (!message || message.role !== "assistant") return message?.content || "";
    if (message.mode === "agentic" || message.mode === "unified") {
      return sanitizeAssistantContent(message.content || "");
    }
    return message.content || "";
  }, []);

  const loadThreads = useCallback(async () => {
    if (!connectionId) {
      setThreads([]);
      return [];
    }
    setThreadsLoading(true);
    try {
      const nextThreads = await chatHistoryAPI.getAllChats(null, connectionId);
      const normalizedThreads = Array.isArray(nextThreads) ? nextThreads : [];
      setThreads(normalizedThreads);
      return normalizedThreads;
    } finally {
      setThreadsLoading(false);
    }
  }, [connectionId]);

  const loadChat = useCallback(async (chatId) => {
    if (!chatId) return;
    const chat = await chatHistoryAPI.getChatWithHistory(chatId);
    setMessages(hydrateChatMessages(chat));
    setCurrentChatId(chat.id || null);
    writeSessionChatId(connectionId, username, chat.id || null);
    return chat;
  }, [connectionId, username]);

  const resetDraftChat = useCallback(() => {
    setMessages([]);
    setCurrentChatId(null);
    writeSessionChatId(connectionId, username, null);
    return null;
  }, [connectionId, username]);

  const createPersistedChat = useCallback(async () => {
    if (!connectionId) {
      return resetDraftChat();
    }
    const chat = await chatHistoryAPI.createChat(connectionId, null, "New chat");
    setCurrentChatId(chat.id || null);
    writeSessionChatId(connectionId, username, chat.id || null);
    return chat;
  }, [connectionId, resetDraftChat, username]);

  const loadSessionChat = useCallback(async () => {
    if (!connectionId) {
      return resetDraftChat();
    }

    const sessionChatId = readSessionChatId(connectionId, username);
    if (sessionChatId) {
      try {
        return await loadChat(sessionChatId);
      } catch {
        writeSessionChatId(connectionId, username, null);
      }
    }

    return resetDraftChat();
  }, [connectionId, loadChat, resetDraftChat, username]);

  const ensureSessionChatId = useCallback(async () => {
    if (currentChatId) return currentChatId;
    const chat = await createPersistedChat();
    return chat?.id || null;
  }, [createPersistedChat, currentChatId]);

  // Auto-scroll to bottom on new messages
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages, sending]);

  useEffect(() => {
    const hasStreamingMessage = messages.some((message) => message.streaming);
    if (!hasStreamingMessage) return undefined;

    const intervalId = window.setInterval(() => {
      setAgentActivityNow(Date.now());
    }, 700);

    return () => window.clearInterval(intervalId);
  }, [messages]);

  // Load this tab's isolated chat on connection change.
  useEffect(() => {
    if (!connectionId) {
      setMessages([]);
      setCurrentChatId(null);
      setThreads([]);
      setThreadsLoading(false);
      setEditingChatId(null);
      setEditingChatTitle("");
      setConfirmDeleteChatId(null);
      return;
    }
    let cancelled = false;
    const load = async () => {
      setThreadsLoading(true);
      try {
        const [chat, nextThreads] = await Promise.all([
          loadSessionChat(),
          chatHistoryAPI.getAllChats(null, connectionId),
        ]);
        if (cancelled) return;
        setMessages(hydrateChatMessages(chat));
        setCurrentChatId(chat?.id || null);
        setThreads(Array.isArray(nextThreads) ? nextThreads : []);
      } catch {
        // Failed to load the session chat — start fresh
        setMessages([]);
        setCurrentChatId(null);
        setThreads([]);
      } finally {
        if (!cancelled) {
          setThreadsLoading(false);
        }
      }
    };
    load();
    return () => {
      cancelled = true;
    };
  }, [connectionId, loadSessionChat]);

  // Build BI-oriented example prompts from the connected schema objects
  useEffect(() => {
    if (!connectionId) {
      setExamplePrompts(FALLBACK_PROMPTS);
      return;
    }

    let cancelled = false;

    queryAPI
      .getDatabaseObjects(connectionId)
      .then((response) => {
        if (cancelled) return;
        const objects = Array.isArray(response?.objects)
          ? response.objects
          : [];
        const keyTables = pickKeyBusinessTables(objects);
        setExamplePrompts(buildBiPromptsFromTables(keyTables));
      })
      .catch(() => {
        if (!cancelled) setExamplePrompts(FALLBACK_PROMPTS);
      });

    return () => {
      cancelled = true;
    };
  }, [connectionId]);

  const handleNewChat = useCallback(async () => {
    if (!connectionId) return;
    resetDraftChat();
    setEditingChatId(null);
    setEditingChatTitle("");
    setConfirmDeleteChatId(null);
    await loadThreads();
  }, [connectionId, loadThreads, resetDraftChat]);

  const handleSelectThread = useCallback(async (chatId) => {
    if (!chatId || chatId === currentChatId) return;
    setConfirmDeleteChatId(null);
    await loadChat(chatId);
    setEditingChatId(null);
    setEditingChatTitle("");
  }, [currentChatId, loadChat]);

  const handleBeginRename = useCallback((thread) => {
    setConfirmDeleteChatId(null);
    setEditingChatId(thread.id);
    setEditingChatTitle(thread.title || "");
  }, []);

  const handleSaveRename = useCallback(async () => {
    if (!editingChatId) return;
    const nextTitle = editingChatTitle.trim();
    if (!nextTitle) {
      setEditingChatId(null);
      setEditingChatTitle("");
      return;
    }
    await chatHistoryAPI.updateChatTitle(editingChatId, nextTitle);
    setEditingChatId(null);
    setEditingChatTitle("");
    setConfirmDeleteChatId(null);
    await loadThreads();
  }, [editingChatId, editingChatTitle, loadThreads]);

  const handleDeleteThread = useCallback(async (chatId) => {
    if (!chatId) return;
    if (confirmDeleteChatId !== chatId) {
      setEditingChatId(null);
      setEditingChatTitle("");
      setConfirmDeleteChatId(chatId);
      return;
    }
    setConfirmDeleteChatId(null);
    await chatHistoryAPI.deleteChat(chatId);
    if (chatId === currentChatId) {
      resetDraftChat();
    }
    await loadThreads();
  }, [confirmDeleteChatId, currentChatId, loadThreads, resetDraftChat]);

  const handleBuildAgent = useCallback(({ userMessage, sql }) => {
    const taskParts = [userMessage];
    if (sql) taskParts.push(`\nSQL:\n${sql}`);
    const name = (userMessage || "").slice(0, 50).replace(/[^a-zA-Z0-9 ]/g, "").trim() || "Chat Agent";
    setBuildAgentData({
      name,
      task: taskParts.join(""),
      connectionId,
    });
  }, [connectionId]);

  const handleTraceOpen = useCallback(async (messageId) => {
    const targetMessage = messages.find((message) => message.id === messageId);
    if (
      !targetMessage?.agentRunId ||
      targetMessage.trace ||
      targetMessage.traceLoading
    ) {
      return;
    }

    setMessages((prev) =>
      prev.map((message) =>
        message.id === messageId
          ? { ...message, traceLoading: true, traceError: null }
          : message,
      ),
    );

    try {
      const trace = await agentRunAPI.getRunTrace(targetMessage.agentRunId);
      setMessages((prev) =>
        prev.map((message) =>
          message.id === messageId
            ? { ...message, traceLoading: false, trace }
            : message,
        ),
      );
    } catch (error) {
      setMessages((prev) =>
        prev.map((message) =>
          message.id === messageId
            ? {
                ...message,
                traceLoading: false,
                traceError: error?.message || "Failed to load trace",
              }
            : message,
        ),
      );
    }
  }, [messages]);

  const handleSaveAgent = useCallback((agent) => {
    saveAgent(agent);
    setBuildAgentData(null);
    useNavStore.getState().setActiveSection(
      AGENTS_ENABLED ? "brain" : "schema",
    );
  }, []);

  const autoResize = useCallback(() => {
    const ta = textareaRef.current;
    if (!ta) return;
    ta.style.height = "auto";
    ta.style.height = `${Math.min(ta.scrollHeight, 200)}px`;
  }, []);

  const handleSend = useCallback(async () => {
    if (!prompt.trim() || sending || !connectionId) return;

    const activeChatId = await ensureSessionChatId();

    const userMsg = {
      id: `user-${Date.now()}`,
      role: "user",
      content: prompt.trim(),
      timestamp: new Date().toISOString(),
    };
    setMessages((prev) => [...prev, userMsg]);
    const savedPrompt = prompt.trim();
    const savedChatId = activeChatId;
    const optimisticTitle = summarizeThreadTitle(savedPrompt);
    setThreads((prev) => {
      let foundThread = false;
      const nextThreads = prev.map((thread) => {
        if (thread.id !== savedChatId) return thread;
        foundThread = true;
        if (!shouldAutoThreadTitle(thread.title)) {
          return thread;
        }
        return { ...thread, title: optimisticTitle };
      });

      if (!foundThread && savedChatId) {
        return [
          {
            id: savedChatId,
            title: optimisticTitle,
            createdAt: new Date().toISOString(),
            updatedAt: new Date().toISOString(),
            lastMessageAt: new Date().toISOString(),
          },
          ...nextThreads,
        ];
      }

      return nextThreads;
    });
    setPrompt("");
    setSending(true);
    if (textareaRef.current) textareaRef.current.style.height = "auto";

    const placeholderId = `asst-${Date.now()}`;
    setMessages((prev) => [
      ...prev,
      {
        id: placeholderId,
        role: "assistant",
        content: "",
        streaming: true,
        progressText: "Thinking...",
        timestamp: new Date().toISOString(),
        activityStartedAt: new Date().toISOString(),
      },
    ]);
    setStreaming(true);
    streamContentRef.current = "";
    streamResultRef.current = null;

    const abortController = new AbortController();
    streamAbortRef.current = abortController;

    try {
      await chatAPI.streamMessage(
        connectionId,
        savedPrompt,
        {
          onMetadata: (metadata) => {
            if (metadata.chatId && !savedChatId) {
              setCurrentChatId(metadata.chatId);
              writeSessionChatId(connectionId, username, metadata.chatId);
              void loadThreads();
            }
            if (metadata && Object.keys(metadata).length > 0) {
              setMessages((prev) => {
                const updated = [...prev];
                const idx = updated.findIndex((m) => m.id === placeholderId);
                if (idx !== -1) {
                  updated[idx] = applyAssistantMetadata(updated[idx], metadata);
                }
                return updated;
              });
            }
          },
          onResult: (result) => {
            streamResultRef.current = result;
            if (result?.chatId && !savedChatId) {
              setCurrentChatId(result.chatId);
              writeSessionChatId(connectionId, username, result.chatId);
              void loadThreads();
            }
            setMessages((prev) => {
              const updated = [...prev];
              const idx = updated.findIndex((m) => m.id === placeholderId);
              if (idx !== -1) {
                updated[idx] = {
                  ...applyAssistantResult(updated[idx], result),
                  progressText: null,
                };
              }
              return updated;
            });
          },
          onProgress: (progress) => {
            const text =
              progress.label ||
              progress.message ||
              progress.step ||
              "Processing...";
            setMessages((prev) => {
              const updated = [...prev];
              const idx = updated.findIndex((m) => m.id === placeholderId);
              if (idx !== -1 && !updated[idx].content) {
                updated[idx] = {
                  ...updated[idx],
                  progressText: text,
                  progressEvents: [
                    ...(Array.isArray(updated[idx].progressEvents)
                      ? updated[idx].progressEvents
                      : []),
                    progress,
                  ],
                };
              }
              return updated;
            });
          },
          onToken: (token) => {
            streamContentRef.current += token;
            setMessages((prev) => {
              const updated = [...prev];
              const idx = updated.findIndex((m) => m.id === placeholderId);
              if (idx !== -1) {
                updated[idx] = {
                  ...updated[idx],
                  content: updated[idx].content + token,
                  progressText: null,
                };
              }
              return updated;
            });
          },
          onDone: () => {
            setMessages((prev) => {
              const updated = [...prev];
              const idx = updated.findIndex((m) => m.id === placeholderId);
              if (idx !== -1) {
                const fallbackMessage =
                  !streamContentRef.current &&
                  streamResultRef.current?.message
                    ? streamResultRef.current.message
                    : null;
                updated[idx] = {
                  ...updated[idx],
                  streaming: false,
                  progressText: null,
                  completedAt: new Date().toISOString(),
                  ...(fallbackMessage
                    ? { content: fallbackMessage }
                    : null),
                };
              }
              return updated;
            });
            setStreaming(false);
            streamAbortRef.current = null;
            void loadThreads();
          },
          onError: (err) => {
            setMessages((prev) => {
              const updated = [...prev];
              const idx = updated.findIndex((m) => m.id === placeholderId);
              if (idx !== -1) {
                updated[idx] = {
                  ...updated[idx],
                  content:
                    updated[idx].content ||
                    "Sorry, I encountered an error: " + err.message,
                  streaming: false,
                  progressText: null,
                  completedAt: new Date().toISOString(),
                };
              }
              return updated;
            });
            setStreaming(false);
            streamAbortRef.current = null;
            streamResultRef.current = null;
            void loadThreads();
          },
        },
        { chatId: savedChatId, signal: abortController.signal },
      );
    } catch {
      setMessages((prev) => {
        const updated = [...prev];
        const idx = updated.findIndex((m) => m.id === placeholderId);
        if (idx !== -1) {
          updated[idx] = {
            ...updated[idx],
            content: "Sorry, something went wrong. Please try again.",
            streaming: false,
            completedAt: new Date().toISOString(),
          };
        }
        return updated;
      });
      setStreaming(false);
      streamAbortRef.current = null;
      streamResultRef.current = null;
      void loadThreads();
    } finally {
      setSending(false);
    }
  }, [prompt, sending, connectionId, ensureSessionChatId, loadThreads, username]);

  const handleKeyDown = (e) => {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  };

  // ── Render ─────────────────────────────────────────────
  const currentThread = threads.find((thread) => thread.id === currentChatId);

  return (
    <div className={styles.root}>
      <aside className={styles.threadRail}>
        <div className={styles.threadRailHeader}>
          <div>
            <div className={styles.threadRailEyebrow}>Threads</div>
            <div className={styles.threadRailTitle}>Chat history</div>
          </div>
          <button
            className={styles.iconBtn}
            onClick={handleNewChat}
            title="New chat"
            disabled={!connectionId}
          >
            <SquarePen size={18} />
          </button>
        </div>
        {!connectionId ? (
          <div className={styles.threadRailEmpty}>Select a connection to view chat threads.</div>
        ) : threadsLoading ? (
          <div className={styles.threadRailEmpty}>Loading threads…</div>
        ) : threads.length === 0 ? (
          <div className={styles.threadRailEmpty}>No saved chats yet. Start a new conversation.</div>
        ) : (
          <div className={styles.threadList}>
            {threads.map((thread) => {
              const displayTitle = thread.title || "Untitled chat";
              const threadTimestamp = formatThreadTimestamp(
                thread.lastMessageAt || thread.updatedAt || thread.createdAt,
              );
              const isEditing = editingChatId === thread.id;
              const isDeleteConfirm = confirmDeleteChatId === thread.id;

              return (
                <div
                  key={thread.id}
                  className={`${styles.threadItem} ${thread.id === currentChatId ? styles.threadItemActive : ""} ${isDeleteConfirm ? styles.threadItemDeleteConfirm : ""}`}
                >
                  {isEditing ? (
                    <div className={styles.threadSelectEditing}>
                      <MessageSquareText size={14} className={styles.threadIcon} />
                      <input
                        autoFocus
                        className={styles.threadTitleInput}
                        value={editingChatTitle}
                        onChange={(event) => setEditingChatTitle(event.target.value)}
                        onBlur={handleSaveRename}
                        onKeyDown={(event) => {
                          if (event.key === "Enter") {
                            event.preventDefault();
                            void handleSaveRename();
                          }
                          if (event.key === "Escape") {
                            setEditingChatId(null);
                            setEditingChatTitle("");
                          }
                        }}
                      />
                    </div>
                  ) : (
                    <button
                      type="button"
                      className={styles.threadSelect}
                      onClick={() => handleSelectThread(thread.id)}
                      title={threadTimestamp ? `${displayTitle} · ${threadTimestamp}` : displayTitle}
                    >
                      <MessageSquareText size={14} className={styles.threadIcon} />
                      <span className={styles.threadTitle}>{displayTitle}</span>
                    </button>
                  )}
                  {!isEditing && (
                    <div className={styles.threadActions}>
                      <button
                        type="button"
                        className={styles.threadActionBtn}
                        onClick={(event) => {
                          event.preventDefault();
                          event.stopPropagation();
                          handleBeginRename(thread);
                        }}
                        aria-label="Rename thread"
                        title="Rename thread"
                      >
                        <Pencil size={13} />
                      </button>
                      <button
                        type="button"
                        className={`${styles.threadActionBtn} ${isDeleteConfirm ? styles.threadActionBtnDanger : ""}`}
                        onClick={(event) => {
                          event.preventDefault();
                          event.stopPropagation();
                          void handleDeleteThread(thread.id);
                        }}
                        aria-label={isDeleteConfirm ? "Confirm delete thread" : "Delete thread"}
                        title={isDeleteConfirm ? "Click again to delete" : "Delete thread"}
                      >
                        <Trash2 size={13} />
                        {isDeleteConfirm && (
                          <span className={styles.threadActionLabel}>Delete?</span>
                        )}
                      </button>
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        )}
      </aside>

      <div className={styles.chatPane}>
        <div className={styles.actionsRow}>
          <div className={styles.topBarTitleBlock}>
            <div className={styles.topBarEyebrow}>Chat</div>
            <div className={styles.topBarTitle}>{currentThread?.title || "New conversation"}</div>
          </div>
        </div>

        <div className={styles.messagesArea}>
        {!connectionId ? (
          <div className={styles.noConnection}>
            <Database size={40} style={{ opacity: 0.2 }} />
            <p className={styles.noConnectionText}>No database connected</p>
            <p className="text-sm text-gray-400 mb-4 text-center max-w-xs">
              Add your first database connection to start chatting with your data.
            </p>
            <button
              className="inline-flex items-center gap-2 px-5 py-2.5 bg-gray-900 text-white text-sm font-medium rounded-lg hover:bg-gray-700 transition-colors"
              onClick={() => setShowAddConnection(true)}
            >
              <Plus size={15} />
              Add connection
            </button>
          </div>
        ) : messages.length === 0 ? (
          <div className={styles.emptyState}>
            <h1 className={styles.emptyGreeting}>What can I help you with?</h1>
            <p className={styles.emptySubtitle}>
              Ask anything about your database — queries, performance, schema,
              or optimization.
            </p>
            <div className={styles.promptGrid}>
              {examplePrompts.map((p, i) => (
                <button
                  key={i}
                  className={styles.promptCard}
                  onClick={() => setPrompt(p.text)}
                >
                  <span className={styles.promptCardText}>{p.text}</span>
                  <span className={styles.promptCardSub}>{p.sub}</span>
                </button>
              ))}
            </div>
          </div>
        ) : (
          <div className={styles.messagesInner}>
            {messages.map((msg, idx) => {
              const visibleAssistantContent = getVisibleAssistantContent(msg);
              const shouldShowStructuredResults = !visibleAssistantContent;

              return msg.role === "user" ? (
                <div
                  key={msg.id}
                  className={`${styles.message} ${styles.messageUser}`}
                >
                  <div className={styles.userBubble}>{msg.content}</div>
                </div>
              ) : (
                <div
                  key={msg.id}
                  className={`${styles.message} ${styles.messageAssistant}`}
                >
                  <div className={styles.assistantAvatar}>
                    <Database size={13} color="#fff" />
                  </div>
                  <div className={styles.assistantBody}>
                    <AgentActivityCard
                      message={msg}
                      nowMs={agentActivityNow}
                      isAdmin={isAdmin}
                      onTraceOpen={handleTraceOpen}
                    />
                    {msg.progressText &&
                      !msg.content &&
                      !buildAgentActivity(msg, agentActivityNow) && (
                      <WaveText
                        text={msg.progressText}
                        className={styles.streamingProgress}
                      />
                    )}
                    <div
                      className={`${styles.assistantContent}${msg.streaming && msg.content ? ` ${styles.assistantContentStreaming}` : ""}`}
                    >
                      {visibleAssistantContent
                        ? renderMarkdown(visibleAssistantContent, styles)
                        : null}
                    </div>
                    {msg.executingQuery && (
                      <div className={styles.queryExecuting}>
                        <div className={styles.queryExecutingDot} />
                        <span>Executing query…</span>
                      </div>
                    )}
                    {shouldShowStructuredResults && msg.resultSets?.length ? (
                      <div className={styles.multiResultSets}>
                        {msg.resultSets
                          .filter((resultSet) => resultSet?.data)
                          .map((resultSet, resultIndex) => (
                            <div
                              key={resultSet.taskId || `result-set-${resultIndex}`}
                              className={styles.queryResultWrap}
                            >
                              <div className={styles.queryResultHeader}>
                                <span className={styles.queryResultLabel}>
                                  {resultSet.title || "Results"}
                                </span>
                                <span className={styles.queryResultMeta}>
                                  {resultSet.kind || "TASK"}
                                  {Array.isArray(resultSet.dependsOn) &&
                                  resultSet.dependsOn.length
                                    ? ` · depends on ${resultSet.dependsOn.join(", ")}`
                                    : ""}
                                  {resultSet.data?.rowCount != null
                                    ? ` · ${resultSet.data.rowCount} row${resultSet.data.rowCount !== 1 ? "s" : ""}`
                                    : ""}
                                  {resultSet.data?.isLimited ? " (limited)" : ""}
                                  {resultSet.data?.executionTimeMs != null
                                    ? ` · ${resultSet.data.executionTimeMs}ms`
                                    : ""}
                                </span>
                              </div>
                              {resultSet.summary ? (
                                <p className={styles.resultSetSummary}>
                                  {resultSet.summary}
                                </p>
                              ) : null}
                              <div className={styles.queryResultTable}>
                                <table>
                                  <thead>
                                    <tr>
                                      {resultSet.data?.columns?.map((col, ci) => (
                                        <th key={ci}>{col}</th>
                                      ))}
                                    </tr>
                                  </thead>
                                  <tbody>
                                    {resultSet.data?.rows?.map((row, ri) => (
                                      <tr key={ri}>
                                        {row.map((cell, ci) => (
                                          <td key={ci}>
                                            {cell === null ? (
                                              <span className={styles.nullValue}>
                                                NULL
                                              </span>
                                            ) : (
                                              String(cell)
                                            )}
                                          </td>
                                        ))}
                                      </tr>
                                    ))}
                                  </tbody>
                                </table>
                              </div>
                            </div>
                          ))}
                      </div>
                    ) : shouldShowStructuredResults && msg.queryResult ? (
                      <div className={styles.queryResultWrap}>
                        <div className={styles.queryResultHeader}>
                          <span className={styles.queryResultLabel}>
                            Results
                          </span>
                          <span className={styles.queryResultMeta}>
                            {msg.queryResult.rowCount} row
                            {msg.queryResult.rowCount !== 1 ? "s" : ""}
                            {msg.queryResult.isLimited ? " (limited)" : ""}
                            {msg.queryResult.executionTimeMs != null &&
                              ` · ${msg.queryResult.executionTimeMs}ms`}
                          </span>
                        </div>
                        <div className={styles.queryResultTable}>
                          <table>
                            <thead>
                              <tr>
                                {msg.queryResult.columns?.map((col, ci) => (
                                  <th key={ci}>{col}</th>
                                ))}
                              </tr>
                            </thead>
                            <tbody>
                              {msg.queryResult.rows?.map((row, ri) => (
                                <tr key={ri}>
                                  {row.map((cell, ci) => (
                                    <td key={ci}>
                                      {cell === null ? (
                                        <span className={styles.nullValue}>
                                          NULL
                                        </span>
                                      ) : (
                                        String(cell)
                                      )}
                                    </td>
                                  ))}
                                </tr>
                              ))}
                            </tbody>
                          </table>
                        </div>
                      </div>
                    ) : null}
                    {msg.queryError && (
                      <div className={styles.queryError}>
                        <span>Query failed: {msg.queryError}</span>
                      </div>
                    )}
                    {!msg.streaming && (
                      <FeedbackButtons
                        connectionId={connectionId}
                        chatId={currentChatId}
                        messageId={msg.id}
                        userMessage={
                          idx > 0 ? messages[idx - 1]?.content || "" : ""
                        }
                        aiResponse={visibleAssistantContent}
                        sql={msg.sql}
                        agentRunId={msg.agentRunId}
                        onBuildAgent={
                          AGENTS_ENABLED ? handleBuildAgent : undefined
                        }
                      />
                    )}
                  </div>
                </div>
              );
            })}

            {sending && !streaming && (
              <div className={styles.thinkingBubble}>
                <div className={styles.assistantAvatar}>
                  <Database size={13} color="#fff" />
                </div>
                <div className={styles.thinkingDots}>
                  <span className={styles.dot} />
                  <span className={styles.dot} />
                  <span className={styles.dot} />
                </div>
              </div>
            )}
            <div ref={messagesEndRef} />
          </div>
        )}
        </div>

        <div className={styles.inputArea}>
          <div className={styles.inputWrap}>
            <textarea
              ref={textareaRef}
              className={styles.inputBox}
              placeholder={
                connectionId
                  ? "Ask anything about your database…"
                  : "Select a connection to start chatting"
              }
              value={prompt}
              onChange={(e) => {
                setPrompt(e.target.value);
                autoResize();
              }}
              onKeyDown={handleKeyDown}
              disabled={!connectionId || sending}
              rows={1}
            />
            <button
              className={styles.sendBtn}
              onClick={handleSend}
              disabled={!prompt.trim() || sending || !connectionId}
              title="Send"
            >
              <ArrowUp size={16} />
            </button>
          </div>
          <p className={styles.inputHint}>
            DeepSQL can make mistakes. Verify important findings with your DBA.
          </p>
        </div>
      </div>

      {AGENTS_ENABLED && buildAgentData && (
        <CreateAgentModal
          connections={connections}
          initialValues={buildAgentData}
          onSave={handleSaveAgent}
          onClose={() => setBuildAgentData(null)}
        />
      )}

      {showAddConnection && (
        <ManageConnectionsModal
          isOpen={showAddConnection}
          onClose={() => setShowAddConnection(false)}
          onConnectionSaved={(newId) => {
            setShowAddConnection(false);
            if (newId) {
              changeConnection(newId);
              setBrainInitConnectionId(newId);
            }
          }}
          onConnectionDeleted={() => {}}
        />
      )}

      {brainInitConnectionId && (
        <BrainInitModal
          connectionId={brainInitConnectionId}
          onClose={() => setBrainInitConnectionId(null)}
        />
      )}
    </div>
  );
}
