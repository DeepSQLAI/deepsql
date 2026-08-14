"use client";

import { useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import { schemaAPI, brainAPI, companyKnowledgeAPI } from "@/lib/api/client";
import { queryKeys } from "@/lib/queryKeys";
import {
  canonicalTableReference,
  isDefaultSchema,
  stripIdentQuotes,
} from "@/lib/schemaNames";

function normalizeIdentifier(value) {
  return stripIdentQuotes(value).toLowerCase();
}

function identifierTail(value, segments = 1) {
  const normalized = normalizeIdentifier(value)
  if (!normalized) return ""
  const parts = normalized.split(".").filter(Boolean)
  if (parts.length <= segments) return normalized
  return parts.slice(-segments).join(".")
}

function referenceAliases(value) {
  const normalized = normalizeIdentifier(value)
  if (!normalized) return new Set()
  return new Set([normalized, identifierTail(normalized, 1), identifierTail(normalized, 2)].filter(Boolean))
}

function tableAliases(table) {
  const canonical = normalizeIdentifier(canonicalTableReference(table))
  const bare = normalizeIdentifier(table?.tableName || table?.name || "")
  return new Set([
    canonical,
    bare,
    identifierTail(canonical, 1),
    identifierTail(canonical, 2),
  ].filter(Boolean))
}

function columnAliases(table, columnName) {
  const canonicalTable = canonicalTableReference(table)
  const bareTable = table?.tableName || table?.name || ""
  return new Set([
    normalizeIdentifier(`${canonicalTable}.${columnName || ""}`),
    normalizeIdentifier(`${bareTable}.${columnName || ""}`),
    identifierTail(`${canonicalTable}.${columnName || ""}`, 2),
  ].filter(Boolean))
}

/**
 * Fetches schema metadata, brain notes, and table classifications,
 * then merges them into a unified model for the Schema Docs tab.
 *
 * Schema API returns: { schema: { tables: [{ name, columns: [{ name, dataType, ... }], rowCount, ... }] } }
 * Notes API returns: [{ tableName, columnName, scopeType, noteText, source, id, ... }]
 * Classifications API returns: [{ tableName, role/tableRole }]
 */
export function useSchemaDocsData(connectionId) {
  const schemaQuery = useQuery({
    queryKey: queryKeys.schema.metadata(connectionId),
    queryFn: () => schemaAPI.getSchema(connectionId),
    enabled: Boolean(connectionId),
  });

  const notesQuery = useQuery({
    queryKey: queryKeys.brain.notes(connectionId, {}),
    queryFn: () => brainAPI.getNotes(connectionId),
    enabled: Boolean(connectionId),
  });

  const classificationsQuery = useQuery({
    queryKey: queryKeys.brain.tableClassifications(connectionId, null),
    queryFn: () => brainAPI.getTableClassifications(connectionId),
    enabled: Boolean(connectionId),
  });

  const companyKnowledgeQuery = useQuery({
    queryKey: queryKeys.companyKnowledge.list(connectionId),
    queryFn: () => companyKnowledgeAPI.list(connectionId),
    enabled: Boolean(connectionId),
  })

  const merged = useMemo(() => {
    const raw = schemaQuery.data;
    const notes = notesQuery.data || [];
    const classifications = classificationsQuery.data || [];
    const knowledgeEntries = companyKnowledgeQuery.data || []

    // Handle nested schema response: { schema: { tables: [...] } }
    // Also handle flat response: { tables: [...], columns: [...] }
    const schemaTables = raw?.schema?.tables || raw?.tables || [];

    if (!raw || schemaTables.length === 0) {
      return {
        tables: [],
        coverage: { tables: 0, totalTables: 0, columns: 0, totalColumns: 0 },
      };
    }

    // Build note lookup maps (case-insensitive).
    // Notes may use schema-qualified names (e.g. "analytics_db.USER") while
    // the schema API returns bare names ("USER"). Index by both forms.
    //
    // When two notes match the same (table, column), pick by source authority:
    //   USER > CODE_DERIVED > CSV_IMPORT > AI_GENERATED > unknown
    // Confidence is a tiebreak. This prevents an older AI_GENERATED row from
    // shadowing a newer code-derived or user-authored description.
    const SOURCE_PRIORITY = {
      USER: 5,
      CODE_DERIVED: 4,
      CSV_IMPORT: 3,
      AI_GENERATED: 2,
    };
    const noteRank = (note) => {
      const src = (note?.source || "").toUpperCase();
      const base = (SOURCE_PRIORITY[src] ?? 1) * 1000;
      const conf = Number(note?.confidenceScore ?? note?.confidence ?? 0) || 0;
      return base + conf;
    };
    const considerWinner = (current, candidate) => {
      if (!current) return candidate;
      return noteRank(candidate) > noteRank(current) ? candidate : current;
    };

    const tableNotes = {};
    const columnNotes = {};
    for (const note of notes) {
      const rawName = (note.tableName || "").toLowerCase();
      if (!rawName) continue;
      const bareName = rawName.includes(".")
        ? rawName.split(".").pop()
        : rawName;
      // Index qualified notes only under their full key so crm.orders and
      // sales.orders never share a slot. Bare notes stay under the bare key
      // for default-schema / legacy rows.
      const keys = rawName.includes(".") ? [rawName] : [bareName];
      if (note.scopeType === "TABLE" || (!note.scopeType && !note.columnName)) {
        for (const k of keys) {
          tableNotes[k] = considerWinner(tableNotes[k], note);
        }
      } else if (note.columnName) {
        const cKey = note.columnName.toLowerCase();
        for (const k of keys) {
          if (!columnNotes[k]) columnNotes[k] = {};
          columnNotes[k][cKey] = considerWinner(columnNotes[k][cKey], note);
        }
      }
    }

    // Build classification lookup (case-insensitive). Prefer schema.table keys.
    const roleMap = {};
    for (const c of classifications) {
      const bare = (c.tableName || "").toLowerCase();
      const schema = (c.schemaName || c.schema || "").toLowerCase();
      const qualified =
        schema && !isDefaultSchema(schema) ? `${schema}.${bare}` : bare;
      if (qualified) roleMap[qualified] = c.role || c.tableRole;
      // Bare fallback only when classification itself is unqualified.
      if (bare && !schema) roleMap[bare] = c.role || c.tableRole;
    }

    let totalTablesDocumented = 0;
    let totalColumns = 0;
    let totalColumnsDocumented = 0;

    const tables = schemaTables.map((table) => {
      // Normalize: API uses `name`, plan assumed `tableName`
      const tableName = table.tableName || table.name || "";
      const tableReference = canonicalTableReference(table)
      const refKey = normalizeIdentifier(tableReference);
      const bareKey = normalizeIdentifier(tableName);
      // Prefer exact reference match; only fall back to bare for default-schema tables.
      const tableNote =
        tableNotes[refKey] ||
        (refKey === bareKey ? tableNotes[bareKey] : null);
      const colNotes =
        columnNotes[refKey] ||
        (refKey === bareKey ? columnNotes[bareKey] : {}) ||
        {};
      const role = roleMap[refKey] || (refKey === bareKey ? roleMap[bareKey] : null) || null;
      const tableAliasSet = tableAliases(table)
      const linkedKnowledge = knowledgeEntries.filter((entry) => {
        const linkedTables = Array.isArray(entry?.linkedTables) ? entry.linkedTables : []
        const linkedColumns = Array.isArray(entry?.linkedColumns) ? entry.linkedColumns : []
        const tableMatch = linkedTables.some((linkedTable) =>
          Array.from(referenceAliases(linkedTable)).some((alias) => tableAliasSet.has(alias))
        )
        if (tableMatch) return true
        return linkedColumns.some((linkedColumn) => {
          return Array.from(referenceAliases(linkedColumn)).some((alias) => {
            if (tableAliasSet.has(alias)) return true
            return Array.from(tableAliasSet).some((tableAlias) => alias.startsWith(`${tableAlias}.`))
          })
        })
      })

      // Columns can be nested (table.columns) or flat (schema.columns grouped)
      const rawCols = table.columns || [];
      const columns = rawCols.map((col) => {
        const columnName = col.columnName || col.name || "";
        const cKey = columnName.toLowerCase();
        const note = colNotes[cKey] || null;
        const aliases = columnAliases(table, columnName)
        const companyKnowledgeCount = knowledgeEntries.filter((entry) => {
          const linkedColumns = Array.isArray(entry?.linkedColumns) ? entry.linkedColumns : []
          return linkedColumns.some((linkedColumn) =>
            Array.from(referenceAliases(linkedColumn)).some((alias) => aliases.has(alias))
          )
        }).length
        return {
          columnName,
          dataType: col.dataType || col.columnType || "unknown",
          primaryKey: col.primaryKey || col.pk || false,
          nullable: col.nullable !== false,
          note,
          companyKnowledgeCount,
          reference: tableReference ? `${tableReference}.${columnName}` : `${tableName}.${columnName}`,
        };
      });

      const columnsDocumented = columns.filter((c) => c.note).length;
      totalColumns += columns.length;
      totalColumnsDocumented += columnsDocumented;
      if (tableNote) totalTablesDocumented++;

      return {
        tableName,
        tableReference,
        schemaName: table.schema || table.schemaName || "",
        rowCount: table.rowCount,
        note: tableNote,
        role,
        columns,
        columnsDocumented,
        columnsTotal: columns.length,
        companyKnowledgeCount: linkedKnowledge.length,
      };
    });

    // Sort by qualified reference so schemas cluster together
    tables.sort((a, b) =>
      (a.tableReference || a.tableName).localeCompare(b.tableReference || b.tableName)
    );

    return {
      tables,
      coverage: {
        tables: totalTablesDocumented,
        totalTables: tables.length,
        columns: totalColumnsDocumented,
        totalColumns,
      },
    };
  }, [schemaQuery.data, notesQuery.data, classificationsQuery.data, companyKnowledgeQuery.data]);

  return {
    ...merged,
    isLoading: schemaQuery.isLoading || notesQuery.isLoading || companyKnowledgeQuery.isLoading,
    error: schemaQuery.error || notesQuery.error || companyKnowledgeQuery.error,
  };
}
