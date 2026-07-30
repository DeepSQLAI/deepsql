"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { RefreshCw } from "lucide-react";
import { useQueryClient } from "@tanstack/react-query";
import { queryKeys } from "@/lib/queryKeys";
import { useBrainAnalyticsSummary } from "../hooks/useBrainAnalyticsSummary";
import CollapsibleSection from "./CollapsibleSection";
import WorkloadPanel from "../WorkloadPanel";
import QueryIntelligencePanel from "../QueryIntelligencePanel";
import { QueryAntiPatternsPanel } from "../QueryAntiPatternsPanel";
import { ScalabilityDashboardPanel } from "../ScalabilityDashboardPanel";
import BrainOverviewHero from "./BrainOverviewHero";
import styles from "./BrainAnalyticsLayout.module.css";
import coreStyles from "../../Core/RagTrainingTab.module.css";

const formatPercent = (value) => {
  if (value === null || value === undefined) return null;
  return `${Math.round(value * 100)}%`;
};

const getRiskScore = (riskLevel) => {
  const map = { CRITICAL: 100, HIGH: 80, MEDIUM: 50, LOW: 20 };
  return map[riskLevel] || 0;
};

const getAccuracyScore = (accuracy) => {
  if (accuracy === null || accuracy === undefined) return 0;
  if (accuracy < 0.6) return 80;
  if (accuracy < 0.7) return 60;
  if (accuracy < 0.8) return 40;
  return 10;
};

const getSchemaScore = (healthScore, hasCycles) => {
  if (hasCycles) return 90;
  if (healthScore === null || healthScore === undefined) return 0;
  if (healthScore < 50) return 70;
  if (healthScore < 60) return 60;
  if (healthScore < 70) return 40;
  return 10;
};

