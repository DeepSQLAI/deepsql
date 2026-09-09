"use client";

import { useState, useEffect, useRef, lazy, Suspense, useMemo } from "react";
import { Panel, PanelGroup, PanelResizeHandle } from "react-resizable-panels";
import {
  Play,
  Square,
  Download,
  Copy,
  ChevronRight,
  ChevronDown,
  Table,
  Eye,
  FunctionSquare,
  FileCode,
  Search,
  Clock,
  Database,
  Columns,
  Key,
  FileText,
  X,
  Hash,
  Wand2,
  Save,
  Edit,
  Loader2,
  Zap,
  Sparkles,
  RefreshCw,
  AlertTriangle,
} from "lucide-react";
import { format } from "sql-formatter";
import styles from "./SqlRunnerTab.module.css";
import {
  queryAPI,
  queryPerformanceAPI,
  explainAPI,
  brainAPI,
} from "@/lib/api/client";
import ExplainAnalysisPanel from "./ExplainAnalysisPanel";
import QueryOptimizePanel from "./QueryOptimizePanel";
import { useAuth } from "@/hooks/useAuth";
import { useConnections } from "@/lib/hooks/queries/useConnections";
import { HelpTooltip } from "../Brain/components";
import {
  canonicalTableReference,
  objectKey,
  qualifyForSql,
  connectionHasMultipleSchemas,
} from "@/lib/schemaNames";

// Kept below the 300s proxy_read_timeout in docker/nginx/default.conf so a slow
// query fails with a real message rather than an opaque 504.
const QUERY_TIMEOUT_SECONDS = 240;

// CSV export re-runs the query with no display cap, so it needs its own bound.
// Without one, an unbounded SELECT streams every row into a JS array, a CSV
// string, and a Blob, and the equivalent unbounded read on the backend loads
// the whole result set into memory before responding — large enough result
// sets can exhaust the backend heap for every tenant on that instance, not
// just the exporter. 100k rows is generous for a CSV download while staying
// well short of that failure mode.
const EXPORT_ROW_LIMIT = 100000;

// Constants for diagram layout
const DIAGRAM_NODE_WIDTH = 240;
const DIAGRAM_NODE_HEIGHT = 120;
const DIAGRAM_LEVEL_GAP = 90;
const DIAGRAM_SIBLING_GAP = 32;

/**
 * Conservative read-only check for deciding whether EXPLAIN can safely run with
 * ANALYZE (which executes the statement). Only statements that clearly cannot
 * mutate return true: SELECT / WITH (CTE) / SHOW / EXPLAIN / TABLE / VALUES.
 * A WITH that contains a writable CTE (INSERT/UPDATE/DELETE inside) is excluded.
 * Anything we're unsure about returns false → plain EXPLAIN, no execution.
 */
