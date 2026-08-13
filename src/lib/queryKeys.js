/**
 * Query key factory for TanStack Query v5
 *
 * Provides consistent, type-safe query keys across the application.
 * Query keys are arrays that uniquely identify cached data.
 *
 * Usage:
 *   queryKeys.connections.all                    // ['connections']
 *   queryKeys.connections.detail(123)            // ['connections', 123]
 *   queryKeys.brain.keyColumns(connId, params)   // ['brain', connId, 'keyColumns', params]
 */

export const queryKeys = {
  // ==================== Auth ====================
  auth: {
    all: ["auth"],
    user: () => ["auth", "user"],
  },

  // ==================== Connections ====================
  connections: {
    all: ["connections"],
    lists: () => [...queryKeys.connections.all, "list"],
    list: (filters) => [...queryKeys.connections.lists(), filters],
    details: () => [...queryKeys.connections.all, "detail"],
    detail: (id) => [...queryKeys.connections.details(), id],
  },

  // ==================== Schema ====================
  schema: {
    all: (connectionId) => ["schema", connectionId],
    metadata: (connectionId) => ["schema", connectionId, "metadata"],
    visualization: (connectionId) => ["schema", connectionId, "visualization"],
    changes: (connectionId) => ["schema", connectionId, "changes"],
  },

  // ==================== Training (RAG) ====================
  training: {
    all: (connectionId) => ["training", connectionId],
    status: (connectionId) => ["training", connectionId, "status"],
    history: (connectionId) => ["training", connectionId, "history"],
    stats: (connectionId) => ["training", connectionId, "stats"],
    queueMetrics: () => ["training", "queue", "metrics"],
  },

  // ==================== Brain ====================
  brain: {
    all: (connectionId) => ["brain", connectionId],
    understanding: (connectionId) => ["brain", connectionId, "understanding"],
    initStatus: (connectionId) => ["brain", connectionId, "initStatus"],

    // Tasks
    tasks: (connectionId, status) => [
      "brain",
      connectionId,
      "tasks",
      { status },
    ],

    // Notes
    notes: (connectionId, params) => ["brain", connectionId, "notes", params],

    // Key Columns
    keyColumns: (connectionId, params) => [
      "brain",
      connectionId,
      "keyColumns",
      params,
    ],

    // Schema Classification
    schemaClassification: (connectionId) => [
      "brain",
      connectionId,
      "schemaClassification",
    ],
    tableClassifications: (connectionId, role) => [
      "brain",
      connectionId,
      "tableClassifications",
      { role },
    ],
    factTables: (connectionId) => ["brain", connectionId, "factTables"],
    dimensionTables: (connectionId) => [
      "brain",
      connectionId,
      "dimensionTables",
    ],

    // Query Anti-Patterns
    queryAntiPatterns: (connectionId, limit) => [
      "brain",
      connectionId,
      "queryAntiPatterns",
      { limit },
    ],

    // Workload Intelligence
    workloadProfile: (connectionId) => [
      "brain",
      connectionId,
      "workload",
      "profile",
    ],
    workloadStatus: (connectionId) => [
      "brain",
      connectionId,
      "workload",
      "status",
    ],

    // Cardinality Accuracy
    cardinalityAccuracy: (connectionId) => [
      "brain",
      connectionId,
      "statistics",
      "accuracy",
    ],

    // Scalability Simulation
    scalabilitySimulations: (connectionId) => [
      "brain",
      connectionId,
      "scalability",
    ],
    latestSimulation: (connectionId) => [
      "brain",
      connectionId,
      "scalability",
      "latest",
    ],
    tablePredictions: (simulationId) => [
      "brain",
      "scalability",
      "predictions",
      simulationId,
    ],
    highRiskTables: (connectionId) => [
      "brain",
      connectionId,
      "scalability",
      "highRisk",
    ],

    // Brain Score
    score: (connectionId) => ["brain", connectionId, "score"],
    scoreHistory: (connectionId, limit) => [
      "brain",
      connectionId,
      "score",
      "history",
      { limit },
    ],
  },

  // ==================== Stats ====================
  stats: {
    all: (connectionId) => ["stats", connectionId],
  },

  // ==================== Query ====================
  query: {
    objects: (connectionId) => ["query", connectionId, "objects"],
    indexes: (connectionId, tableName) => [
      "query",
      connectionId,
      "tables",
      tableName,
      "indexes",
    ],
    tableStats: (connectionId, tableName) => [
      "query",
      connectionId,
      "tables",
      tableName,
      "stats",
    ],
  },

  // ==================== Chat ====================
  chat: {
    all: (connectionId) => ["chat", connectionId],
    history: (projectId, connectionId) => [
      "chat",
      "history",
      { projectId, connectionId },
    ],
    detail: (chatId) => ["chat", "detail", chatId],
    active: (connectionId) => ["chat", connectionId, "active"],
  },

  // ==================== Company Knowledge ====================
  companyKnowledge: {
    all: (connectionId) => ["companyKnowledge", connectionId],
    list: (connectionId) => ["companyKnowledge", connectionId, "list"],
    detail: (entryId) => ["companyKnowledge", "detail", entryId],
  },

  // ==================== Code Scan (Company Knowledge enrichment) ====================
  codeScan: {
    all: (connectionId) => ["codeScan", connectionId],
    sources: (connectionId) => ["codeScan", connectionId, "sources"],
    jobs: (connectionId, sourceId) => ["codeScan", connectionId, "jobs", sourceId],
    job: (connectionId, jobId) => ["codeScan", connectionId, "job", jobId],
    suggestions: (connectionId, params) => ["codeScan", connectionId, "suggestions", params],
  },

  // ==================== Schema Context (ambiguity inventory) ====================
  schemaContext: {
    all: (connectionId) => ["schemaContext", connectionId],
    ambiguity: (connectionId) => ["schemaContext", connectionId, "ambiguity"],
  },

  // ==================== Projects ====================
  projects: {
    all: ["projects"],
    list: (connectionId) => ["projects", "list", { connectionId }],
    detail: (projectId) => ["projects", projectId],
  },

  // ==================== Slow Queries ====================
  slowQueries: {
    all: (connectionId) => ["slowQueries", connectionId],
    history: (connectionId) => ["slowQueries", connectionId, "history"],
    historyDetail: (id) => ["slowQueries", "history", id],
    latest: (connectionId) => ["slowQueries", connectionId, "latest"],

    // Optimization
    optimization: (connectionId) => [
      "slowQueries",
      connectionId,
      "optimization",
    ],
    candidates: (connectionId, fingerprint) => [
      "slowQueries",
      connectionId,
      "optimization",
      "candidates",
      fingerprint,
    ],

    // Optimization Cache
    cachedOptimization: (connectionId, fingerprint) => [
      "slowQueries",
      "cachedOptimization",
      connectionId,
      fingerprint,
    ],
    cachedOptimizations: (connectionId, fingerprints) => [
      "slowQueries",
      "cachedOptimizations",
      connectionId,
      fingerprints,
    ],
    cacheStats: (connectionId) => ["slowQueries", connectionId, "cacheStats"],

    // Alerts
    alerts: (connectionId) => ["slowQueries", connectionId, "alerts"],

    // Dashboard
    dashboard: (connectionId) => ["slowQueries", connectionId, "dashboard"],
    overview: (connectionId) => [
      "slowQueries",
      connectionId,
      "dashboard",
      "overview",
    ],
    trend: (connectionId) => [
      "slowQueries",
      connectionId,
      "dashboard",
      "trend",
    ],

    // Fingerprints
    fingerprints: (connectionId) => [
      "slowQueries",
      connectionId,
      "fingerprints",
    ],
    fingerprintsList: (connectionId, options) => [
      "slowQueries",
      connectionId,
      "fingerprints",
      "list",
      options,
    ],
    fingerprintTrend: (fingerprintId) => [
      "slowQueries",
      "fingerprint",
      fingerprintId,
      "trend",
    ],

    // Explain
    explain: (connectionId) => ["slowQueries", connectionId, "explain"],
    criticalExplains: (connectionId) => [
      "slowQueries",
      connectionId,
      "explain",
      "critical",
    ],

    // Key Customers
    keyCustomers: (connectionId, { limit, tableName } = {}) => [
      "slowQueries",
      connectionId,
      "keyCustomers",
      { limit, tableName },
    ],

    // Advanced Insights
    insights: (connectionId, { window, limit } = {}) => [
      "slowQueries",
      connectionId,
      "insights",
      { window, limit },
    ],
    insightsRemediation: (connectionId, { window, limit } = {}) => [
      "slowQueries",
      connectionId,
      "insights",
      "remediation",
      { window, limit },
    ],
    insightsHotspots: (connectionId, { window, limit } = {}) => [
      "slowQueries",
      connectionId,
      "insights",
      "hotspots",
      { window, limit },
    ],
    insightsSkew: (connectionId, { window, limit } = {}) => [
      "slowQueries",
      connectionId,
      "insights",
      "skew",
      { window, limit },
    ],
    insightsTailRisk: (connectionId, { window, limit } = {}) => [
      "slowQueries",
      connectionId,
      "insights",
      "tailRisk",
      { window, limit },
    ],
    insightsPlanDrift: (connectionId, { window, limit } = {}) => [
      "slowQueries",
      connectionId,
      "insights",
      "planDrift",
      { window, limit },
    ],
  },

  // ==================== Slow Log Source ====================
  slowLogSource: {
    config: (connectionId) => ["slowLogSource", connectionId, "config"],
    jobStatus: (jobId) => ["slowLogSource", "job", jobId],
    activeJob: (connectionId) => ["slowLogSource", connectionId, "activeJob"],
  },

  // ==================== Index Recommendations ====================
  index: {
    recommendations: (connectionId) => [
      "index",
      connectionId,
      "recommendations",
    ],
  },

  // ==================== Performance ====================
  performance: {
    metrics: (connectionId) => ["performance", connectionId, "metrics"],
    dashboard: (connectionId, days) => [
      "performance",
      connectionId,
      "dashboard",
      { days },
    ],
  },

  // ==================== Lock Contention ====================
  locks: {
    active: (connectionId) => ["locks", connectionId, "active"],
    statistics: (connectionId) => ["locks", connectionId, "statistics"],
  },

  // ==================== Active Queries ====================
  activeQueries: {
    all: (connectionId) => ["activeQueries", connectionId],
    list: (connectionId) => ["activeQueries", connectionId, "list"],
    latest: (connectionId) => ["activeQueries", connectionId, "latest"],
    statistics: (connectionId) => ["activeQueries", connectionId, "statistics"],
    filterOptions: (connectionId) => [
      "activeQueries",
      connectionId,
      "filterOptions",
    ],
  },

  // ==================== Configuration ====================
  config: {
    all: (connectionId) => ["config", connectionId],
    analysis: (connectionId) => ["config", connectionId, "analysis"],
  },

  // ==================== Explain Plan ====================
  explain: {
    history: (connectionId) => ["explain", connectionId, "history"],
  },

  // ==================== Saved Items ====================
  savedQueries: {
    all: (connectionId) => ["savedQueries", connectionId],
    detail: (id) => ["savedQueries", "detail", id],
    favorites: (connectionId) => ["savedQueries", connectionId, "favorites"],
    byFolder: (connectionId, folder) => [
      "savedQueries",
      connectionId,
      "folder",
      folder,
    ],
    folders: (connectionId) => ["savedQueries", connectionId, "folders"],
  },

  savedDashboards: {
    all: (connectionId) => ["savedDashboards", connectionId],
    detail: (id) => ["savedDashboards", "detail", id],
    favorites: (connectionId) => ["savedDashboards", connectionId, "favorites"],
    byFolder: (connectionId, folder) => [
      "savedDashboards",
      connectionId,
      "folder",
      folder,
    ],
    folders: (connectionId) => ["savedDashboards", connectionId, "folders"],
  },

  // ==================== Playbooks ====================
  playbooks: {
    all: (params) => ["playbooks", { ...params }],
    detail: (playbookId) => ["playbooks", playbookId],
    runHistory: (connectionId) => ["playbooks", "runs", connectionId],
    alerts: (connectionId, unacknowledgedOnly) => [
      "playbooks",
      "alerts",
      connectionId,
      { unacknowledgedOnly },
    ],
  },

  // ==================== Growth Monitoring ====================
  growth: {
    history: (connectionId, tableName, days) => [
      "growth",
      connectionId,
      "history",
      { tableName, days },
    ],
    anomalies: (connectionId, tableName, unacknowledgedOnly, days) => [
      "growth",
      connectionId,
      "anomalies",
      { tableName, unacknowledgedOnly, days },
    ],
    config: (connectionId, tableName) => [
      "growth",
      connectionId,
      "config",
      { tableName },
    ],
    trends: (connectionId, tableName, days) => [
      "growth",
      connectionId,
      "trends",
      { tableName, days },
    ],
  },

  // ==================== Sentinel Analytics ====================
  sentinel: {
    deathClock: (connectionId) => ["sentinel", connectionId, "deathClock"],
    forecasts: (connectionId, tableName) => [
      "sentinel",
      connectionId,
      "forecasts",
      { tableName },
    ],
    velocity: (connectionId, tableName) => [
      "sentinel",
      connectionId,
      "velocity",
      { tableName },
    ],
    events: (connectionId, days) => [
      "sentinel",
      connectionId,
      "events",
      { days },
    ],
    recommendations: (connectionId, status, priority) => [
      "sentinel",
      connectionId,
      "recommendations",
      { status, priority },
    ],
    summary: (connectionId) => ["sentinel", connectionId, "summary"],
  },

  // ==================== Query Performance ====================
  queryPerformance: {
    queries: (connectionId) => ["queryPerformance", connectionId, "queries"],
    history: (connectionId, queryHash, days) => [
      "queryPerformance",
      connectionId,
      "history",
      queryHash,
      { days },
    ],
    trend: (connectionId, queryHash, days) => [
      "queryPerformance",
      connectionId,
      "trend",
      queryHash,
      { days },
    ],
    regressions: (connectionId, unacknowledgedOnly) => [
      "queryPerformance",
      connectionId,
      "regressions",
      { unacknowledgedOnly },
    ],
  },

  // ==================== Resource Limits ====================
  resourceLimits: {
    all: (connectionId) => ["resourceLimits", connectionId],
  },

  // ==================== Performance Insights ====================
  performanceInsights: {
    snapshots: (connectionId, hours) => [
      "performanceInsights",
      connectionId,
      "snapshots",
      { hours },
    ],
    recent: (connectionId, limit) => [
      "performanceInsights",
      connectionId,
      "recent",
      { limit },
    ],
    summary: (connectionId, hours) => [
      "performanceInsights",
      connectionId,
      "summary",
      { hours },
    ],
    tableUsage: (connectionId) => [
      "performanceInsights",
      connectionId,
      "tableUsage",
    ],
  },

  // ==================== Advisor ====================
  advisor: {
    analysis: (connectionId) => ["advisor", connectionId, "analysis"],
  },

  // ==================== Performance Actions ====================
  performanceActions: {
    all: (connectionId) => ["performanceActions", connectionId],
    list: (connectionId) => ["performanceActions", connectionId, "list"],
    top: (connectionId, limit) => [
      "performanceActions",
      connectionId,
      "top",
      { limit },
    ],
    byCategory: (connectionId, category) => [
      "performanceActions",
      connectionId,
      "category",
      category,
    ],
    bySource: (connectionId, source) => [
      "performanceActions",
      connectionId,
      "source",
      source,
    ],
    summary: (connectionId) => ["performanceActions", connectionId, "summary"],
  },

};