export function BrainAnalyticsLayout({ connectionId, activeTab, onTabChange }) {
  const queryClient = useQueryClient();
  const summary = useBrainAnalyticsSummary(connectionId);
  const [expandedSections, setExpandedSections] = useState({});
  const [autoExpanded, setAutoExpanded] = useState(false);

  const handleRefresh = useCallback(() => {
    if (!connectionId) return;
    queryClient.invalidateQueries({
      queryKey: queryKeys.brain.workloadProfile(connectionId),
    });
    queryClient.invalidateQueries({
      queryKey: queryKeys.brain.workloadStatus(connectionId),
    });
    queryClient.invalidateQueries({
      queryKey: queryKeys.brain.schemaClassification(connectionId),
    });
    queryClient.invalidateQueries({
      queryKey: queryKeys.brain.queryAntiPatterns(connectionId, null),
    });
    queryClient.invalidateQueries({
      queryKey: queryKeys.brain.cardinalityAccuracy(connectionId),
    });
    queryClient.invalidateQueries({
      queryKey: queryKeys.brain.latestSimulation(connectionId),
    });
    queryClient.invalidateQueries({
      queryKey: queryKeys.brain.highRiskTables(connectionId),
    });
  }, [connectionId, queryClient]);

  const sections = useMemo(
    () => [
      {
        id: "workload",
        title: "Workload Intelligence",
        description:
          "ML-powered workload characterization and learning progress.",
        component: <WorkloadPanel connectionId={connectionId} />,
      },
      {
        id: "query-intelligence",
        title: "Query Intelligence",
        description: "Cardinality accuracy and reliable plan patterns.",
        component: <QueryIntelligencePanel connectionId={connectionId} />,
      },
      {
        id: "anti-patterns",
        title: "Query Anti-Patterns",
        description: "Detected query quality issues and performance smells.",
        component: <QueryAntiPatternsPanel connectionId={connectionId} />,
      },
      {
        id: "scalability",
        title: "Scalability Simulation",
        description: "Growth predictions and scalability risk assessment.",
        component: <ScalabilityDashboardPanel connectionId={connectionId} />,
      },
    ],
    [connectionId],
  );

  const summaryChips = useMemo(() => {
    const chips = {};

    const workloadChips = [];
    if (summary.workload.type) workloadChips.push(summary.workload.type);
    if (
      summary.workload.confidence !== null &&
      summary.workload.confidence !== undefined
    ) {
      workloadChips.push(
        `${Math.round(summary.workload.confidence)}% confidence`,
      );
    }
    if (summary.workload.lastUpdated && summary.freshness?.value) {
      workloadChips.push(`Updated ${summary.freshness.value}`);
    } else if (summary.workload.lastSnapshotAt && summary.freshness?.value) {
      workloadChips.push(`Snapshot ${summary.freshness.value}`);
    } else if (
      summary.workload.snapshotCount !== null &&
      summary.workload.minSnapshotsRequired !== null
    ) {
      workloadChips.push(
        `${summary.workload.snapshotCount}/${summary.workload.minSnapshotsRequired} snapshots`,
      );
    } else if (summary.workload.readyToCharacterize) {
      workloadChips.push("Ready to analyze");
    }

    chips.workload = workloadChips;

    const queryChips = [];
    if (
      summary.query.overallAccuracy !== null &&
      summary.query.overallAccuracy !== undefined
    ) {
      queryChips.push(
        `${Math.round(summary.query.overallAccuracy * 100)}% accuracy`,
      );
    }
    if (summary.query.needsExplainData) queryChips.push("EXPLAIN needed");
    if (
      summary.query.totalEstimates !== null &&
      summary.query.totalEstimates !== undefined
    ) {
      queryChips.push(`${summary.query.totalEstimates} estimates`);
    }
    chips["query-intelligence"] = queryChips;

    const antiPatternChips = [];
    if (summary.antiPatterns) {
      antiPatternChips.push(`${summary.antiPatterns.critical} critical`);
      antiPatternChips.push(`${summary.antiPatterns.high} high`);
      antiPatternChips.push(`${summary.antiPatterns.total} total`);
    }
    chips["anti-patterns"] = antiPatternChips;

    const scalabilityChips = [];
    if (summary.scalability.riskLevel)
      scalabilityChips.push(`${summary.scalability.riskLevel} risk`);
    if (summary.scalability.scenario)
      scalabilityChips.push(`${summary.scalability.scenario} scenario`);
    if (summary.scalability.highRiskCount)
      scalabilityChips.push(
        `${summary.scalability.highRiskCount} high-risk tables`,
      );
    chips.scalability = scalabilityChips;

    return chips;
  }, [summary]);

  const insights = useMemo(() => {
    const items = [];

    if (summary.antiPatterns?.critical > 0) {
      items.push({
        text: `${summary.antiPatterns.critical} critical anti-patterns detected`,
        sectionId: "anti-patterns",
      });
    } else if (summary.antiPatterns?.high > 0) {
      items.push({
        text: `${summary.antiPatterns.high} high anti-patterns detected`,
        sectionId: "anti-patterns",
      });
    } else if (summary.antiPatterns?.total > 0) {
      items.push({
        text: `${summary.antiPatterns.total} total anti-patterns detected`,
        sectionId: "anti-patterns",
      });
    }

    if (summary.scalability.riskLevel) {
      const scenarioLabel = summary.scalability.scenario
        ? ` for ${summary.scalability.scenario}`
        : "";
      items.push({
        text: `Scalability risk ${summary.scalability.riskLevel}${scenarioLabel}`,
        sectionId: "scalability",
      });
    }

    if (
      summary.query.overallAccuracy !== null &&
      summary.query.overallAccuracy !== undefined
    ) {
      const accuracyPercent = Math.round(summary.query.overallAccuracy * 100);
      if (summary.query.overallAccuracy < 0.7) {
        items.push({
          text: `Cardinality accuracy below 70% (${accuracyPercent}%)`,
          sectionId: "query-intelligence",
        });
      } else {
        items.push({
          text: `Cardinality accuracy at ${accuracyPercent}%`,
          sectionId: "query-intelligence",
        });
      }
    } else if (summary.query.needsExplainData) {
      items.push({
        text: "EXPLAIN data required for accuracy metrics",
        sectionId: "query-intelligence",
      });
    }

    if (summary.schema.hasCycles) {
      items.push({
        text: "Schema has circular dependencies",
        sectionId: "schema",
      });
    } else if (
      summary.schema.avgHealthScore !== null &&
      summary.schema.avgHealthScore !== undefined &&
      summary.schema.avgHealthScore < 70
    ) {
      items.push({
        text: `Schema health below 70 (${summary.schema.avgHealthScore}/100)`,
        sectionId: "schema",
      });
    } else if (!summary.schema.avgHealthScore && summary.schema.pattern) {
      items.push({
        text: `Schema pattern ${summary.schema.pattern}`,
        sectionId: "schema",
      });
    }

    if (!summary.workload.type && summary.workload.readyToCharacterize) {
      items.push({
        text: "Workload profile ready to analyze",
        sectionId: "workload",
      });
    } else if (
      !summary.workload.type &&
      summary.workload.snapshotCount !== null &&
      summary.workload.minSnapshotsRequired !== null
    ) {
      items.push({
        text: `Collecting workload snapshots (${summary.workload.snapshotCount}/${summary.workload.minSnapshotsRequired})`,
        sectionId: "workload",
      });
    }

    const unique = [];
    const seen = new Set();
    items.forEach((item) => {
      const key = `${item.sectionId}:${item.text}`;
      if (!seen.has(key)) {
        seen.add(key);
        unique.push(item);
      }
    });

    return unique.slice(0, 5);
  }, [summary]);

  const defaultExpanded = useMemo(() => {
    const scores = {
      workload: summary.workload.readyToCharacterize ? 40 : 0,
      "query-intelligence":
        getAccuracyScore(summary.query.overallAccuracy) +
        (summary.query.needsExplainData ? 50 : 0),
      "anti-patterns": summary.antiPatterns
        ? summary.antiPatterns.critical * 100 +
          summary.antiPatterns.high * 25 +
          summary.antiPatterns.medium * 10
        : 0,
      scalability: getRiskScore(summary.scalability.riskLevel),
    };

    const ranked = Object.entries(scores)
      .sort((a, b) => b[1] - a[1])
      .map(([id]) => id);

    const hasUrgency = Object.values(scores).some((score) => score > 0);
    const defaults = hasUrgency
      ? ranked.slice(0, 2)
      : ["workload", "query-intelligence"];

    return defaults.reduce((acc, id) => {
      acc[id] = true;
      return acc;
    }, {});
  }, [summary]);

  useEffect(() => {
    if (autoExpanded || summary.loading) return;
    setExpandedSections(defaultExpanded);
    setAutoExpanded(true);
  }, [autoExpanded, summary.loading, defaultExpanded]);

  const toggleSection = useCallback((sectionId) => {
    setExpandedSections((prev) => ({
      ...prev,
      [sectionId]: !prev[sectionId],
    }));
  }, []);

  const workloadStatValue = summary.workload.type || "--";
  const workloadMeta =
    summary.workload.confidence !== null &&
    summary.workload.confidence !== undefined
      ? `${Math.round(summary.workload.confidence)}% confidence`
      : summary.workload.snapshotCount !== null &&
          summary.workload.minSnapshotsRequired !== null
        ? `${summary.workload.snapshotCount}/${summary.workload.minSnapshotsRequired} snapshots`
        : summary.workload.readyToCharacterize
          ? "Ready to analyze"
          : "No data";

  const schemaStatValue =
    summary.schema.avgHealthScore !== null &&
    summary.schema.avgHealthScore !== undefined
      ? `${summary.schema.avgHealthScore} / 100`
      : "--";
  const schemaMeta = summary.schema.pattern || "No pattern";

  const queryStatValue = formatPercent(summary.query.overallAccuracy) || "--";
  const queryMeta = summary.query.needsExplainData
    ? "EXPLAIN needed"
    : summary.query.totalEstimates !== null &&
        summary.query.totalEstimates !== undefined
      ? `${summary.query.totalEstimates} estimates`
      : "No data";

  const antiPatternValue = summary.antiPatterns
    ? `${summary.antiPatterns.critical} critical`
    : "--";
  const antiPatternMeta = summary.antiPatterns
    ? `${summary.antiPatterns.high} high`
    : "No data";

  const scalabilityValue = summary.scalability.riskLevel
    ? `Risk: ${summary.scalability.riskLevel}`
    : "--";
  const scalabilityMeta = summary.scalability.scenario || "No scenario";

  return (
    <div className={styles.layout}>
      <BrainOverviewHero
        title="Brain Analytics"
        subtitle="Deep diagnostics across workload, query quality, schema, and growth."
        activeTab={activeTab}
        onTabChange={onTabChange}
        onAnalyze={handleRefresh}
        isAnalyzing={summary.loading}
      >
        {summary.freshness && (
          <div className={styles.heroMeta}>
            <span>{summary.freshness.label}:</span>
            <strong>{summary.freshness.value}</strong>
          </div>
        )}
      </BrainOverviewHero>

      <div className={styles.statsGrid}>
        <div className={styles.statCard}>
          <div className={styles.statLabel}>Workload</div>
          <div className={styles.statValue}>{workloadStatValue}</div>
          <div className={styles.statMeta}>{workloadMeta}</div>
        </div>
        <div className={styles.statCard}>
          <div className={styles.statLabel}>Schema Health</div>
          <div className={styles.statValue}>{schemaStatValue}</div>
          <div className={styles.statMeta}>{schemaMeta}</div>
        </div>
        <div className={styles.statCard}>
          <div className={styles.statLabel}>Query Accuracy</div>
          <div className={styles.statValue}>{queryStatValue}</div>
          <div className={styles.statMeta}>{queryMeta}</div>
        </div>
        <div className={styles.statCard}>
          <div className={styles.statLabel}>Anti-Patterns</div>
          <div className={styles.statValue}>{antiPatternValue}</div>
          <div className={styles.statMeta}>{antiPatternMeta}</div>
        </div>
        <div className={styles.statCard}>
          <div className={styles.statLabel}>Scalability</div>
          <div className={styles.statValue}>{scalabilityValue}</div>
          <div className={styles.statMeta}>{scalabilityMeta}</div>
        </div>
      </div>

      <div className={styles.insights}>
        <div className={styles.insightsHeader}>Quick Insights</div>
        <div className={styles.insightList}>
          {insights.length > 0 ? (
            insights.map((insight, idx) => (
              <button
                type="button"
                key={`insight-${idx}`}
                className={styles.insightButton}
                onClick={() => {
                  const element = document.getElementById(
                    `brain-analytics-${insight.sectionId}`,
                  );
                  if (element) {
                    element.scrollIntoView({
                      behavior: "smooth",
                      block: "start",
                    });
                  }
                }}
              >
                {insight.text}
              </button>
            ))
          ) : (
            <span className={styles.chip}>No critical insights yet</span>
          )}
        </div>
      </div>

      {sections.map((section) => (
        <CollapsibleSection
          key={section.id}
          id={`brain-analytics-${section.id}`}
          title={section.title}
          description={section.description}
          summaryChips={summaryChips[section.id] || []}
          expanded={Boolean(expandedSections[section.id])}
          onToggle={() => toggleSection(section.id)}
        >
          {section.component}
        </CollapsibleSection>
      ))}
    </div>
  );
}

export default BrainAnalyticsLayout;
