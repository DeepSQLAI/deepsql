"use client";

import { useMemo, useState, useCallback } from "react";
import { Database } from "lucide-react";
import { brainAPI } from "@/lib/api/client";
import {
  BrainErrorBoundary,
  BrainOverview,
  BrainAnalyticsLayout,
  KeyColumnsPanel,
  BrainOverviewHero,
  NoteModal,
  AmbiguityModal,
  ActionConfirmModal,
  BCNFModal,
  BCNFReviewModal,
  useBrainData,
  useBrainNotes,
  useBrainTraining,
  useConnectionInfo,
  useBrainState,
  buildBcnfSuggestions,
} from "../Brain";
import { SchemaDocsPanel } from "../Brain/SchemaDocs/SchemaDocsPanel";
import styles from "./RagTrainingTab.module.css";

export default function RagTrainingTab({ connectionId }) {
  // Tab navigation state
  const [activeTab, setActiveTab] = useState("overview");

  // Custom hooks for data fetching
  const connectionName = useConnectionInfo(connectionId);
  const brainData = useBrainData(connectionId);
  const brainNotes = useBrainNotes(connectionId);
  const training = useBrainTraining(connectionId);

  // UI state management with reducer
  const {
    state,
    openModal,
    closeModal,
    updateNoteForm,
    resetNoteForm,
    updateAmbiguityForm,
    updateFilter,
    setLoading,
    setResult,
    clearResult,
  } = useBrainState();

  // Computed values for table/column options
  const tableOptions = useMemo(() => {
    return (brainData.data?.tables || [])
      .map((table) => table.tableName)
      .filter(Boolean)
      .sort((a, b) => a.localeCompare(b));
  }, [brainData.data]);

  const columnOptions = useMemo(() => {
    if (!state.forms.note.tableName) {
      return [];
    }
    return (brainData.data?.columns || [])
      .filter((column) => column.tableName === state.forms.note.tableName)
      .map((column) => column.columnName)
      .filter(Boolean)
      .sort((a, b) => a.localeCompare(b));
  }, [brainData.data, state.forms.note.tableName]);

  // Handler: Profile columns
  const handleProfile = async () => {
    if (!connectionId) return;

    setLoading("profile", true);
    try {
      const result = await brainAPI.profileConnection(connectionId);
      setResult("profile", result);
      brainData.refresh();
    } catch (err) {
      console.error("Failed to profile columns:", err);
    } finally {
      setLoading("profile", false);
    }
  };

  // Handler: Rescan schema
  const handleRescanSchema = async () => {
    if (!connectionId) return;

    setLoading("rescan", true);
    try {
      await brainAPI.rescanSchema(connectionId);
      brainData.refresh();
    } catch (err) {
      console.error("Failed to rescan schema:", err);
    } finally {
      setLoading("rescan", false);
    }
  };

  // Handler: Action confirm (train/profile/rescan)
  const handleActionConfirm = async () => {
    const action = state.modals.action;
    closeModal("action");

    if (action === "train") {
      await training.train();
      brainData.refresh();
    } else if (action === "profile") {
      await handleProfile();
    } else if (action === "rescan") {
      await handleRescanSchema();
    }
  };

  // Handler: Open note modal
  const handleOpenNoteModal = (item = {}) => {
    const scopeType = (
      item.objectType ||
      item.scopeType ||
      "TABLE"
    ).toUpperCase();
    const noteData = {
      noteId: item.noteId || item.id || null,
      scopeType,
      tableName: item.tableName || "",
      columnName: item.columnName || "",
      source: item.source || null,
      reason: item.reason || null,
      ambiguousTables: item.ambiguousTables || [],
      locked: Boolean(item.locked),
    };

    openModal("note", noteData);
    updateNoteForm({
      scopeType,
      tableName: item.tableName || "",
      columnName: item.columnName || "",
      noteText: item.noteText || "",
    });
    clearResult("noteAction");
  };

  // Handler: Submit note
  const handleNoteSubmit = async () => {
    if (!state.modals.note || !connectionId) return;

    setLoading("noteSaving", true);
    clearResult("noteAction");

    try {
      const createdBy =
        typeof window !== "undefined" ? localStorage.getItem("username") : null;
      const payload = {
        connectionId,
        scopeType: state.forms.note.scopeType,
        tableName: state.forms.note.tableName,
        columnName:
          state.forms.note.scopeType === "COLUMN"
            ? state.forms.note.columnName
            : null,
        noteText: state.forms.note.noteText,
        createdBy: createdBy || null,
      };

      if (state.modals.note.noteId) {
        await brainAPI.updateNote(state.modals.note.noteId, payload);
      } else {
        await brainAPI.createNote(payload);
      }

      closeModal("note");
      resetNoteForm();
      brainNotes.refresh();
      brainData.refresh();
    } catch (err) {
      console.error("Failed to save note:", err);
      setResult("noteAction", err?.message || "Unable to save note.");
    } finally {
      setLoading("noteSaving", false);
    }
  };

  // Handler: Open ambiguity modal
  const handleOpenAmbiguityModal = (item) => {
    if (!item) return;

    const tables = item.ambiguousTables || [];
    const defaultTable =
      item.tableName && tables.includes(item.tableName)
        ? item.tableName
        : tables[0] || "";

    openModal("ambiguity", {
      columnName: item.columnName,
      tableName: item.tableName,
      ambiguousTables: tables,
    });

    updateAmbiguityForm({
      selection: defaultTable,
      filter: "",
      showAll: false,
    });
  };

  // Handler: Resolve ambiguity
  const handleResolveAmbiguity = async () => {
    if (
      !state.modals.ambiguity ||
      !connectionId ||
      !state.forms.ambiguity.selection
    )
      return;

    setLoading("ambiguitySaving", true);
    try {
      await brainAPI.resolveAmbiguity({
        connectionId,
        columnName: state.modals.ambiguity.columnName,
        preferredTable: state.forms.ambiguity.selection,
      });
      closeModal("ambiguity");
      brainData.refresh();
    } catch (err) {
      console.error("Failed to resolve ambiguity:", err);
    } finally {
      setLoading("ambiguitySaving", false);
    }
  };

  // Handler: Create BCNF task
  const handleCreateBcnfTask = async (payload) => {
    if (!payload || !connectionId) return;

    const suggestions = payload.suggestions?.length
      ? payload.suggestions
      : buildBcnfSuggestions(payload.issues);

    try {
      const createdBy =
        typeof window !== "undefined" ? localStorage.getItem("username") : null;
      await brainAPI.createTask({
        connectionId,
        taskType: "BCNF_REVIEW",
        tableName: payload.tableName,
        tableLabel: payload.tableLabel,
        bcnfScore: payload.bcnfScore,
        issues: payload.issues || [],
        suggestions,
        createdBy: createdBy || null,
      });
      setResult("bcnfTask", "Task saved to Brain tasks");
    } catch (err) {
      console.error("Failed to save BCNF task:", err);
      setResult("bcnfTask", "Unable to save task.");
    }

    setTimeout(() => clearResult("bcnfTask"), 2500);
  };

  // Early return if no connection selected
  if (!connectionId) {
    return (
      <div className={styles.container}>
        <div className={styles.emptyState}>
          <Database size={32} />
          <h3>Select a connection</h3>
          <p>Choose a connection to view Brain understanding and training.</p>
        </div>
      </div>
    );
  }

  return (
    <BrainErrorBoundary>
      <div className={styles.container}>
        {/* Overview Tab */}
        {activeTab === "overview" && (
          <BrainOverview
            connectionId={connectionId}
            onNavigateToTab={setActiveTab}
            activeTab={activeTab}
            onTabChange={setActiveTab}
          />
        )}

        {/* Analytics Tab */}
        {activeTab === "analytics" && (
          <BrainAnalyticsLayout
            connectionId={connectionId}
            activeTab={activeTab}
            onTabChange={setActiveTab}
          />
        )}

        {/* Schema Docs Tab */}
        {activeTab === "knowledge" && (
          <SchemaDocsPanel
            connectionId={connectionId}
            activeTab={activeTab}
            onTabChange={setActiveTab}
            onTrain={() => openModal("action", "train")}
            trainingStatus={training.status}
          />
        )}

        {/* Key Column Analysis Tab */}
        {activeTab === "key-columns" && (
          <div
            style={{ padding: "24px", background: "white", minHeight: "100%" }}
          >
            <BrainOverviewHero
              title="Key Column Analysis"
              subtitle="Important columns identified from query patterns (JOINs, WHERE, GROUP BY)."
              activeTab={activeTab}
              onTabChange={setActiveTab}
              connectionId={connectionId}
            />
            <div style={{ marginTop: "24px" }}>
              <KeyColumnsPanel connectionId={connectionId} hideCTAs />
            </div>
          </div>
        )}

        {/* Modals */}
        <NoteModal
          isOpen={!!state.modals.note}
          noteData={state.modals.note}
          noteForm={state.forms.note}
          onUpdateForm={(updates) => updateNoteForm(updates)}
          onSubmit={handleNoteSubmit}
          onClose={() => closeModal("note")}
          tableOptions={tableOptions}
          columnOptions={columnOptions}
          saving={state.loading.noteSaving}
          actionStatus={state.results.noteAction}
        />

        <AmbiguityModal
          isOpen={!!state.modals.ambiguity}
          ambiguityData={state.modals.ambiguity}
          selection={state.forms.ambiguity.selection}
          filter={state.forms.ambiguity.filter}
          showAll={state.forms.ambiguity.showAll}
          onUpdateSelection={(value) =>
            updateAmbiguityForm({ selection: value })
          }
          onUpdateFilter={(value) => updateAmbiguityForm({ filter: value })}
          onToggleShowAll={() =>
            updateAmbiguityForm({ showAll: !state.forms.ambiguity.showAll })
          }
          onSubmit={handleResolveAmbiguity}
          onClose={() => closeModal("ambiguity")}
          saving={state.loading.ambiguitySaving}
        />

        <ActionConfirmModal
          isOpen={!!state.modals.action}
          actionType={state.modals.action}
          onConfirm={handleActionConfirm}
          onClose={() => closeModal("action")}
          loading={
            (state.modals.action === "train" && training.loading) ||
            (state.modals.action === "profile" && state.loading.profile) ||
            (state.modals.action === "rescan" && state.loading.rescan)
          }
        />

        <BCNFModal
          isOpen={!!state.modals.bcnf}
          bcnfData={state.modals.bcnf}
          onCreateTask={handleCreateBcnfTask}
          onClose={() => closeModal("bcnf")}
          taskStatus={state.results.bcnfTask}
        />

        <BCNFReviewModal
          isOpen={state.modals.bcnfReview}
          tables={brainData.data?.tables || []}
          searchTerm={state.filters.bcnfReviewSearch}
          onUpdateSearch={(value) => updateFilter("bcnfReviewSearch", value)}
          onViewDetails={(table) => {
            closeModal("bcnfReview");
            openModal("bcnf", table);
          }}
          onClose={() => closeModal("bcnfReview")}
        />
      </div>
    </BrainErrorBoundary>
  );
}