const isReadOnlySql = (sql) => {
  if (!sql || typeof sql !== "string") return false;
  // Strip leading line/block comments, then look at the first keyword.
  const cleaned = sql
    .replace(/\/\*[\s\S]*?\*\//g, " ")
    .replace(/--[^\n]*/g, " ")
    .trim()
    .toLowerCase();
  if (!cleaned) return false;
  const firstWord = cleaned.match(/^[a-z]+/)?.[0];
  if (!["select", "with", "show", "explain", "table", "values"].includes(firstWord)) {
    return false;
  }
  // A CTE is only read-only if no writable statement appears inside it.
  if (firstWord === "with" && /\b(insert|update|delete|merge)\b/.test(cleaned)) {
    return false;
  }
  return true;
};

const buildDiagramLayout = (planTree, expandedNodes) => {
  if (!planTree) return null;
  const nodes = [];
  const edges = [];
  const nodeMap = new Map();
  let nextX = 0;
  let maxDepth = 0;

  const walk = (node, path, depth) => {
    const nodeId = `node-${path.join("-")}`;
    const hasChildren = node.children && node.children.length > 0;
    const isExpanded = expandedNodes[nodeId] !== false;
    const visibleChildren = hasChildren && isExpanded ? node.children : [];
    maxDepth = Math.max(maxDepth, depth);

    let x;
    if (!visibleChildren.length) {
      x = nextX + DIAGRAM_NODE_WIDTH / 2;
      nextX += DIAGRAM_NODE_WIDTH + DIAGRAM_SIBLING_GAP;
    } else {
      const childCenters = [];
      visibleChildren.forEach((child, idx) => {
        const childLayout = walk(child, [...path, idx], depth + 1);
        childCenters.push(childLayout.x);
        edges.push({ from: nodeId, to: childLayout.id });
      });
      const sum = childCenters.reduce((acc, value) => acc + value, 0);
      x = sum / childCenters.length;
    }

    const y = depth * (DIAGRAM_NODE_HEIGHT + DIAGRAM_LEVEL_GAP);
    const layoutNode = {
      id: nodeId,
      node,
      x,
      y,
      depth,
      hasChildren,
      isExpanded,
    };
    nodes.push(layoutNode);
    nodeMap.set(nodeId, layoutNode);
    return layoutNode;
  };

  walk(planTree, ["root"], 0);
  const width = Math.max(nextX, DIAGRAM_NODE_WIDTH) + DIAGRAM_SIBLING_GAP;
  const height =
    (maxDepth + 1) * (DIAGRAM_NODE_HEIGHT + DIAGRAM_LEVEL_GAP) +
    DIAGRAM_LEVEL_GAP;

  return { nodes, edges, width, height, nodeMap };
};
import SavedQueriesPanel from "@/components/SavedQueriesPanel";
import { saveTabState, loadTabState, saveSchemaCache, loadSchemaCache, clearSchemaCache } from "@/utils/tabStateCache";

// Lazy load Monaco Editor for better performance
const Editor = lazy(() => import("@monaco-editor/react"));

const SQL_STATEMENT_START = /^\s*(SELECT|INSERT|UPDATE|DELETE|WITH|CREATE|ALTER|DROP|TRUNCATE|REPLACE|EXPLAIN|SHOW|CALL|EXEC|MERGE|UPSERT)\b/i;

function stripSqlComments(sql) {
  let result = "";
  let i = 0;
  while (i < sql.length) {
    if (sql[i] === "-" && sql[i + 1] === "-") {
      const nl = sql.indexOf("\n", i);
      i = nl >= 0 ? nl + 1 : sql.length;
    } else if (sql[i] === "/" && sql[i + 1] === "*") {
      const end = sql.indexOf("*/", i + 2);
      i = end >= 0 ? end + 2 : sql.length;
    } else if (sql[i] === "'") {
      result += sql[i++];
      while (i < sql.length && sql[i] !== "'") {
        if (sql[i] === "\\" && i + 1 < sql.length) result += sql[i++];
        result += sql[i++];
      }
      if (i < sql.length) result += sql[i++];
    } else {
      result += sql[i++];
    }
  }
  return result;
}

function hasMultipleStatementsWithoutSemicolons(sql) {
  const stripped = stripSqlComments(sql);
  // Split on semicolons that are outside string literals (already handled by stripSqlComments preserving structure)
  const hasSemicolon = stripped.includes(";");
  if (hasSemicolon) return false; // User used semicolons — let the backend handle it
  // Count how many lines look like the start of a new SQL statement
  const lines = stripped.split("\n");
  let statementStarts = 0;
  for (const line of lines) {
    if (SQL_STATEMENT_START.test(line)) {
      statementStarts++;
      if (statementStarts > 1) return true;
    }
  }
  return false;
}

// Map our internal connection dbType strings to the dialect names sql-formatter understands.
// Anything we don't have a specific mapping for falls back to "mysql" because the editor's
// historical default has always been MySQL — keeps behaviour stable for unknown types.
const SQL_FORMATTER_DIALECT_BY_DB_TYPE = {
  mysql: "mysql",
  mariadb: "mariadb",
  postgres: "postgresql",
  postgresql: "postgresql",
  sqlite: "sqlite",
  mssql: "tsql",
  tsql: "tsql",
};

function resolveSqlFormatterDialect(dbType) {
  if (!dbType) return "mysql";
  return SQL_FORMATTER_DIALECT_BY_DB_TYPE[String(dbType).toLowerCase()] || "mysql";
}

// Look for a single-line "--" comment that appears to be eating closing parens. MySQL/SQLite/
// PostgreSQL "--" comments extend to end of line; on a single-line query that can swallow the
// rest of the statement, which is exactly the parse failure we see most often.
function looksLikeSingleLineDashComment(sql) {
  if (!sql) return false;
  if (/\r?\n/.test(sql)) return false;
  return /--\s/.test(sql);
}

export default function SqlRunnerTab({ connectionId }) {
  const { isAdmin, username } = useAuth();
  const { data: connectionsData } = useConnections();
  const currentConnection = (Array.isArray(connectionsData) ? connectionsData : []).find(
    (c) => c?.id === connectionId
  );
  // Initialize with cached state if available
  const getInitialState = () => {
    if (!connectionId) {
      return {
        query:
          "-- Click a table in the explorer to generate a query\n-- Or write your own SQL query here\n\nSELECT 1 as test;",
        queryHistory: [],
        expandedNodes: { tables: true },
        searchTerm: "",
        selectedTable: null,
        leftPanelView: "explorer",
      };
    }
    const cached = loadTabState(connectionId, "code");
    return {
      query:
        cached?.query ||
        "-- Click a table in the explorer to generate a query\n-- Or write your own SQL query here\n\nSELECT 1 as test;",
      results: cached?.results || null,
      queryHistory: cached?.queryHistory || [],
      expandedNodes: cached?.expandedNodes || { tables: true },
      searchTerm: cached?.searchTerm || "",
      selectedTable: cached?.selectedTable || null,
      leftPanelView: cached?.leftPanelView || "explorer",
    };
  };

  const initialState = getInitialState();

  const [query, setQuery] = useState(initialState.query);
  const [results, setResults] = useState(initialState.results);
  const [isRunning, setIsRunning] = useState(false);
  const [isExporting, setIsExporting] = useState(false);
  const [isExplaining, setIsExplaining] = useState(false);
  const [explainResults, setExplainResults] = useState(null);
  const [isOptimizing, setIsOptimizing] = useState(false);
  const [optimizeResult, setOptimizeResult] = useState(null);
  const [error, setError] = useState(null);
  // Format-related notices (warning/info) live in their own state so a formatter parse error
  // never bleeds into the Results panel as a giant BNF trace.
  const [formatNotice, setFormatNotice] = useState(null);
  const [databaseObjects, setDatabaseObjects] = useState(() => loadSchemaCache(connectionId, username) || []);
  const [loadingObjects, setLoadingObjects] = useState(false);
  const [refreshingSchema, setRefreshingSchema] = useState(false);
  const [expandedNodes, setExpandedNodes] = useState(
    initialState.expandedNodes,
  );
  const [searchTerm, setSearchTerm] = useState(initialState.searchTerm);
  const [queryHistory, setQueryHistory] = useState(initialState.queryHistory);
  const [selectedTable, setSelectedTable] = useState(
    initialState.selectedTable,
  );
  const [contextMenu, setContextMenu] = useState({
    visible: false,
    x: 0,
    y: 0,
    table: null,
  });
  const [showDetailModal, setShowDetailModal] = useState(false);
  const [detailTable, setDetailTable] = useState(null);
  const [detailTab, setDetailTab] = useState("columns");
  const [tableIndexes, setTableIndexes] = useState([]);
  const [loadingIndexes, setLoadingIndexes] = useState(false);
  const [showSaveQueryModal, setShowSaveQueryModal] = useState(false);
  const [saveQueryData, setSaveQueryData] = useState(null);
  const [leftPanelView, setLeftPanelView] = useState(
    initialState.leftPanelView,
  ); // 'explorer', 'tables', 'views', 'functions', 'procedures'
  const [showSqlSubmenu, setShowSqlSubmenu] = useState(false);
  const [pendingMutationConfirmation, setPendingMutationConfirmation] = useState(null);
  const editorRef = useRef(null);
  const monacoRef = useRef(null);
  const autocompleteRegisteredRef = useRef(false);
  const dbObjectsRef = useRef([]);
  const abortControllerRef = useRef(null);
  const executionIdRef = useRef(null);
  // Guards against a second run starting while one is already in flight (e.g. a
  // held-down Cmd+Enter): without it, whichever response lands last wins the
  // results panel regardless of which run the user actually meant to see last,
  // and the earlier run's abort/cancel handle gets silently discarded.
  const isRunningRef = useRef(false);
  const runSeqRef = useRef(0);
  // Note: savedQueriesPanelRef kept for potential future use, but panel UI is now in modal
  const savedQueriesPanelRef = useRef(null);
  const hasRowCount = (value) => value !== null && value !== undefined;

  // Update ref when database objects change
  useEffect(() => {
    dbObjectsRef.current = databaseObjects;
  }, [databaseObjects]);

  // Load cached state when connection changes
  useEffect(() => {
    if (connectionId) {
      const cached = loadTabState(connectionId, "code");
      if (cached) {
        if (cached.query) {
          setQuery(cached.query);
          // Push into editor directly so cursor / content is correct
          if (editorRef.current) editorRef.current.setValue(cached.query);
        }
        if (cached.results) setResults(cached.results);
        if (cached.queryHistory) setQueryHistory(cached.queryHistory);
        if (cached.expandedNodes) setExpandedNodes(cached.expandedNodes);
        if (cached.searchTerm !== undefined) setSearchTerm(cached.searchTerm);
        if (cached.selectedTable !== undefined)
          setSelectedTable(cached.selectedTable);
        if (cached.leftPanelView) setLeftPanelView(cached.leftPanelView);
      }
      autocompleteRegisteredRef.current = false; // Reset when connection changes

      // Restore schema from localStorage cache for instant display, then
      // refresh in background. Only show the loading spinner on a cold start
      // (no cached objects at all).
      const cachedSchema = loadSchemaCache(connectionId, username);
      if (cachedSchema && cachedSchema.length > 0) {
        dbObjectsRef.current = cachedSchema;
        setDatabaseObjects(cachedSchema);
        // Background refresh — no spinner since data is already visible
        fetchDatabaseObjects({ showSpinner: false });
      } else {
        dbObjectsRef.current = [];
        setDatabaseObjects([]);
        fetchDatabaseObjects({ showSpinner: true });
      }
    }
  }, [connectionId, username]);

  // Save state to cache whenever important state changes
  useEffect(() => {
    if (connectionId) {
      const stateToSave = {
        query,
        results,
        queryHistory,
        expandedNodes,
        searchTerm,
        selectedTable,
        leftPanelView,
      };
      saveTabState(connectionId, "code", stateToSave);
    }
  }, [
    connectionId,
    query,
    results,
    queryHistory,
    expandedNodes,
    searchTerm,
    selectedTable,
    leftPanelView,
  ]);

  // Register autocomplete provider when database objects or Monaco instance is available
  useEffect(() => {
    if (
      monacoRef.current &&
      databaseObjects.length > 0 &&
      !autocompleteRegisteredRef.current
    ) {
      registerAutocompleteProvider(monacoRef.current);
      autocompleteRegisteredRef.current = true;
    }
  }, [databaseObjects]);

  // Close context menu on click outside
  useEffect(() => {
    const handleClick = () => {
      setContextMenu({ visible: false, x: 0, y: 0, table: null });
      setShowSqlSubmenu(false);
    };
    if (contextMenu.visible) {
      document.addEventListener("click", handleClick);
      return () => document.removeEventListener("click", handleClick);
    }
  }, [contextMenu.visible]);

  const fetchDatabaseObjects = async ({ showSpinner = true } = {}) => {
    // Only clear the spinner in finally if this call was the one that set it.
    // Background fetches (showSpinner=false) must never touch the flag so they
    // cannot prematurely hide a spinner owned by a concurrent foreground fetch.
    if (showSpinner && dbObjectsRef.current.length === 0) setLoadingObjects(true);
    try {
      const response = await queryAPI.getDatabaseObjects(connectionId);
      if (response.success) {
        setDatabaseObjects(response.objects);
        saveSchemaCache(connectionId, response.objects, username);
      }
    } catch (err) {
      console.error("Failed to fetch database objects:", err);
    } finally {
      if (showSpinner) setLoadingObjects(false);
    }
  };

  const handleRefreshSchema = async () => {
    if (!connectionId) return;

    setRefreshingSchema(true);
    setError(null);

    brainAPI
      .rescanSchema(connectionId)
      .then(async () => {
        clearSchemaCache(connectionId, username);
        await fetchDatabaseObjects({ showSpinner: false });
      })
      .catch((err) => {
        console.error("Failed to refresh schema:", err);
        setError(
          "Failed to refresh schema: " +
            (err.response?.data?.message || err.message),
        );
      })
      .finally(() => {
        setRefreshingSchema(false);
      });
  };

  // Register Monaco Editor autocomplete provider for SQL
  const registerAutocompleteProvider = (monaco) => {
    try {
      monaco.languages.registerCompletionItemProvider("sql", {
        provideCompletionItems: (model, position) => {
          // Use ref to get latest database objects
          const dbObjects = dbObjectsRef.current || [];

          const textUntilPosition = model.getValueInRange({
            startLineNumber: 1,
            startColumn: 1,
            endLineNumber: position.lineNumber,
            endColumn: position.column,
          });

          const word = model.getWordUntilPosition(position);
          const range = {
            startLineNumber: position.lineNumber,
            endColumn: position.column,
            endLineNumber: position.lineNumber,
            startColumn: word.startColumn,
          };

          // Detect SQL context for smarter suggestions
          const detectSQLContext = (text) => {
            const textLower = text.toLowerCase();
            const lastSelectIndex = textLower.lastIndexOf("select");
            const lastFromIndex = textLower.lastIndexOf("from");
            const lastWhereIndex = textLower.lastIndexOf("where");
            const lastJoinIndex = Math.max(
              textLower.lastIndexOf("join"),
              textLower.lastIndexOf("inner join"),
              textLower.lastIndexOf("left join"),
              textLower.lastIndexOf("right join"),
            );
            const lastGroupByIndex = textLower.lastIndexOf("group by");
            const lastOrderByIndex = textLower.lastIndexOf("order by");
            const lastHavingIndex = textLower.lastIndexOf("having");

            // Find which clause we're currently in
            const maxIndex = Math.max(
              lastSelectIndex,
              lastFromIndex,
              lastWhereIndex,
              lastJoinIndex,
              lastGroupByIndex,
              lastOrderByIndex,
              lastHavingIndex,
            );

            if (maxIndex === lastSelectIndex) return "SELECT";
            if (maxIndex === lastFromIndex) return "FROM";
            if (maxIndex === lastWhereIndex) return "WHERE";
            if (maxIndex === lastJoinIndex) return "JOIN";
            if (maxIndex === lastGroupByIndex) return "GROUP_BY";
            if (maxIndex === lastOrderByIndex) return "ORDER_BY";
            if (maxIndex === lastHavingIndex) return "HAVING";

            return "SELECT"; // Default context
          };

          const currentContext = detectSQLContext(textUntilPosition);

          // Fuzzy matching helper
          const fuzzyMatch = (input, target) => {
            if (!input) return { matches: true, score: 0 };

            const inputLower = input.toLowerCase();
            const targetLower = target.toLowerCase();

            // Exact match gets highest score
            if (targetLower === inputLower)
              return { matches: true, score: 100 };

            // Starts with gets high score
            if (targetLower.startsWith(inputLower))
              return { matches: true, score: 90 };

            // Contains gets medium score
            if (targetLower.includes(inputLower))
              return { matches: true, score: 70 };

            // Fuzzy match - check if all characters appear in order
            let inputIndex = 0;
            let lastMatchIndex = -1;
            let consecutiveMatches = 0;
            let score = 50;

            for (
              let i = 0;
              i < targetLower.length && inputIndex < inputLower.length;
              i++
            ) {
              if (targetLower[i] === inputLower[inputIndex]) {
                // Boost score for consecutive matches
                if (i === lastMatchIndex + 1) {
                  consecutiveMatches++;
                  score += 2;
                } else {
                  consecutiveMatches = 0;
                }
                lastMatchIndex = i;
                inputIndex++;
              }
            }

            // All characters matched
            if (inputIndex === inputLower.length) {
              // Boost score based on how early matches appeared
              const matchDensity = inputLower.length / (lastMatchIndex + 1);
              score += Math.floor(matchDensity * 20);
              return { matches: true, score: Math.min(score, 95) };
            }

            return { matches: false, score: 0 };
          };

          const suggestions = [];

          // Helper to get priority based on context
          const getPriority = (itemType) => {
            const priorities = {
              SELECT: { column: "1", function: "2", table: "5", keyword: "6" },
              FROM: {
                table: "1",
                view: "2",
                column: "8",
                function: "8",
                keyword: "5",
              },
              WHERE: { column: "1", function: "3", table: "7", keyword: "4" },
              HAVING: { column: "1", function: "3", table: "7", keyword: "4" },
              JOIN: {
                table: "1",
                view: "2",
                column: "7",
                function: "8",
                keyword: "3",
              },
              GROUP_BY: {
                column: "1",
                table: "7",
                function: "8",
                keyword: "5",
              },
              ORDER_BY: {
                column: "1",
                table: "7",
                function: "8",
                keyword: "5",
              },
            };
            return priorities[currentContext]?.[itemType] || "5";
          };

          // Get current word being typed for fuzzy matching
          const currentWord = word.word || "";

          // Get all table names with fuzzy matching
          const tables = dbObjects.filter((obj) => obj.type === "table");
          tables.forEach((table) => {
            const tableRef = canonicalTableReference(table);
            const matchRef = fuzzyMatch(currentWord, tableRef);
            const matchBare = fuzzyMatch(currentWord, table.name);
            const fuzzyResult = matchRef.matches ? matchRef : matchBare;
            if (fuzzyResult.matches) {
              const fuzzyBoost = String(100 - fuzzyResult.score).padStart(
                3,
                "0",
              );
              suggestions.push({
                label: tableRef,
                kind: monaco.languages.CompletionItemKind.Class,
                insertText: qualifyForSql(table),
                range: range,
                detail: table.schema && table.schema !== "public" ? `Table · ${table.schema}` : "Table",
                documentation: table.columns
                  ? `${table.columns.length} columns`
                  : "Table",
                sortText: getPriority("table") + fuzzyBoost + tableRef,
              });
            }
          });

          // Get all view names with fuzzy matching
          const views = dbObjects.filter((obj) => obj.type === "view");
          views.forEach((view) => {
            const viewRef = canonicalTableReference(view);
            const matchRef = fuzzyMatch(currentWord, viewRef);
            const matchBare = fuzzyMatch(currentWord, view.name);
            const fuzzyResult = matchRef.matches ? matchRef : matchBare;
            if (fuzzyResult.matches) {
              const fuzzyBoost = String(100 - fuzzyResult.score).padStart(
                3,
                "0",
              );
              suggestions.push({
                label: viewRef,
                kind: monaco.languages.CompletionItemKind.View,
                insertText: qualifyForSql(view),
                range: range,
                detail: view.schema && view.schema !== "public" ? `View · ${view.schema}` : "View",
                documentation: "Database view",
                sortText: getPriority("view") + fuzzyBoost + viewRef,
              });
            }
          });

          // Detect table aliases (e.g., "FROM users u", "FROM users AS u")
          const aliasPattern =
            /(?:FROM|JOIN)\s+((?:\w+\.)?\w+)(?:\s+AS\s+(\w+)|\s+(\w+)(?=\s|,|WHERE|JOIN|GROUP|ORDER|LIMIT|$))/gi;
          const aliases = {};
          let aliasMatch;

          // Build alias map: { 'u': 'users', 'o': 'orders' }
          while ((aliasMatch = aliasPattern.exec(textUntilPosition)) !== null) {
            const tableName = aliasMatch[1];
            const alias = aliasMatch[2] || aliasMatch[3];
            if (
              alias &&
              alias.toLowerCase() !== "on" &&
              alias.toLowerCase() !== "where"
            ) {
              aliases[alias.toLowerCase()] = tableName;
            }
          }

          // Check if user is typing an alias followed by a dot (e.g., "u.")
          const aliasDotPattern = /(\w+)\.\s*$/i;
          const aliasDotMatch = textUntilPosition.match(aliasDotPattern);

          if (aliasDotMatch) {
            const possibleAlias = aliasDotMatch[1];
            const actualTableName = aliases[possibleAlias.toLowerCase()];

            if (actualTableName) {
              // User typed an alias - suggest columns from that table
              const table = tables.find((t) => {
                const ref = canonicalTableReference(t).toLowerCase();
                const bare = (t.name || "").toLowerCase();
                const wanted = actualTableName.toLowerCase();
                return ref === wanted || bare === wanted || qualifyForSql(t).toLowerCase() === wanted;
              });
              if (table && table.columns) {
                table.columns.forEach((column) => {
                  const fuzzyResult = fuzzyMatch(currentWord, column.name);
                  if (fuzzyResult.matches) {
                    const fuzzyBoost = String(100 - fuzzyResult.score).padStart(
                      3,
                      "0",
                    );
                    suggestions.push({
                      label: column.name,
                      kind: column.primaryKey
                        ? monaco.languages.CompletionItemKind.Keyword
                        : monaco.languages.CompletionItemKind.Field,
                      insertText: column.name,
                      range: range,
                      detail: `${possibleAlias}.${column.name} (${canonicalTableReference(table)})`,
                      documentation: `${column.dataType}${column.nullable ? " (nullable)" : " (not null)"}${column.primaryKey ? " [PK]" : ""}`,
                      sortText:
                        getPriority("column") + fuzzyBoost + column.name,
                    });
                  }
                });
              }
            } else {
              // Not an alias - check if it's a direct table / schema.table name
              const table = tables.find((t) => {
                const ref = canonicalTableReference(t).toLowerCase();
                const bare = (t.name || "").toLowerCase();
                const wanted = possibleAlias.toLowerCase();
                return ref === wanted || bare === wanted;
              });
              if (table && table.columns) {
                table.columns.forEach((column) => {
                  const fuzzyResult = fuzzyMatch(currentWord, column.name);
                  if (fuzzyResult.matches) {
                    const fuzzyBoost = String(100 - fuzzyResult.score).padStart(
                      3,
                      "0",
                    );
                    suggestions.push({
                      label: column.name,
                      kind: column.primaryKey
                        ? monaco.languages.CompletionItemKind.Keyword
                        : monaco.languages.CompletionItemKind.Field,
                      insertText: column.name,
                      range: range,
                      detail: `${canonicalTableReference(table)}.${column.name}`,
                      documentation: `${column.dataType}${column.nullable ? " (nullable)" : " (not null)"}${column.primaryKey ? " [PK]" : ""}`,
                      sortText:
                        getPriority("column") + fuzzyBoost + column.name,
                    });
                  }
                });
              }
            }
          } else {
            // No alias/table dot - suggest all columns from all tables with qualified names
            tables.forEach((table) => {
              if (table.columns) {
                table.columns.forEach((column) => {
                  const fullName = `${canonicalTableReference(table)}.${column.name}`;
                  const fuzzyResult = fuzzyMatch(currentWord, fullName);
                  if (fuzzyResult.matches) {
                    const fuzzyBoost = String(100 - fuzzyResult.score).padStart(
                      3,
                      "0",
                    );
                    suggestions.push({
                      label: fullName,
                      kind: column.primaryKey
                        ? monaco.languages.CompletionItemKind.Keyword
                        : monaco.languages.CompletionItemKind.Field,
                      insertText: fullName,
                      range: range,
                      detail: fullName,
                      documentation: `${column.dataType}${column.nullable ? " (nullable)" : " (not null)"}${column.primaryKey ? " [PK]" : ""}`,
                      sortText: getPriority("column") + fuzzyBoost + fullName,
                    });
                  }
                });
              }
            });
          }

          // SQL Functions with documentation
          const sqlFunctions = [
            // String Functions
            {
              name: "CONCAT",
              detail: "String",
              doc: "CONCAT(str1, str2, ...) - Concatenate strings together",
            },
            {
              name: "SUBSTRING",
              detail: "String",
              doc: "SUBSTRING(str, pos, len) - Extract substring from string",
            },
            {
              name: "UPPER",
              detail: "String",
              doc: "UPPER(str) - Convert string to uppercase",
            },
            {
              name: "LOWER",
              detail: "String",
              doc: "LOWER(str) - Convert string to lowercase",
            },
            {
              name: "TRIM",
              detail: "String",
              doc: "TRIM(str) - Remove leading and trailing spaces",
            },
            {
              name: "LTRIM",
              detail: "String",
              doc: "LTRIM(str) - Remove leading spaces",
            },
            {
              name: "RTRIM",
              detail: "String",
              doc: "RTRIM(str) - Remove trailing spaces",
            },
            {
              name: "LENGTH",
              detail: "String",
              doc: "LENGTH(str) - Get string length",
            },
            {
              name: "REPLACE",
              detail: "String",
              doc: "REPLACE(str, from, to) - Replace substring in string",
            },
            {
              name: "LEFT",
              detail: "String",
              doc: "LEFT(str, len) - Get leftmost characters",
            },
            {
              name: "RIGHT",
              detail: "String",
              doc: "RIGHT(str, len) - Get rightmost characters",
            },
            {
              name: "LPAD",
              detail: "String",
              doc: "LPAD(str, len, pad) - Left-pad string to length",
            },
            {
              name: "RPAD",
              detail: "String",
              doc: "RPAD(str, len, pad) - Right-pad string to length",
            },

            // Aggregate Functions
            {
              name: "COUNT",
              detail: "Aggregate",
              doc: "COUNT(*) or COUNT(column) - Count rows or non-null values",
            },
            {
              name: "SUM",
              detail: "Aggregate",
              doc: "SUM(column) - Sum of numeric values",
            },
            {
              name: "AVG",
              detail: "Aggregate",
              doc: "AVG(column) - Average of numeric values",
            },
            {
              name: "MIN",
              detail: "Aggregate",
              doc: "MIN(column) - Minimum value",
            },
            {
              name: "MAX",
              detail: "Aggregate",
              doc: "MAX(column) - Maximum value",
            },
            {
              name: "GROUP_CONCAT",
              detail: "Aggregate",
              doc: "GROUP_CONCAT(column) - Concatenate group values",
            },

            // Date/Time Functions
            {
              name: "NOW",
              detail: "Date/Time",
              doc: "NOW() - Current date and time",
            },
            {
              name: "CURDATE",
              detail: "Date/Time",
              doc: "CURDATE() - Current date",
            },
            {
              name: "CURTIME",
              detail: "Date/Time",
              doc: "CURTIME() - Current time",
            },
            {
              name: "DATE",
              detail: "Date/Time",
              doc: "DATE(datetime) - Extract date part",
            },
            {
              name: "TIME",
              detail: "Date/Time",
              doc: "TIME(datetime) - Extract time part",
            },
            {
              name: "YEAR",
              detail: "Date/Time",
              doc: "YEAR(date) - Extract year",
            },
            {
              name: "MONTH",
              detail: "Date/Time",
              doc: "MONTH(date) - Extract month (1-12)",
            },
            {
              name: "DAY",
              detail: "Date/Time",
              doc: "DAY(date) - Extract day of month",
            },
            {
              name: "HOUR",
              detail: "Date/Time",
              doc: "HOUR(time) - Extract hour",
            },
            {
              name: "MINUTE",
              detail: "Date/Time",
              doc: "MINUTE(time) - Extract minute",
            },
            {
              name: "SECOND",
              detail: "Date/Time",
              doc: "SECOND(time) - Extract second",
            },
            {
              name: "DATE_FORMAT",
              detail: "Date/Time",
              doc: "DATE_FORMAT(date, format) - Format date as string",
            },
            {
              name: "DATE_ADD",
              detail: "Date/Time",
              doc: "DATE_ADD(date, INTERVAL n unit) - Add interval to date",
            },
            {
              name: "DATE_SUB",
              detail: "Date/Time",
              doc: "DATE_SUB(date, INTERVAL n unit) - Subtract interval from date",
            },
            {
              name: "DATEDIFF",
              detail: "Date/Time",
              doc: "DATEDIFF(date1, date2) - Days between dates",
            },

            // Mathematical Functions
            { name: "ABS", detail: "Math", doc: "ABS(n) - Absolute value" },
            {
              name: "ROUND",
              detail: "Math",
              doc: "ROUND(n, decimals) - Round to decimal places",
            },
            {
              name: "CEIL",
              detail: "Math",
              doc: "CEIL(n) - Round up to nearest integer",
            },
            {
              name: "FLOOR",
              detail: "Math",
              doc: "FLOOR(n) - Round down to nearest integer",
            },
            {
              name: "POWER",
              detail: "Math",
              doc: "POWER(base, exponent) - Raise to power",
            },
            { name: "SQRT", detail: "Math", doc: "SQRT(n) - Square root" },
            {
              name: "MOD",
              detail: "Math",
              doc: "MOD(n, m) - Modulo (remainder of division)",
            },
            {
              name: "RAND",
              detail: "Math",
              doc: "RAND() - Random number between 0 and 1",
            },

            // Conditional Functions
            {
              name: "IF",
              detail: "Conditional",
              doc: "IF(condition, true_value, false_value) - Conditional expression",
            },
            {
              name: "IFNULL",
              detail: "Conditional",
              doc: "IFNULL(expr, alt_value) - Return alt_value if expr is NULL",
            },
            {
              name: "COALESCE",
              detail: "Conditional",
              doc: "COALESCE(val1, val2, ...) - Return first non-NULL value",
            },
            {
              name: "NULLIF",
              detail: "Conditional",
              doc: "NULLIF(expr1, expr2) - Return NULL if equal, else expr1",
            },

            // Type Conversion Functions
            {
              name: "CAST",
              detail: "Conversion",
              doc: "CAST(expr AS type) - Convert expression to type",
            },
            {
              name: "CONVERT",
              detail: "Conversion",
              doc: "CONVERT(expr, type) - Convert expression to type",
            },
          ];

          sqlFunctions.forEach((func) => {
            suggestions.push({
              label: func.name,
              kind: monaco.languages.CompletionItemKind.Function,
              insertText: func.name,
              range: range,
              detail: func.detail,
              documentation: func.doc,
              sortText: getPriority("function") + func.name,
            });
          });

          // Add SQL keywords (basic ones that might not be covered)
          const sqlKeywords = [
            "SELECT",
            "FROM",
            "WHERE",
            "JOIN",
            "INNER JOIN",
            "LEFT JOIN",
            "RIGHT JOIN",
            "FULL JOIN",
            "ON",
            "GROUP BY",
            "ORDER BY",
            "HAVING",
            "LIMIT",
            "OFFSET",
            "INSERT",
            "INTO",
            "VALUES",
            "UPDATE",
            "SET",
            "DELETE",
            "CREATE",
            "ALTER",
            "DROP",
            "TABLE",
            "INDEX",
            "VIEW",
            "AND",
            "OR",
            "NOT",
            "IN",
            "LIKE",
            "BETWEEN",
            "IS NULL",
            "IS NOT NULL",
            "DISTINCT",
            "AS",
            "ASC",
            "DESC",
            "UNION",
            "UNION ALL",
            "EXISTS",
            "CASE",
            "WHEN",
            "THEN",
            "ELSE",
            "END",
            "PRIMARY KEY",
            "FOREIGN KEY",
            "UNIQUE",
            "DEFAULT",
            "AUTO_INCREMENT",
          ];

          sqlKeywords.forEach((keyword) => {
            suggestions.push({
              label: keyword,
              kind: monaco.languages.CompletionItemKind.Keyword,
              insertText: keyword,
              range: range,
              detail: "SQL Keyword",
              sortText: getPriority("keyword") + keyword,
            });
          });

          return { suggestions };
        },
        triggerCharacters: [".", " "],
      });
    } catch (error) {
      console.warn("Error registering autocomplete provider:", error);
    }
  };

  const handleExplainQuery = async () => {
    let queryText = null;

    // Get query from editor
    if (editorRef.current) {
      try {
        queryText = editorRef.current.getValue();
        if (queryText !== query) {
          setQuery(queryText);
        }
      } catch (e) {
        console.error("Error reading from editor:", e);
        queryText = query || "";
      }
    } else {
      queryText = query || "";
    }

    if (typeof queryText !== "string") {
      queryText = String(queryText || "");
    }

    const trimmedQuery = queryText.trim();

    if (!trimmedQuery || trimmedQuery.length === 0) {
      setError("Please enter a SQL query to explain");
      return;
    }

    if (!connectionId) {
      setError("Please select a database connection first");
      return;
    }

    setIsExplaining(true);
    setError(null);
    setExplainResults(null);
    setOptimizeResult(null);
    setResults(null); // Clear results when explaining

    try {
      // Run the actual plan (EXPLAIN ANALYZE) for read-only statements so the
      // analysis reflects real rows/timings; fall back to an estimated plan
      // (plain EXPLAIN) for anything that could mutate, so we never execute a
      // write here. The backend mutation gate is the backstop if this misses.
      const useAnalyze = isReadOnlySql(trimmedQuery);
      const data = await explainAPI.analyzeQuery(
        connectionId,
        trimmedQuery,
        useAnalyze,
      );
      setExplainResults(data);
    } catch (err) {
      setError(err.message || "Failed to explain query");
    } finally {
      setIsExplaining(false);
    }
  };

  const handleOptimizeQuery = async () => {
    let queryText = query || "";
    try {
      if (editorRef.current) queryText = editorRef.current.getValue() || queryText;
    } catch {
      /* fall back to state */
    }
    const trimmedQuery = queryText.trim();
    if (!trimmedQuery) {
      setError("Please enter a SQL query to optimize");
      return;
    }
    if (!connectionId) {
      setError("Please select a database connection first");
      return;
    }
    // AI rewrite runs EXPLAIN ANALYZE under the hood — only for read-only SQL.
    if (!isReadOnlySql(trimmedQuery)) {
      setError("AI rewrite is available for read-only (SELECT) queries.");
      return;
    }

    setIsOptimizing(true);
    setError(null);
    setOptimizeResult(null);
    setExplainResults(null);
    setResults(null);

    try {
      const data = await explainAPI.optimizeQuery(connectionId, trimmedQuery);
      setOptimizeResult({ ...data, originalQuery: trimmedQuery });
    } catch (err) {
      setError(err.response?.data?.message || err.message || "Failed to optimize query");
    } finally {
      setIsOptimizing(false);
    }
  };

  const handleRunQuery = async (queryToRun = null, options = {}) => {
    if (isRunningRef.current) {
      return;
    }
    const mutationConfirmed = options.mutationConfirmed === true;
    let queryText = null;

    // If explicit query provided (e.g., from preview), use it
    if (queryToRun && typeof queryToRun === "string" && queryToRun.trim()) {
      queryText = queryToRun;
    }
    // Otherwise, prefer selected text (standard SQL editor behavior: run selection if present)
    else if (editorRef.current) {
      try {
        const selection = editorRef.current.getSelection();
        const selectedText =
          selection && !selection.isEmpty()
            ? editorRef.current.getModel()?.getValueInRange(selection)
            : null;
        queryText =
          selectedText?.trim() || editorRef.current.getValue();
        // Update state to keep it in sync for UI
        if (queryText !== query) {
          setQuery(queryText);
        }
      } catch (e) {
        console.error("Error reading from editor:", e);
        queryText = query || "";
      }
    }
    // Fall back to state if editor ref not available
    else {
      queryText = query || "";
    }

    // Ensure it's a string
    if (typeof queryText !== "string") {
      queryText = String(queryText || "");
    }

    const trimmedQuery = queryText.trim();

    // Basic validation - just check if there's any non-whitespace content
    if (!trimmedQuery || trimmedQuery.length === 0) {
      setError("Please enter a SQL query");
      return;
    }

    if (!connectionId) {
      setError("Please select a database connection first");
      return;
    }

    // Detect multiple SQL statements without semicolons — standard editors require
    // semicolons to separate statements; without them the batch is unparseable.
    if (hasMultipleStatementsWithoutSemicolons(trimmedQuery)) {
      setError(
        "Multiple statements detected without semicolons. Add a semicolon (;) after each statement, or select just the one you want to run and press Run."
      );
      return;
    }

    isRunningRef.current = true;
    setIsRunning(true);
    setError(null);
    setResults(null);
    setExplainResults(null); // Clear explain results when running query
    setOptimizeResult(null);

    const seq = ++runSeqRef.current;
    const abortController = new AbortController();
    abortControllerRef.current = abortController;
    // Identifies this run so cancelling can terminate it on the database.
    const executionId =
      globalThis.crypto?.randomUUID?.() ??
      `exec-${Date.now()}-${Math.random().toString(16).slice(2)}`;
    executionIdRef.current = executionId;

    try {
      // The server default of 30s is too short for analytical queries, but the
      // nginx proxy in front of the API gives up at 300s (docker/nginx/default.conf).
      // Staying under that means a slow query surfaces as a real error here
      // instead of an opaque gateway timeout.
      const response = await queryAPI.executeQuery(
        connectionId,
        trimmedQuery,
        1000,
        QUERY_TIMEOUT_SECONDS,
        abortController.signal,
        {
          executionOrigin: "EDITOR",
          mutationConfirmed,
          executionId,
        },
      );

      if (seq !== runSeqRef.current) {
        // Superseded by a later run — drop this response rather than let a
        // stale result overwrite what the user is now looking at.
        return;
      }

      if (response.success) {
        setPendingMutationConfirmation(null);
        const DISPLAY_LIMIT = 1000;
        const result = response.result;
        // Track on the client whether we capped results at the display limit.
        // rowCount === DISPLAY_LIMIT means we hit the cap (there may be more rows).
        const clientIsLimited = result.rowCount === DISPLAY_LIMIT;
        setResults({
          ...result,
          // Prefer backend-computed values; fall back to client-side detection
          isLimited: result.isLimited ?? clientIsLimited,
          query: result.query || trimmedQuery,
        });

        // Add to query history
        setQueryHistory((prev) => [
          {
            query: trimmedQuery,
            timestamp: new Date().toISOString(),
            rowCount: result.rowCount,
            executionTime: result.executionTimeMs,
          },
          ...prev.slice(0, 19),
        ]); // Keep last 20

        // Record query performance (non-blocking)
        queryPerformanceAPI
          .recordQueryExecution({
            connectionId: connectionId,
            queryText: trimmedQuery,
            executionTimeMs: response.result.executionTimeMs,
            rowsExamined: null, // Not available from query executor
            rowsSent: response.result.rowCount,
            databaseName: null, // Not available in this context
          })
          .catch((err) => {
            // Silently fail - don't interrupt user experience
            console.warn("Failed to record query performance:", err);
          });
      } else if (response.requiresConfirmation) {
        setPendingMutationConfirmation({
          query: trimmedQuery,
          queryType: response.queryType || "MUTATION",
          message:
            response.message || "This statement will modify the database.",
          warnings: Array.isArray(response.warnings)
            ? response.warnings
            : [],
        });
      } else {
        setError(response.message || "Query execution failed");
      }
    } catch (err) {
      if (err.name === "CanceledError" || err.name === "AbortError" || err.code === "ERR_CANCELED") {
        // User cancelled — clear any stale error, don't show a new one
        setError(null);
      } else if (err.requiresConfirmation) {
        const responseData = err.responseData || {};
        setPendingMutationConfirmation({
          query: trimmedQuery,
          queryType: responseData.queryType || "MUTATION",
          message:
            err.message || "This statement will modify the database.",
          warnings: Array.isArray(responseData.warnings)
            ? responseData.warnings
            : [],
        });
      } else {
        setError(err.message || "Failed to execute query");
      }
    } finally {
      if (seq === runSeqRef.current) {
        abortControllerRef.current = null;
        executionIdRef.current = null;
        isRunningRef.current = false;
        setIsRunning(false);
      }
    }
  };

  const handleStopQuery = () => {
    const executionId = executionIdRef.current;
    if (abortControllerRef.current) {
      abortControllerRef.current.abort();
    }
    isRunningRef.current = false;
    setIsRunning(false);
    setError(null);
    // Aborting above only drops the HTTP response; the statement keeps running
    // and holds a pooled connection. Ask the server to terminate this specific
    // execution. Previously this killed every active query on the connection,
    // which could take out other users' work and DeepSQL's own background jobs.
    if (connectionId && executionId) {
      executionIdRef.current = null;
      queryAPI.cancelQuery(connectionId, executionId).catch(() => {});
    }
  };

  const buildCsv = (columns, rows) => {
    return [
      columns.join(","),
      ...rows.map((row) =>
        row
          .map((cell) => {
            const cellStr = cell?.toString() || "";
            return cellStr.includes(",") || cellStr.includes('"') || cellStr.includes("\n")
              ? `"${cellStr.replace(/"/g, '""')}"`
              : cellStr;
          })
          .join(","),
      ),
    ].join("\n");
  };

  const triggerCsvDownload = (csv) => {
    const blob = new Blob([csv], { type: "text/csv" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `query-results-${new Date().toISOString().split("T")[0]}.csv`;
    a.click();
    URL.revokeObjectURL(url);
  };

  const handleExportResults = async () => {
    if (!results) return;
    // Export re-runs the query, so it must respect the same in-flight guard as
    // Run. Today `handleRunQuery` clears `results`, which hides the Export
    // button for the duration of a run and makes this unreachable — but that is
    // an incidental consequence of unrelated state handling, not a guarantee.
    // Without this check, any change that keeps results on screen during a run
    // silently reintroduces two concurrent queries from one tab.
    if (isRunningRef.current || isExporting) return;

    // If the result was limited, always re-fetch for download so the CSV isn't
    // just the displayed page — but still capped at EXPORT_ROW_LIMIT, not
    // unbounded, and on the same timeout budget as Run so it fails with a real
    // error instead of an opaque 504 from the nginx proxy.
    if (results.isLimited && results.query) {
      setIsExporting(true);
      // Strip trailing semicolon so backends don't reject the re-executed query
      const queryForExport = results.query.trim().replace(/;+$/, "");
      const exportAbortController = new AbortController();
      const exportExecutionId =
        globalThis.crypto?.randomUUID?.() ??
        `exec-${Date.now()}-${Math.random().toString(16).slice(2)}`;
      try {
        const response = await queryAPI.executeQuery(
          connectionId,
          queryForExport,
          EXPORT_ROW_LIMIT,
          QUERY_TIMEOUT_SECONDS,
          exportAbortController.signal,
          {
            executionOrigin: "EDITOR",
            mutationConfirmed: false,
            executionId: exportExecutionId,
          },
        );
        if (response.success) {
          triggerCsvDownload(buildCsv(response.result.columns, response.result.rows));
        } else {
          triggerCsvDownload(buildCsv(results.columns, results.rows));
        }
      } catch {
        triggerCsvDownload(buildCsv(results.columns, results.rows));
      } finally {
        setIsExporting(false);
      }
    } else {
      triggerCsvDownload(buildCsv(results.columns, results.rows));
    }
  };

  const handleFormatQuery = () => {
    if (!editorRef.current) {
      setFormatNotice({ kind: "warning", message: "Editor isn't ready yet." });
      return;
    }

    const currentQuery = editorRef.current.getValue();
    if (!currentQuery || !currentQuery.trim()) {
      setFormatNotice({ kind: "warning", message: "Nothing to format yet — write some SQL first." });
      return;
    }

    // Resolve the dialect from the connection's actual dbType, not from regex-matching its UUID.
    // Fall back through a short chain so a query that confuses one parser can still be formatted
    // by a more permissive cousin.
    const primaryDialect = resolveSqlFormatterDialect(currentConnection?.dbType);
    const fallbackDialects = ["mysql", "sql"].filter((d) => d !== primaryDialect);
    const dialectChain = [primaryDialect, ...fallbackDialects];

    const baseOptions = {
      indent: "  ",
      keywordCase: "upper",
      linesBetweenQueries: 2,
    };

    let formatted = null;
    let usedDialect = null;
    for (const language of dialectChain) {
      try {
        formatted = format(currentQuery, { ...baseOptions, language });
        usedDialect = language;
        break;
      } catch (err) {
        // Swallow and try the next dialect. We never surface the parser's BNF trace.
        // Keeping the last error around for diagnostic logging only.
        if (import.meta?.env?.DEV) {
          // eslint-disable-next-line no-console
          console.debug(`[SqlRunnerTab] format failed with dialect=${language}:`, err?.message);
        }
      }
    }

    if (formatted == null) {
      // Every dialect bailed. Give the user a short, actionable message — never the BNF dump —
      // and leave their SQL untouched so they don't lose work.
      const hint = looksLikeSingleLineDashComment(currentQuery)
        ? " It looks like this query has `--` comments on a single line — in MySQL/Postgres/SQLite a `--` comment extends to end of line, so it can swallow the closing parens. Try breaking the query across multiple lines."
        : "";
      setFormatNotice({
        kind: "warning",
        message: `Couldn't auto-format this query — leaving it as-is.${hint}`,
      });
      return;
    }

    editorRef.current.setValue(formatted);
    setQuery(formatted);
    if (usedDialect !== primaryDialect) {
      setFormatNotice({
        kind: "info",
        message: `Formatted using the ${usedDialect} dialect (the connection's ${primaryDialect} parser couldn't read this query).`,
      });
    } else {
      setFormatNotice(null);
    }
  };

  const toggleNode = (nodeId) => {
    setExpandedNodes((prev) => ({
      ...prev,
      [nodeId]: !prev[nodeId],
    }));
  };

  const handleTableClick = (table) => {
    setSelectedTable(table);
    const columnList = table.columns?.map((col) => col.name).join(", ") || "*";
    const from = qualifyForSql(table);
    const sql = `SELECT ${columnList}\nFROM ${from}\nLIMIT 10;`;
    if (editorRef.current) editorRef.current.setValue(sql);
    setQuery(sql);
  };

  const handleTableSelect = (table) => {
    // Just select the table without overwriting the query
    setSelectedTable(table);
    // Expand the table to show columns
    toggleNode(`table-${objectKey(table)}`);
  };

  const handleColumnInsert = (tableOrName, columnName) => {
    // Get current value directly from the editor (not from stale React state)
    const current = editorRef.current ? editorRef.current.getValue() : query;
    const tableRef =
      typeof tableOrName === "string"
        ? tableOrName
        : canonicalTableReference(tableOrName);
    const newValue = current + (current ? "\n" : "") + `${tableRef}.${columnName}`;
    if (editorRef.current) editorRef.current.setValue(newValue);
    setQuery(newValue);
  };

  const handleTableContextMenu = (e, table) => {
    e.preventDefault();
    setContextMenu({
      visible: true,
      x: e.clientX,
      y: e.clientY,
      table: table,
    });
  };

  const handlePreviewData = () => {
    if (contextMenu.table) {
      const previewQuery = `SELECT *\nFROM ${qualifyForSql(contextMenu.table)}\nLIMIT 100;`;
      if (editorRef.current) editorRef.current.setValue(previewQuery);
      setQuery(previewQuery);
      setContextMenu({ visible: false, x: 0, y: 0, table: null });
      // Auto-execute the query
      handleRunQuery(previewQuery);
    }
  };

  const handleViewDetail = async () => {
    if (contextMenu.table) {
      setDetailTable(contextMenu.table);
      setShowDetailModal(true);
      setDetailTab("columns");
      setContextMenu({ visible: false, x: 0, y: 0, table: null });

      // Fetch indexes for the table
      setLoadingIndexes(true);
      try {
        const response = await queryAPI.getTableIndexes(
          connectionId,
          objectKey(contextMenu.table),
        );
        if (response.success) {
          setTableIndexes(response.indexes || []);
        }
      } catch (err) {
        console.error("Failed to fetch indexes:", err);
        setTableIndexes([]);
      } finally {
        setLoadingIndexes(false);
      }
    }
  };

  const copyToClipboard = (text) => {
    navigator.clipboard
      .writeText(text)
      .then(() => {})
      .catch((err) => {
        console.error("Failed to copy to clipboard:", err);
        setError("Failed to copy to clipboard");
      });
    setContextMenu({ visible: false, x: 0, y: 0, table: null });
    setShowSqlSubmenu(false);
  };

  const handleCopyName = () => {
    if (contextMenu.table) {
      copyToClipboard(canonicalTableReference(contextMenu.table));
    }
  };

  const handleCopyAllColumnNames = () => {
    if (contextMenu.table && contextMenu.table.columns) {
      const columnNames = contextMenu.table.columns
        .map((col) => col.name)
        .join(", ");
      copyToClipboard(columnNames);
    }
  };

  const handleGenerateSQL = (type) => {
    if (!contextMenu.table) return;

    const tableName = qualifyForSql(contextMenu.table);
    const columns = contextMenu.table.columns || [];
    const columnList = columns.map((col) => col.name).join(",\n    ");

    let sql = "";
    switch (type) {
      case "SELECT":
        sql = `SELECT ${columnList || "*"}\nFROM ${tableName}\nWHERE 1=1;`;
        break;
      case "INSERT":
        const values = columns.map(() => "?").join(", ");
        sql = `INSERT INTO ${tableName} (\n    ${columnList}\n)\nVALUES (\n    ${values}\n);`;
        break;
      case "UPDATE":
        const setClauses = columns
          .map((col) => `${col.name} = ?`)
          .join(",\n    ");
        sql = `UPDATE ${tableName}\nSET\n    ${setClauses}\nWHERE id = ?;`;
        break;
      case "DELETE":
        sql = `DELETE FROM ${tableName}\nWHERE id = ?;`;
        break;
    }

    setQuery(sql);
    if (editorRef.current) {
      editorRef.current.setValue(sql);
    }
    copyToClipboard(sql);
  };

  const handleViewSchemaText = () => {
    if (!contextMenu.table) {
      setContextMenu({ visible: false, x: 0, y: 0, table: null });
      return;
    }

    if (!contextMenu.table.columns || contextMenu.table.columns.length === 0) {
      setError("No column information available for this table");
      setContextMenu({ visible: false, x: 0, y: 0, table: null });
      return;
    }

    const columns = contextMenu.table.columns;
    const tableName = canonicalTableReference(contextMenu.table);

    // Create schema text with table name header
    let schemaText = `-- Schema for table: ${tableName}\n`;
    schemaText += `-- ${columns.length} column${columns.length !== 1 ? "s" : ""}\n\n`;

    schemaText += columns
      .map((col) => {
        let line = `${col.name} ${col.dataType}`;
        if (!col.nullable) line += " NOT NULL";
        if (col.defaultValue) line += ` DEFAULT ${col.defaultValue}`;
        if (col.primaryKey) line += " PRIMARY KEY";
        return line;
      })
      .join("\n");

    copyToClipboard(schemaText);

    // Show success feedback
    setError(null);
  };

  const handleEditSchema = () => {
    // TODO: Open schema editor
    setContextMenu({ visible: false, x: 0, y: 0, table: null });
  };

  const handleCopyURL = () => {
    if (contextMenu.table) {
      const url = `${window.location.origin}/tables/${objectKey(contextMenu.table)}`;
      copyToClipboard(url);
    }
  };

  const handleEditorKeyDown = (e) => {
    // Cmd/Ctrl + Enter to run query
    if ((e.metaKey || e.ctrlKey) && e.key === "Enter") {
      e.preventDefault();
      handleRunQuery();
    }
    // Shift + Alt + F to format query
    if (e.shiftKey && e.altKey && e.key === "f") {
      e.preventDefault();
      handleFormatQuery();
    }
    // Cmd/Ctrl + S to save query
    if ((e.metaKey || e.ctrlKey) && e.key === "s") {
      e.preventDefault();
      handleSaveCurrentQuery();
    }
  };

  const handleSaveCurrentQuery = () => {
    const currentQuery = editorRef.current
      ? editorRef.current.getValue()
      : query;
    if (!currentQuery || !currentQuery.trim()) {
      setError("No query to save");
      return;
    }
    setSaveQueryData({ query: currentQuery });
    setShowSaveQueryModal(true);
  };

  const handleLoadQuery = (loadedQuery) => {
    if (editorRef.current) {
      editorRef.current.setValue(loadedQuery);
    }
    setQuery(loadedQuery);
  };

  // Group objects by type
  const groupedObjects = {
    tables: databaseObjects.filter((obj) => obj.type === "table"),
    views: databaseObjects.filter((obj) => obj.type === "view"),
    functions: databaseObjects.filter((obj) => obj.type === "function"),
    procedures: databaseObjects.filter((obj) => obj.type === "procedure"),
  };

  // Filter objects based on search (includes table names and column names)
  const multiSchema = connectionHasMultipleSchemas(databaseObjects);

  const filteredObjects = searchTerm
    ? databaseObjects.filter((obj) => {
        const searchLower = searchTerm.toLowerCase();
        const ref = canonicalTableReference(obj).toLowerCase();
        const schema = (obj.schema || obj.schemaName || "").toLowerCase();
        // Match table/view/function/procedure name or schema
        if (
          obj.name.toLowerCase().includes(searchLower) ||
          ref.includes(searchLower) ||
          schema.includes(searchLower)
        ) {
          return true;
        }
        // Match columns if it's a table or view
        if ((obj.type === "table" || obj.type === "view") && obj.columns) {
          return obj.columns.some((col) =>
            col.name.toLowerCase().includes(searchLower),
          );
        }
        return false;
      })
    : null;

  return (
    <div className={styles.sqlRunner}>
      <PanelGroup direction="horizontal" style={{ height: "100%" }}>
        {/* Database Explorer Sidebar */}
        <Panel defaultSize={18} minSize={12} maxSize={30}>
          <div className={styles.explorerSidebar}>
            <div className={styles.explorerHeader}>
              <div className={styles.explorerTitle}>
                <div
                  style={{
                    display: "flex",
                    alignItems: "center",
                    gap: "var(--spacing-sm)",
                  }}
                >
                  <Database size={16} />
                  <h3>Database</h3>
                </div>
                {/* Refresh Button */}
                <button
                  className={styles.refreshButton}
                  onClick={handleRefreshSchema}
                  disabled={!connectionId || refreshingSchema || loadingObjects}
                  title="Refresh schema and row counts"
                  style={{
                    background: "transparent",
                    border: "none",
                    padding: 0,
                    margin: 0,
                  }}
                >
                  {refreshingSchema ? (
                    <Loader2 size={16} className={styles.spinner} />
                  ) : (
                    <RefreshCw size={16} />
                  )}
                </button>
              </div>

              {/* View Tabs */}
              <div className={styles.viewTabs}>
                <button
                  className={`${styles.viewTab} ${leftPanelView === "explorer" ? styles.active : ""}`}
                  onClick={() => setLeftPanelView("explorer")}
                  title="Explorer"
                >
                  <Database size={14} />
                </button>
                <button
                  className={`${styles.viewTab} ${leftPanelView === "views" ? styles.active : ""}`}
                  onClick={() => setLeftPanelView("views")}
                  title="Views"
                >
                  <Eye size={14} />
                </button>
                <button
                  className={`${styles.viewTab} ${leftPanelView === "functions" ? styles.active : ""}`}
                  onClick={() => setLeftPanelView("functions")}
                  title="Functions"
                >
                  <FunctionSquare size={14} />
                </button>
                <button
                  className={`${styles.viewTab} ${leftPanelView === "procedures" ? styles.active : ""}`}
                  onClick={() => setLeftPanelView("procedures")}
                  title="Procedures"
                >
                  <FileCode size={14} />
                </button>
              </div>

              {leftPanelView === "explorer" && (
                <div className={styles.searchBox}>
                  <Search size={14} />
                  <input
                    type="text"
                    placeholder="Search..."
                    value={searchTerm}
                    onChange={(e) => setSearchTerm(e.target.value)}
                  />
                </div>
              )}
            </div>

            <div className={styles.explorerContent}>
              {/* Explorer View */}
              {leftPanelView === "explorer" && (
                <>
                  {loadingObjects && databaseObjects.length === 0 ? (
                    <div className={styles.loadingExplorer}>
                      <div className={styles.spinner}></div>
                      <span>Loading schema...</span>
                    </div>
                  ) : filteredObjects ? (
                    <div className={styles.searchResults}>
                      <div className={styles.searchResultsCount}>
                        {filteredObjects.length} result
                        {filteredObjects.length !== 1 ? "s" : ""}
                      </div>
                      {filteredObjects.map((obj) => (
                        <div key={objectKey(obj)}>
                          <div
                            className={`${styles.objectItem} ${objectKey(selectedTable) === objectKey(obj) ? styles.selected : ""}`}
                            onClick={() => handleTableSelect(obj)}
                          >
                            <div
                              className={styles.objectName}
                              onClick={(e) => {
                                e.stopPropagation();
                                toggleNode(`table-${objectKey(obj)}`);
                              }}
                            >
                              {expandedNodes[`table-${objectKey(obj)}`] ? (
                                <ChevronDown size={14} />
                              ) : (
                                <ChevronRight size={14} />
                              )}
                              {obj.type === "table" && <Table size={14} />}
                              {obj.type === "view" && <Eye size={14} />}
                              {obj.type === "function" && (
                                <FunctionSquare size={14} />
                              )}
                              {obj.type === "procedure" && (
                                <FileCode size={14} />
                              )}
                              <span>{canonicalTableReference(obj)}</span>
                            </div>
                            {hasRowCount(obj.rowCount) && (
                              <span className={styles.rowCount}>
                                {obj.rowCount.toLocaleString()}
                              </span>
                            )}
                          </div>
                          {expandedNodes[`table-${objectKey(obj)}`] &&
                            obj.columns && (
                              <div className={styles.columnList}>
                                {obj.columns.map((column) => {
                                  const columnMatches =
                                    searchTerm &&
                                    column.name
                                      .toLowerCase()
                                      .includes(searchTerm.toLowerCase());
                                  return (
                                    <div
                                      key={`${objectKey(obj)}.${column.name}`}
                                      className={`${styles.columnItem} ${columnMatches ? styles.highlighted : ""}`}
                                      onClick={(e) => {
                                        e.stopPropagation();
                                        handleColumnInsert(
                                          obj,
                                          column.name,
                                        );
                                      }}
                                      title={`${column.dataType}${column.nullable ? " (nullable)" : " (not null)"}`}
                                    >
                                      {column.primaryKey ? (
                                        <Key size={12} />
                                      ) : (
                                        <Columns size={12} />
                                      )}
                                      <span className={styles.columnName}>
                                        {column.name}
                                      </span>
                                      <span className={styles.columnType}>
                                        {column.dataType}
                                      </span>
                                    </div>
                                  );
                                })}
                              </div>
                            )}
                        </div>
                      ))}
                    </div>
                  ) : (
                    <>
                      {/* Tables */}
                      <div className={styles.objectGroup}>
                        <div
                          className={styles.groupHeader}
                          onClick={() => toggleNode("tables")}
                        >
                          {expandedNodes.tables ? (
                            <ChevronDown size={16} />
                          ) : (
                            <ChevronRight size={16} />
                          )}
                          <Table size={14} />
                          <span>Tables</span>
                          <span className={styles.count}>
                            {groupedObjects.tables.length}
                          </span>
                        </div>
                        {expandedNodes.tables && (
                          <div className={styles.objectList}>
                            {groupedObjects.tables.map((table) => (
                              <div key={objectKey(table)}>
                                <div
                                  className={`${styles.objectItem} ${objectKey(selectedTable) === objectKey(table) ? styles.selected : ""}`}
                                  onClick={() => handleTableClick(table)}
                                  onContextMenu={(e) =>
                                    handleTableContextMenu(e, table)
                                  }
                                >
                                  <div className={styles.objectName}>
                                    <button
                                      type="button"
                                      className={styles.expandToggle || undefined}
                                      style={{
                                        background: "transparent",
                                        border: "none",
                                        padding: 0,
                                        display: "inline-flex",
                                        cursor: "pointer",
                                      }}
                                      aria-label={
                                        expandedNodes[`table-${objectKey(table)}`]
                                          ? "Collapse columns"
                                          : "Expand columns"
                                      }
                                      onClick={(e) => {
                                        e.stopPropagation();
                                        toggleNode(`table-${objectKey(table)}`);
                                      }}
                                    >
                                      {expandedNodes[`table-${objectKey(table)}`] ? (
                                        <ChevronDown size={14} />
                                      ) : (
                                        <ChevronRight size={14} />
                                      )}
                                    </button>
                                    <span title={canonicalTableReference(table)}>
                                      {multiSchema && table.schema && table.schema !== 'public'
                                        ? <><span style={{opacity:0.55}}>{table.schema}.</span>{table.name}</>
                                        : table.name}
                                    </span>
                                  </div>
                                  {hasRowCount(table.rowCount) && (
                                    <span className={styles.rowCount}>
                                      {table.rowCount.toLocaleString()}
                                    </span>
                                  )}
                                </div>
                                {expandedNodes[`table-${objectKey(table)}`] &&
                                  table.columns && (
                                    <div className={styles.columnList}>
                                      {table.columns.map((column) => {
                                        // Key role badge: 'k' for direct keys (PK/FK), 'ik' for
                                        // brain-inferred keys. Direct takes precedence over inferred.
                                        const keyTag = column.directKey
                                          ? "k"
                                          : column.inferredKey
                                          ? "ik"
                                          : null;
                                        // Index badge: 'i' for single-column index, 'ci' for composite.
                                        // Prefer 'i' when a column participates in both.
                                        const idxTag = column.hasSingleColumnIndex
                                          ? "i"
                                          : column.hasCompositeIndex
                                          ? "ci"
                                          : null;
                                        return (
                                          <div
                                            key={`${objectKey(table)}.${column.name}`}
                                            className={styles.columnItem}
                                            onClick={(e) => {
                                              e.stopPropagation();
                                              handleColumnInsert(
                                                table,
                                                column.name,
                                              );
                                            }}
                                            title={`${column.dataType}${column.nullable ? " (nullable)" : " (not null)"}`}
                                          >
                                            {column.primaryKey ? (
                                              <Key size={12} />
                                            ) : (
                                              <Columns size={12} />
                                            )}
                                            <span className={styles.columnName}>
                                              {column.name}
                                            </span>
                                            {keyTag && (
                                              <span
                                                className={`${styles.colBadge} ${
                                                  keyTag === "k"
                                                    ? styles.colBadgeKey
                                                    : styles.colBadgeKeyInferred
                                                }`}
                                                title={
                                                  keyTag === "k"
                                                    ? "Key column (primary key or foreign key)"
                                                    : "Inferred key column (identified by query-usage analysis)"
                                                }
                                              >
                                                {keyTag}
                                              </span>
                                            )}
                                            {idxTag && (
                                              <span
                                                className={`${styles.colBadge} ${styles.colBadgeIdx}`}
                                                title={
                                                  idxTag === "i"
                                                    ? "Indexed (single-column index)"
                                                    : "Indexed (part of a composite / multi-column index)"
                                                }
                                              >
                                                {idxTag}
                                              </span>
                                            )}
                                            <span className={styles.columnType}>
                                              {column.dataType}
                                            </span>
                                          </div>
                                        );
                                      })}
                                    </div>
                                  )}
                              </div>
                            ))}
                          </div>
                        )}
                      </div>

                      {/* Views */}
                      {groupedObjects.views.length > 0 && (
                        <div className={styles.objectGroup}>
                          <div
                            className={styles.groupHeader}
                            onClick={() => toggleNode("views")}
                          >
                            {expandedNodes.views ? (
                              <ChevronDown size={16} />
                            ) : (
                              <ChevronRight size={16} />
                            )}
                            <Eye size={14} />
                            <span>Views</span>
                            <span className={styles.count}>
                              {groupedObjects.views.length}
                            </span>
                          </div>
                          {expandedNodes.views && (
                            <div className={styles.objectList}>
                              {groupedObjects.views.map((view) => (
                                <div
                                  key={objectKey(view)}
                                  className={styles.objectItem}
                                  onClick={() => handleTableClick(view)}
                                >
                                  <span>{canonicalTableReference(view)}</span>
                                </div>
                              ))}
                            </div>
                          )}
                        </div>
                      )}

                      {/* Functions */}
                      {groupedObjects.functions.length > 0 && (
                        <div className={styles.objectGroup}>
                          <div
                            className={styles.groupHeader}
                            onClick={() => toggleNode("functions")}
                          >
                            {expandedNodes.functions ? (
                              <ChevronDown size={16} />
                            ) : (
                              <ChevronRight size={16} />
                            )}
                            <FunctionSquare size={14} />
                            <span>Functions</span>
                            <span className={styles.count}>
                              {groupedObjects.functions.length}
                            </span>
                          </div>
                          {expandedNodes.functions && (
                            <div className={styles.objectList}>
                              {groupedObjects.functions.map((func) => (
                                <div
                                  key={objectKey(func)}
                                  className={styles.objectItem}
                                >
                                  <span>{canonicalTableReference(func)}</span>
                                </div>
                              ))}
                            </div>
                          )}
                        </div>
                      )}

                      {/* Procedures */}
                      {groupedObjects.procedures.length > 0 && (
                        <div className={styles.objectGroup}>
                          <div
                            className={styles.groupHeader}
                            onClick={() => toggleNode("procedures")}
                          >
                            {expandedNodes.procedures ? (
                              <ChevronDown size={16} />
                            ) : (
                              <ChevronRight size={16} />
                            )}
                            <FileCode size={14} />
                            <span>Procedures</span>
                            <span className={styles.count}>
                              {groupedObjects.procedures.length}
                            </span>
                          </div>
                          {expandedNodes.procedures && (
                            <div className={styles.objectList}>
                              {groupedObjects.procedures.map((proc) => (
                                <div
                                  key={objectKey(proc)}
                                  className={styles.objectItem}
                                >
                                  <span>{canonicalTableReference(proc)}</span>
                                </div>
                              ))}
                            </div>
                          )}
                        </div>
                      )}
                    </>
                  )}
                </>
              )}

              {/* Views View */}
              {leftPanelView === "views" && (
                <div className={styles.listView}>
                  <div className={styles.listViewHeader}>
                    <h4>Views ({groupedObjects.views.length})</h4>
                  </div>
                  <div className={styles.listViewContent}>
                    {loadingObjects ? (
                      <div className={styles.loadingExplorer}>
                        <div className={styles.spinner}></div>
                        <span>Loading views...</span>
                      </div>
                    ) : groupedObjects.views.length === 0 ? (
                      <div className={styles.emptyState}>
                        <Eye size={32} />
                        <p>No views found</p>
                      </div>
                    ) : (
                      groupedObjects.views.map((view) => (
                        <div
                          key={objectKey(view)}
                          className={styles.listItem}
                          onClick={() => handleTableClick(view)}
                        >
                          <div className={styles.listItemHeader}>
                            <div className={styles.listItemTitle}>
                              <Eye size={14} />
                              <span>{canonicalTableReference(view)}</span>
                            </div>
                          </div>
                          {view.columns && (
                            <div className={styles.listItemMeta}>
                              <span>{view.columns.length} columns</span>
                            </div>
                          )}
                        </div>
                      ))
                    )}
                  </div>
                </div>
              )}

              {/* Functions View */}
              {leftPanelView === "functions" && (
                <div className={styles.listView}>
                  <div className={styles.listViewHeader}>
                    <h4>Functions ({groupedObjects.functions.length})</h4>
                  </div>
                  <div className={styles.listViewContent}>
                    {loadingObjects ? (
                      <div className={styles.loadingExplorer}>
                        <div className={styles.spinner}></div>
                        <span>Loading functions...</span>
                      </div>
                    ) : groupedObjects.functions.length === 0 ? (
                      <div className={styles.emptyState}>
                        <FunctionSquare size={32} />
                        <p>No functions found</p>
                      </div>
                    ) : (
                      groupedObjects.functions.map((func) => (
                        <div
                          key={objectKey(func)}
                          className={styles.listItem}
                          onClick={() => handleTableClick(func)}
                        >
                          <div className={styles.listItemHeader}>
                            <div className={styles.listItemTitle}>
                              <FunctionSquare size={14} />
                              <span>{canonicalTableReference(func)}</span>
                            </div>
                          </div>
                        </div>
                      ))
                    )}
                  </div>
                </div>
              )}

              {/* Procedures View */}
              {leftPanelView === "procedures" && (
                <div className={styles.listView}>
                  <div className={styles.listViewHeader}>
                    <h4>Procedures ({groupedObjects.procedures.length})</h4>
                  </div>
                  <div className={styles.listViewContent}>
                    {loadingObjects ? (
                      <div className={styles.loadingExplorer}>
                        <div className={styles.spinner}></div>
                        <span>Loading procedures...</span>
                      </div>
                    ) : groupedObjects.procedures.length === 0 ? (
                      <div className={styles.emptyState}>
                        <FileCode size={32} />
                        <p>No procedures found</p>
                      </div>
                    ) : (
                      groupedObjects.procedures.map((proc) => (
                        <div
                          key={objectKey(proc)}
                          className={styles.listItem}
                          onClick={() => handleTableClick(proc)}
                        >
                          <div className={styles.listItemHeader}>
                            <div className={styles.listItemTitle}>
                              <FileCode size={14} />
                              <span>{canonicalTableReference(proc)}</span>
                            </div>
                          </div>
                        </div>
                      ))
                    )}
                  </div>
                </div>
              )}
            </div>
          </div>
        </Panel>

        <PanelResizeHandle className={styles.resizeHandleVertical} />

        {/* Main Query Editor and Results Area */}
        <Panel defaultSize={64}>
          <PanelGroup direction="vertical" style={{ height: "100%" }}>
            {/* Query Editor */}
            <Panel defaultSize={40} minSize={20} maxSize={80}>
              <div className={styles.editorSection}>
                <div className={styles.editorHeader}>
                  <div className={styles.editorTitle}>
                    <h3>SQL Query Editor</h3>
                    <HelpTooltip
                      content={
                        isAdmin
                          ? "Admins can run INSERT, UPDATE, and DELETE (with a WHERE clause) after confirmation. DROP is blocked. The selected database user must still have write privileges."
                          : "Only admins can run DML from the Editor."
                      }
                    >
                      <span className={styles.executionPolicyNote}>
                        {isAdmin ? "Admin mode" : "Read-only mode"}
                      </span>
                    </HelpTooltip>
                    {!connectionId && (
                      <span
                        className={styles.hint}
                        style={{ color: "#991B1B" }}
                      >
                        ⚠️ No database connection selected
                      </span>
                    )}
                  </div>
                  <div className={styles.editorActions}>
                    <button
                      className={styles.secondaryButton}
                      onClick={() => {
                        if (editorRef.current) editorRef.current.setValue("");
                        setQuery("");
                      }}
                      disabled={!query}
                      title="Clear SQL"
                      aria-label="Clear SQL"
                    >
                      <X size={16} />
                      <span className={styles.buttonLabel}>Clear</span>
                    </button>
                    <button
                      className={styles.secondaryButton}
                      onClick={handleFormatQuery}
                      disabled={!query}
                      title="Format SQL (Shift+Alt+F)"
                      aria-label="Format SQL"
                    >
                      <Wand2 size={16} />
                      <span className={styles.buttonLabel}>Format</span>
                    </button>
                    <button
                      className={styles.secondaryButton}
                      onClick={handleSaveCurrentQuery}
                      disabled={!query || !connectionId}
                      title="Save Query (⌘+S)"
                      aria-label="Save Query"
                    >
                      <Save size={16} />
                      <span className={styles.buttonLabel}>Save</span>
                    </button>
                    <button
                      className={styles.explainButton}
                      onClick={handleExplainQuery}
                      disabled={isExplaining || !connectionId}
                      title={
                        !connectionId
                          ? "Please select a database connection first"
                          : "Explain Query (Analyze execution plan)"
                      }
                      aria-label="Explain Query"
                    >
                      {isExplaining ? (
                        <Loader2 size={16} className={styles.runSpinner} />
                      ) : (
                        <Zap size={16} />
                      )}
                      <span className={styles.buttonLabel}>Explain</span>
                    </button>
                    <button
                      className={styles.explainButton}
                      onClick={handleOptimizeQuery}
                      disabled={isOptimizing || !connectionId || !isReadOnlySql(query)}
                      title={
                        !connectionId
                          ? "Please select a database connection first"
                          : !isReadOnlySql(query)
                            ? "AI rewrite is available for read-only (SELECT) queries"
                            : "AI Optimize — rewrite for best practices and index usage"
                      }
                      aria-label="AI Optimize Query"
                    >
                      {isOptimizing ? (
                        <Loader2 size={16} className={styles.runSpinner} />
                      ) : (
                        <Sparkles size={16} />
                      )}
                      <span className={styles.buttonLabel}>Optimize</span>
                    </button>
                    {isRunning ? (
                      <button
                        className={styles.stopButton}
                        onClick={handleStopQuery}
                        title="Stop query execution"
                        aria-label="Stop Query"
                      >
                        <Square size={14} fill="currentColor" />
                        <span className={styles.buttonLabel}>Stop</span>
                      </button>
                    ) : (
                      <button
                        className={styles.runButton}
                        onClick={() => handleRunQuery()}
                        disabled={!connectionId}
                        title={
                          !connectionId
                            ? "Please select a database connection first"
                            : "Run Query (⌘+Enter or Ctrl+Enter)"
                        }
                        aria-label="Run Query"
                      >
                        <Play size={16} />
                        <span className={styles.buttonLabel}>Run</span>
                      </button>
                    )}
                  </div>
                </div>

                {formatNotice && (
                  <div
                    className={
                      formatNotice.kind === "info"
                        ? styles.formatNoticeInfo
                        : styles.formatNoticeWarning
                    }
                    role="status"
                  >
                    <span className={styles.formatNoticeMessage}>{formatNotice.message}</span>
                    <button
                      type="button"
                      className={styles.formatNoticeDismiss}
                      onClick={() => setFormatNotice(null)}
                      aria-label="Dismiss notice"
                    >
                      <X size={14} />
                    </button>
                  </div>
                )}

                <div
                  className={styles.monacoWrapper}
                  onKeyDown={handleEditorKeyDown}
                >
                  <Suspense
                    fallback={
                      <div className={styles.editorLoading}>
                        Loading editor...
                      </div>
                    }
                  >
                    <Editor
                      height="100%"
                      defaultLanguage="sql"
                      defaultValue={query}
                      onChange={(value) => {
                        // Sync to React state for caching only — do NOT feed back into the editor
                        // (avoids cursor-jump caused by controlled-mode re-renders)
                        const stringValue =
                          value === null || value === undefined
                            ? ""
                            : String(value);
                        setQuery(stringValue);
                      }}
                      onMount={(editor, monaco) => {
                        editorRef.current = editor;
                        monacoRef.current = monaco;

                        // Enable autocomplete suggestions
                        editor.updateOptions({
                          quickSuggestions: {
                            other: true,
                            comments: false,
                            strings: false,
                          },
                          suggestOnTriggerCharacters: true,
                          // Only accept suggestions with Enter or Tab, not on punctuation/space —
                          // prevents unexpected mid-word completions that jump the cursor
                          acceptSuggestionOnCommitCharacter: false,
                          acceptSuggestionOnEnter: "on",
                          tabCompletion: "on",
                          // Disable word-based suggestions to avoid conflicts with our SQL provider
                          wordBasedSuggestions: "off",
                        });

                        // Register autocomplete provider if database objects are loaded
                        if (
                          databaseObjects.length > 0 &&
                          !autocompleteRegisteredRef.current
                        ) {
                          registerAutocompleteProvider(monaco);
                          autocompleteRegisteredRef.current = true;
                        }
                      }}
                      theme="vs-light"
                      options={{
                        minimap: { enabled: false },
                        fontSize: 13,
                        lineNumbers: "on",
                        roundedSelection: true,
                        scrollBeyondLastLine: false,
                        automaticLayout: true,
                        tabSize: 2,
                        wordWrap: "on",
                      }}
                    />
                  </Suspense>
                </div>
              </div>
            </Panel>

            <PanelResizeHandle className={styles.resizeHandle} />

            {/* Results Section */}
            <Panel defaultSize={60} minSize={20} maxSize={80}>
              <div className={styles.resultsSection}>
                <div className={styles.resultsHeader}>
                  <div className={styles.resultsTitle}>
                    <h3>
                      {results
                        ? "Results"
                        : explainResults
                          ? "Explain Plan"
                          : "Results"}
                    </h3>
                    {results && (
                      <div className={styles.resultsMeta}>
                        <span className={styles.rowCount}>
                          {results.totalRowCount != null && results.isLimited
                            ? `Showing ${results.rowCount.toLocaleString()} of ${results.totalRowCount.toLocaleString()} rows`
                            : results.isLimited
                              ? `Showing first ${results.rowCount.toLocaleString()} rows`
                              : `${results.rowCount} row${results.rowCount !== 1 ? "s" : ""}`}
                        </span>
                        <span className={styles.executionTime}>
                          {results.executionTimeMs}ms
                        </span>
                      </div>
                    )}
                    {explainResults && (
                      <div className={styles.resultsMeta}>
                        <span
                          className={styles.performanceScore}
                          title={
                            explainResults.wasExecuted
                              ? "EXPLAIN ANALYZE — the query was executed; rows and timings are actual"
                              : "Plain EXPLAIN — planner estimates only; the query was not executed"
                          }
                        >
                          {explainResults.wasExecuted ? "Actual plan" : "Estimated plan"}
                        </span>
                      </div>
                    )}
                  </div>
                  {results && (
                    <div className={styles.resultsActions}>
                      <button
                        className={styles.iconButton}
                        onClick={handleExportResults}
                        disabled={isExporting}
                        title={
                          results?.isLimited
                            ? results?.totalRowCount != null && results.totalRowCount > EXPORT_ROW_LIMIT
                              ? `Download first ${EXPORT_ROW_LIMIT.toLocaleString()} of ${results.totalRowCount.toLocaleString()} rows as CSV`
                              : `Download up to ${EXPORT_ROW_LIMIT.toLocaleString()} rows as CSV`
                            : "Export CSV"
                        }
                      >
                        {isExporting ? <Loader2 size={16} className={styles.spinning} /> : <Download size={16} />}
                      </button>
                    </div>
                  )}
                </div>

                {error && (
                  <div className={styles.error}>
                    <strong>Error:</strong> {error}
                  </div>
                )}

                {/* Show Results when available */}
                {results && !error && (
                  <div className={styles.tableWrapper}>
                    <table className={styles.resultsTable}>
                      <thead>
                        <tr>
                          {results.columns?.map((col, index) => (
                            <th key={index}>{col}</th>
                          ))}
                        </tr>
                      </thead>
                      <tbody>
                        {results.rows?.map((row, rowIndex) => (
                          <tr key={rowIndex}>
                            {row.map((cell, cellIndex) => (
                              <td key={cellIndex}>
                                {cell === null ? (
                                  <span className={styles.nullValue}>NULL</span>
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
                )}

                {/* Show Explain Plan when available */}
                {explainResults && !error && (
                  <div className={styles.explainResults}>
                    <ExplainAnalysisPanel analysis={explainResults} />
                  </div>
                )}

                {/* Show AI optimize result when available */}
                {optimizeResult && !error && (
                  <div className={styles.explainResults}>
                    <QueryOptimizePanel result={optimizeResult} />
                  </div>
                )}

                {/* Empty states */}
                {!results &&
                  !explainResults &&
                  !optimizeResult &&
                  !error &&
                  !isRunning &&
                  !isExplaining &&
                  !isOptimizing && (
                    <div className={styles.emptyState}>
                      <Play size={48} className={styles.emptyIcon} />
                      <h3>No results yet</h3>
                      <p>
                        Run a SQL query to see results, or click "Explain" to
                        analyze the execution plan
                      </p>
                      {queryHistory.length > 0 && (
                        <div className={styles.historySection}>
                          <div className={styles.historyHeader}>
                            <Clock size={16} />
                            <span>Recent Queries</span>
                          </div>
                          <div className={styles.historyList}>
                            {queryHistory.slice(0, 5).map((item, index) => (
                              <div
                                key={index}
                                className={styles.historyItem}
                                onClick={() => setQuery(item.query)}
                              >
                                <div className={styles.historyQuery}>
                                  {item.query.substring(0, 100)}
                                  {item.query.length > 100 && "..."}
                                </div>
                                <div className={styles.historyMeta}>
                                  <span>{item.rowCount} rows</span>
                                  <span>•</span>
                                  <span>{item.executionTime}ms</span>
                                </div>
                              </div>
                            ))}
                          </div>
                        </div>
                      )}
                    </div>
                  )}

                {/* Loading states */}
                {isRunning && (
                  <div className={styles.loadingState}>
                    <div className={styles.spinner}></div>
                    <p>Executing query...</p>
                    <button
                      className={styles.stopButtonInline}
                      onClick={handleStopQuery}
                      title="Stop query execution"
                    >
                      <Square size={12} fill="currentColor" />
                      Stop execution
                    </button>
                  </div>
                )}

                {isExplaining && (
                  <div className={styles.loadingState}>
                    <div className={styles.spinner}></div>
                    <p>Analyzing query execution plan...</p>
                  </div>
                )}

                {isOptimizing && (
                  <div className={styles.loadingState}>
                    <div className={styles.spinner}></div>
                    <p>AI is analyzing the plan and rewriting your query…</p>
                  </div>
                )}
              </div>
            </Panel>
          </PanelGroup>
        </Panel>
      </PanelGroup>

      {/* Saved Queries Modal (no panel UI, only modal) */}
      <SavedQueriesPanel
        ref={savedQueriesPanelRef}
        connectionId={connectionId}
        onLoadQuery={handleLoadQuery}
        initialQuery={saveQueryData}
        showSaveModal={showSaveQueryModal}
        onCloseSaveModal={() => {
          setShowSaveQueryModal(false);
          setSaveQueryData(null);
        }}
      />

      {pendingMutationConfirmation && (
        <div
          className={styles.modalOverlay}
          onClick={() => setPendingMutationConfirmation(null)}
        >
          <div className={styles.modal} onClick={(e) => e.stopPropagation()}>
            <div className={styles.modalHeader}>
              <div className={styles.modalTitle}>
                <AlertTriangle size={20} />
                <h2>Confirm {pendingMutationConfirmation.queryType}</h2>
              </div>
              <button
                className={styles.closeButton}
                onClick={() => setPendingMutationConfirmation(null)}
              >
                <X size={20} />
              </button>
            </div>
            <div className={styles.modalContent}>
              <div className={styles.confirmationBody}>
                <p className={styles.confirmationMessage}>
                  {pendingMutationConfirmation.message}
                </p>
                {pendingMutationConfirmation.warnings?.length > 0 && (
                  <ul className={styles.confirmationWarnings}>
                    {pendingMutationConfirmation.warnings.map((warning, index) => (
                      <li key={`${warning}-${index}`}>{warning}</li>
                    ))}
                  </ul>
                )}
                <pre className={styles.confirmationSqlPreview}>
                  {pendingMutationConfirmation.query}
                </pre>
              </div>
              <div className={styles.confirmationActions}>
                <button
                  className={styles.secondaryButton}
                  onClick={() => setPendingMutationConfirmation(null)}
                >
                  Cancel
                </button>
                <button
                  className={styles.runButton}
                  onClick={() =>
                    handleRunQuery(pendingMutationConfirmation.query, {
                      mutationConfirmed: true,
                    })
                  }
                >
                  Confirm and Run
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Context Menu */}
      {contextMenu.visible && contextMenu.table && (
        <div
          className={styles.contextMenu}
          style={{
            position: "fixed",
            top: contextMenu.y,
            left: contextMenu.x,
          }}
          onClick={(e) => e.stopPropagation()}
        >
          <div className={styles.contextMenuItem} onClick={handleCopyName}>
            <Copy size={14} />
            <span>Copy name</span>
          </div>
          <div
            className={styles.contextMenuItem}
            onClick={handleCopyAllColumnNames}
          >
            <Copy size={14} />
            <span>Copy all column names</span>
          </div>
          <div className={styles.divider}></div>
          <div className={styles.contextMenuItem} onClick={handlePreviewData}>
            <Eye size={14} />
            <span>Preview table data</span>
          </div>
          <div
            className={styles.contextMenuItem}
            onMouseEnter={() => setShowSqlSubmenu(true)}
            onMouseLeave={() => setShowSqlSubmenu(false)}
          >
            <FileCode size={14} />
            <span>Generate SQL</span>
            <ChevronRight size={14} className={styles.submenuArrow} />

            {/* SQL Submenu */}
            {showSqlSubmenu && (
              <div className={styles.submenu}>
                <div
                  className={styles.contextMenuItem}
                  onClick={() => handleGenerateSQL("SELECT")}
                >
                  <span>SELECT</span>
                </div>
                {isAdmin ? (
                  <>
                    <div
                      className={styles.contextMenuItem}
                      onClick={() => handleGenerateSQL("INSERT")}
                    >
                      <span>INSERT</span>
                    </div>
                    <div
                      className={styles.contextMenuItem}
                      onClick={() => handleGenerateSQL("UPDATE")}
                    >
                      <span>UPDATE</span>
                    </div>
                    <div
                      className={styles.contextMenuItem}
                      onClick={() => handleGenerateSQL("DELETE")}
                    >
                      <span>DELETE</span>
                    </div>
                  </>
                ) : (
                  <div className={`${styles.contextMenuItem} ${styles.contextMenuItemDisabled}`}>
                    <span>INSERT / UPDATE / DELETE (admins only)</span>
                  </div>
                )}
              </div>
            )}
          </div>
          <div className={styles.divider}></div>
          <div
            className={styles.contextMenuItem}
            onClick={handleViewSchemaText}
          >
            <FileText size={14} />
            <span>View schema text</span>
          </div>
          <div className={styles.contextMenuItem} onClick={handleViewDetail}>
            <Eye size={14} />
            <span>View detail</span>
          </div>
          <div className={styles.contextMenuItem} onClick={handleEditSchema}>
            <Edit size={14} />
            <span>Edit Schema</span>
          </div>
          <div className={styles.divider}></div>
          <div className={styles.contextMenuItem} onClick={handleCopyURL}>
            <Copy size={14} />
            <span>Copy URL</span>
          </div>
        </div>
      )}

      {/* Table Detail Modal */}
      {showDetailModal && detailTable && (
        <div
          className={styles.modalOverlay}
          onClick={() => setShowDetailModal(false)}
        >
          <div className={styles.modal} onClick={(e) => e.stopPropagation()}>
            <div className={styles.modalHeader}>
              <div className={styles.modalTitle}>
                <Table size={20} />
                <h2>Detail for table {detailTable.name}</h2>
              </div>
              <button
                className={styles.closeButton}
                onClick={() => setShowDetailModal(false)}
              >
                <X size={20} />
              </button>
            </div>

            <div className={styles.modalTabs}>
              <button
                className={`${styles.modalTab} ${detailTab === "columns" ? styles.active : ""}`}
                onClick={() => setDetailTab("columns")}
              >
                <Columns size={16} />
                Columns
              </button>
              <button
                className={`${styles.modalTab} ${detailTab === "indexes" ? styles.active : ""}`}
                onClick={() => setDetailTab("indexes")}
              >
                <Hash size={16} />
                Indexes
              </button>
            </div>

            <div className={styles.modalContent}>
              {detailTab === "columns" && (
                <div className={styles.tableWrapper}>
                  <table className={styles.detailTable}>
                    <thead>
                      <tr>
                        <th>Name</th>
                        <th>Type</th>
                        <th>Not Null</th>
                        <th>Primary</th>
                        <th>Default</th>
                        <th>Comment</th>
                      </tr>
                    </thead>
                    <tbody>
                      {detailTable.columns?.map((column, index) => (
                        <tr key={index}>
                          <td>
                            <div className={styles.columnNameCell}>
                              {column.primaryKey ? (
                                <Key size={12} />
                              ) : (
                                <Columns size={12} />
                              )}
                              <span>{column.name}</span>
                            </div>
                          </td>
                          <td>
                            <code>{column.dataType}</code>
                          </td>
                          <td>
                            {!column.nullable ? (
                              <span className={styles.badge}>✓</span>
                            ) : (
                              <span className={styles.nullValue}>—</span>
                            )}
                          </td>
                          <td>
                            {column.primaryKey ? (
                              <span className={styles.badge}>✓</span>
                            ) : (
                              <span className={styles.nullValue}>—</span>
                            )}
                          </td>
                          <td>
                            {column.defaultValue ? (
                              <code>{column.defaultValue}</code>
                            ) : (
                              <span className={styles.nullValue}>NULL</span>
                            )}
                          </td>
                          <td>
                            {column.comment || (
                              <span className={styles.nullValue}>—</span>
                            )}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}

              {detailTab === "indexes" && (
                <div className={styles.tableWrapper}>
                  {loadingIndexes ? (
                    <div className={styles.loadingState}>
                      <div className={styles.spinner}></div>
                      <p>Loading indexes...</p>
                    </div>
                  ) : tableIndexes.length > 0 ? (
                    <table className={styles.detailTable}>
                      <thead>
                        <tr>
                          <th>Name</th>
                          <th>Columns</th>
                          <th>Type</th>
                          <th>Unique</th>
                        </tr>
                      </thead>
                      <tbody>
                        {tableIndexes.map((idx, index) => (
                          <tr key={index}>
                            <td>
                              <code>{idx.name}</code>
                            </td>
                            <td>{idx.columns?.join(", ")}</td>
                            <td>{idx.type || "BTREE"}</td>
                            <td>
                              {idx.unique ? (
                                <span className={styles.badge}>✓</span>
                              ) : (
                                <span className={styles.nullValue}>—</span>
                              )}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  ) : (
                    <div className={styles.emptyState}>
                      <Hash size={48} className={styles.emptyIcon} />
                      <h3>No indexes found</h3>
                      <p>This table has no indexes defined</p>
                    </div>
                  )}
                </div>
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
