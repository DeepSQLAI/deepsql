"use client";

import { useRef, useState } from "react";
import {
  Search,
  AlertCircle,
  Upload,
  Clipboard,
  XCircle,
  Pencil,
  Trash2,
  Download,
  Plus,
  RefreshCw,
  ChevronDown,
  ChevronRight,
  Loader,
  Lightbulb,
  Sparkles,
} from "lucide-react";
import Papa from "papaparse";
import { formatTimestamp } from "./utils/formatUtils";
import { TrainingProgress } from "./TrainingProgress";
import { TrainingHistory } from "./TrainingHistory";
import { HelpTooltip } from "./components/HelpTooltip";
import { ActionGuard } from "@/components/ActionGuard";
import styles from "../Core/RagTrainingTab.module.css";

const SYNC_TO_BRAIN_HELP = {
  title: "Sync to Brain",
  description:
    "Embeds your schema and knowledge notes into the AI's memory for smarter answers.",
  impact:
    "With notes: AI uses your domain context (column meanings, valid values, business rules) when generating SQL. Without notes: AI still learns your schema structure (tables, columns, relationships).",
  recommendation:
    "Run after adding or updating notes. First sync builds the foundation; subsequent syncs update it incrementally.",
};

/**
 * Knowledge library - manages domain notes and AI sync (training)
 * Merged from Details + Training tabs for streamlined workflow
 */
export function DetailsLibrary({
  notes = [],
  loading = false,
  error = null,
  searchTerm = "",
  onUpdateSearch,
  onAddNote,
  onEditNote,
  onDeleteNote,
  onBulkUpload,
  // Training props
  trainingStatus = null,
  trainingHistory = [],
  trainingLoading = false,
  onTrain,
  onCancelTraining,
  // Suggestions props
  suggestions = [],
  suggestionsLoading = false,
  onAddSuggestedNote,
}) {
  const fileInputRef = useRef(null);
  const [uploading, setUploading] = useState(false);
  const [uploadSummary, setUploadSummary] = useState(null);
  const [uploadErrors, setUploadErrors] = useState([]);
  const [showPasteModal, setShowPasteModal] = useState(false);
  const [pasteValue, setPasteValue] = useState("");
  const [showSyncHistory, setShowSyncHistory] = useState(false);
  const [showSuggestions, setShowSuggestions] = useState(true);

  // Training state
  const isTrainingRunning =
    trainingStatus?.status === "RUNNING" ||
    trainingStatus?.status === "PENDING";
  const canCancelTraining =
    trainingStatus?.status === "RUNNING" ||
    trainingStatus?.status === "PENDING";
  const syncButtonLabel = isTrainingRunning
    ? "Syncing..."
    : trainingStatus?.status === "COMPLETED"
      ? "Re-sync"
      : "Sync to Brain";

  const normalizeKey = (value) =>
    value
      .toString()
      .trim()
      .toLowerCase()
      .replace(/[\s-]+/g, "_");

  const pickValue = (row, keys) => {
    for (const key of keys) {
      if (
        row[key] !== undefined &&
        row[key] !== null &&
        `${row[key]}`.trim() !== ""
      ) {
        return row[key];
      }
    }
    return "";
  };

  const parseRows = (rows) => {
    const entries = [];
    const errors = [];
    let skipped = 0;

    rows.forEach((row, index) => {
      if (
        !row ||
        Object.values(row).every((value) => `${value ?? ""}`.trim() === "")
      ) {
        skipped++;
        return;
      }

      const normalized = {};
      Object.entries(row).forEach(([key, value]) => {
        normalized[normalizeKey(key)] = value;
      });

      const tableName =
        `${pickValue(normalized, ["table_name", "table", "tablename"])}`.trim();
      const columnName =
        `${pickValue(normalized, ["column_name", "column", "columnname"])}`.trim();
      const details =
        `${pickValue(normalized, ["details", "detail", "description", "note", "notes"])}`.trim();

      if (!tableName) {
        errors.push({ row: index + 2, message: "Missing table_name" });
        return;
      }
      if (!details) {
        errors.push({ row: index + 2, message: "Missing details" });
        return;
      }

      entries.push({ tableName, columnName, details });
    });

    return { entries, errors, skipped };
  };

  const parseFile = async (file) => {
    const extension = file.name.split(".").pop()?.toLowerCase();
    if (extension === "csv" || file.type === "text/csv") {
      return new Promise((resolve, reject) => {
        Papa.parse(file, {
          header: true,
          skipEmptyLines: true,
          complete: (result) => resolve(result.data),
          error: (err) => reject(err),
        });
      });
    }

    if (extension === "xlsx" || extension === "xls") {
      const buffer = await file.arrayBuffer();
      const XLSXModule = await import("xlsx");
      const XLSX = XLSXModule.default || XLSXModule;
      const workbook = XLSX.read(buffer, { type: "array" });
      const firstSheet = workbook.SheetNames?.[0];
      if (!firstSheet) {
        return [];
      }
      const sheet = workbook.Sheets[firstSheet];
      return XLSX.utils.sheet_to_json(sheet, { defval: "" });
    }

    throw new Error("Unsupported file type. Upload a CSV or Excel file.");
  };

  const downloadTemplate = () => {
    const template = [
      "table_name,column_name,details",
      "crm.orders,,Contains order-level details used in analytics dashboards.",
      "sales.orders,,Order headers for the sales schema (use schema.table when names collide).",
      "orders,order_total,Total order value in USD after discounts.",
    ].join("\n");
    const blob = new Blob([template], { type: "text/csv;charset=utf-8;" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = "details_library_template.csv";
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(url);
  };

  const formatSummary = ({ created, updated, skipped, failed }) => {
    const parts = [];
    parts.push(`Created ${created}`);
    parts.push(`updated ${updated}`);
    if (skipped) {
      parts.push(`skipped ${skipped}`);
    }
    if (failed) {
      parts.push(`failed ${failed}`);
    }
    return parts.join(" • ");
  };

  const handleFileSelect = async (event) => {
    const file = event.target.files?.[0];
    event.target.value = "";
    if (!file || !onBulkUpload) return;

    setUploading(true);
    setUploadSummary(null);
    setUploadErrors([]);

    try {
      const rows = await parseFile(file);
      const { entries, errors, skipped } = parseRows(rows);
      setUploadErrors(errors);

      if (entries.length === 0) {
        setUploadSummary({
          message: "No valid rows found to upload.",
        });
        return;
      }

      const result = await onBulkUpload(entries);
      const skippedTotal = (result.skipped || 0) + skipped;
      setUploadSummary({
        message: formatSummary({
          created: result.created || 0,
          updated: result.updated || 0,
          skipped: skippedTotal,
          failed: result.failed || 0,
        }),
      });
    } catch (err) {
      setUploadSummary({
        message: err?.message || "Unable to parse the file.",
      });
    } finally {
      setUploading(false);
    }
  };

  const handleChooseFile = () => {
    if (fileInputRef.current) {
      fileInputRef.current.click();
    }
  };

  const handlePasteUpload = async () => {
    if (!pasteValue.trim() || !onBulkUpload) {
      return;
    }

    setUploading(true);
    setUploadSummary(null);
    setUploadErrors([]);

    try {
      const result = Papa.parse(pasteValue.trim(), {
        header: true,
        skipEmptyLines: true,
      });
      if (result.errors?.length) {
        throw new Error(
          "Unable to parse CSV. Check the header and comma separation.",
        );
      }

      const { entries, errors, skipped } = parseRows(result.data || []);
      setUploadErrors(errors);

      if (entries.length === 0) {
        setUploadSummary({ message: "No valid rows found to upload." });
        return;
      }

      const uploadResult = await onBulkUpload(entries);
      const skippedTotal = (uploadResult.skipped || 0) + skipped;
      setUploadSummary({
        message: formatSummary({
          created: uploadResult.created || 0,
          updated: uploadResult.updated || 0,
          skipped: skippedTotal,
          failed: uploadResult.failed || 0,
        }),
      });
      setShowPasteModal(false);
      setPasteValue("");
    } catch (err) {
      setUploadSummary({
        message: err?.message || "Unable to parse CSV.",
      });
    } finally {
      setUploading(false);
    }
  };

  return (
    <div className={styles.brainNotes}>
      <div className={styles.brainSectionHeader}>
        <div className={styles.brainSectionTitle}>
          <h3>Knowledge</h3>
          <p className={styles.brainSectionDescription}>
            Add domain context that improves AI answers. Sync to embed notes
            into the Brain.
          </p>
        </div>
        <div className={styles.noteHeaderMeta}>
          <span>{notes.length} notes</span>
          <button
            type="button"
            className={`${styles.inlineButton} ${styles.iconButton} ${styles.tooltipButton}`}
            onClick={onAddNote}
            aria-label="Add note"
            data-tooltip-title="Add note"
            data-tooltip-body="Create a table or column note"
          >
            <Plus size={14} />
          </button>
          {onTrain && (
            <ActionGuard action="train-brain">
              <HelpTooltip content={SYNC_TO_BRAIN_HELP}>
                <button
                  type="button"
                  className={styles.trainButton}
                  onClick={onTrain}
                  disabled={trainingLoading || isTrainingRunning}
                  data-testid="sync-to-brain-button"
                >
                  {isTrainingRunning ? (
                    <Loader className={styles.spinner} size={14} />
                  ) : (
                    <RefreshCw size={14} />
                  )}
                  <span>{syncButtonLabel}</span>
                </button>
              </HelpTooltip>
            </ActionGuard>
          )}
          {canCancelTraining && onCancelTraining && (
            <ActionGuard action="train-brain" mode="hide">
              <button
                type="button"
                className={styles.cancelButton}
                onClick={onCancelTraining}
              >
                <XCircle size={14} />
              </button>
            </ActionGuard>
          )}
        </div>
      </div>

      {/* Training Progress - shown inline when active */}
      {isTrainingRunning && <TrainingProgress status={trainingStatus} />}

      {/* Suggested Notes Section - Collapsible */}
      {onAddSuggestedNote && (
        <div className={styles.suggestionsPanel}>
          <button
            type="button"
            className={styles.suggestionsPanelToggle}
            onClick={() => setShowSuggestions(!showSuggestions)}
          >
            <div className={styles.suggestionsPanelTitle}>
              {showSuggestions ? (
                <ChevronDown size={14} />
              ) : (
                <ChevronRight size={14} />
              )}
              <Lightbulb size={14} />
              <span>Suggested Notes</span>
            </div>
            <span className={styles.suggestionsPanelMeta}>
              {suggestionsLoading
                ? "Analyzing..."
                : suggestions.length > 0
                  ? `${suggestions.length} items need documentation`
                  : "Run Key Column Analysis in Overview to get suggestions"}
            </span>
          </button>
          {showSuggestions && !suggestionsLoading && suggestions.length > 0 && (
            <div className={styles.suggestionsList}>
              {suggestions.map((suggestion, index) => {
                const label = suggestion.columnName
                  ? `${suggestion.tableName}.${suggestion.columnName}`
                  : suggestion.tableName;
                return (
                  <div
                    key={`${suggestion.tableName}-${suggestion.columnName || ""}-${index}`}
                    className={styles.suggestionRow}
                  >
                    <div className={styles.suggestionMain}>
                      <div className={styles.suggestionTitleRow}>
                        <span className={styles.suggestionTitle}>{label}</span>
                        <span
                          className={`${styles.suggestionPriority} ${styles[`priority${suggestion.priority}`]}`}
                        >
                          {suggestion.priority}
                        </span>
                      </div>
                      <div className={styles.suggestionReason}>
                        {suggestion.reason}
                      </div>
                      {suggestion.indicators &&
                        suggestion.indicators.length > 0 && (
                          <div className={styles.suggestionIndicators}>
                            {suggestion.indicators
                              .slice(0, 3)
                              .map((indicator, i) => (
                                <span
                                  key={i}
                                  className={styles.suggestionIndicator}
                                >
                                  {indicator}
                                </span>
                              ))}
                          </div>
                        )}
                    </div>
                    <button
                      type="button"
                      className={styles.suggestionAddButton}
                      onClick={() => onAddSuggestedNote(suggestion)}
                      title={
                        suggestion.suggestedPrompt || "Add note for this item"
                      }
                    >
                      <Sparkles size={12} />
                      <span>Add</span>
                    </button>
                  </div>
                );
              })}
            </div>
          )}
        </div>
      )}

      <div className={styles.bulkUploadPanel}>
        <div className={styles.bulkUploadInfo}>
          <div className={styles.bulkUploadTitle}>Bulk upload details</div>
          <p className={styles.bulkUploadHelp}>
            Upload a CSV or Excel file with columns: table_name, column_name (use schema.table for non-public schemas)
            (optional), details. The first sheet is used for Excel.
          </p>
          <div className={styles.bulkUploadMeta}>
            Leave column_name blank to attach details to the table itself.
          </div>
        </div>
        <div className={styles.bulkUploadActions}>
          <input
            ref={fileInputRef}
            type="file"
            accept=".csv,.xlsx,.xls"
            className={styles.bulkUploadInput}
            onChange={handleFileSelect}
          />
          <div className={styles.bulkUploadButtons}>
            <button
              type="button"
              className={`${styles.inlineButton} ${styles.iconButton} ${styles.tooltipButton}`}
              onClick={handleChooseFile}
              disabled={uploading || !onBulkUpload}
              aria-label="Upload file"
              data-tooltip-title="Upload file"
              data-tooltip-body="CSV or Excel"
            >
              <Upload size={14} />
            </button>
            <button
              type="button"
              className={`${styles.inlineButton} ${styles.iconButton} ${styles.tooltipButton}`}
              onClick={() => setShowPasteModal(true)}
              disabled={uploading || !onBulkUpload}
              aria-label="Paste CSV"
              data-tooltip-title="Paste CSV"
              data-tooltip-body="Quick copy/paste"
            >
              <Clipboard size={14} />
            </button>
            <button
              type="button"
              className={`${styles.inlineButton} ${styles.iconButton} ${styles.tooltipButton}`}
              onClick={downloadTemplate}
              disabled={uploading}
              aria-label="Download template"
              data-tooltip-title="Download template"
              data-tooltip-body="CSV example file"
            >
              <Download size={14} />
            </button>
          </div>
          {uploadSummary ? (
            <div className={styles.bulkUploadStatus}>
              {uploadSummary.message}
            </div>
          ) : null}
        </div>
      </div>

      {uploadErrors.length ? (
        <div className={styles.bulkUploadError}>
          <div>Some rows were skipped:</div>
          <ul className={styles.bulkUploadErrorList}>
            {uploadErrors.slice(0, 3).map((error) => (
              <li key={`${error.row}-${error.message}`}>
                Row {error.row}: {error.message}
              </li>
            ))}
          </ul>
          {uploadErrors.length > 3 ? (
            <div>...and {uploadErrors.length - 3} more.</div>
          ) : null}
        </div>
      ) : null}

      <div className={styles.brainFilters}>
        <div className={styles.brainFilterInput}>
          <Search size={14} />
          <input
            type="search"
            value={searchTerm}
            onChange={(e) => onUpdateSearch(e.target.value)}
            placeholder="Search notes"
          />
        </div>
      </div>

      {showPasteModal ? (
        <div
          className={styles.modalOverlay}
          onClick={() => setShowPasteModal(false)}
        >
          <div
            className={styles.modalContent}
            onClick={(e) => e.stopPropagation()}
          >
            <div className={styles.modalHeader}>
              <div>
                <div className={styles.modalTitle}>Paste CSV</div>
                <div className={styles.modalSubtitle}>
                  Use headers: table_name, column_name (optional), details.
                </div>
              </div>
              <button
                className={styles.modalCloseButton}
                onClick={() => setShowPasteModal(false)}
                aria-label="Close"
              >
                <XCircle size={18} />
              </button>
            </div>
            <div className={styles.modalBody}>
              <textarea
                className={styles.bulkUploadTextarea}
                value={pasteValue}
                onChange={(e) => setPasteValue(e.target.value)}
                placeholder="table_name,column_name,details"
              />
              <div className={styles.bulkUploadHint}>
                Leave column_name blank to attach details to the table.
              </div>
              <div className={styles.modalActions}>
                <button
                  type="button"
                  className={styles.inlineButton}
                  onClick={downloadTemplate}
                >
                  Download template
                </button>
                <button
                  type="button"
                  className={styles.trainButton}
                  onClick={handlePasteUpload}
                  disabled={uploading || !pasteValue.trim()}
                >
                  {uploading ? "Uploading..." : "Parse & upload"}
                </button>
                <button
                  type="button"
                  className={styles.cancelButton}
                  onClick={() => setShowPasteModal(false)}
                >
                  Cancel
                </button>
              </div>
            </div>
          </div>
        </div>
      ) : null}

      {loading ? (
        <div className={styles.brainEmpty}>Loading notes...</div>
      ) : error ? (
        <div className={styles.brainError}>
          <AlertCircle size={14} />
          <span>{error}</span>
        </div>
      ) : notes.length ? (
        <div className={styles.noteList}>
          {notes.map((note) => {
            const label = note.columnName
              ? `${note.tableName}.${note.columnName}`
              : note.tableName || "Unknown object";
            return (
              <div key={note.id} className={styles.noteRow}>
                <div className={styles.noteMain}>
                  <div className={styles.noteTitleRow}>
                    <span className={styles.noteTitle}>{label}</span>
                    <div className={styles.noteBadges}>
                      {note.source === "AI_GENERATED" && (
                        <span
                          style={{
                            padding: "2px 6px",
                            borderRadius: "4px",
                            fontSize: "10px",
                            background: "#f0f0ff",
                            color: "#6366f1",
                            fontWeight: 500,
                          }}
                        >
                          AI
                        </span>
                      )}
                      {note.stale ? (
                        <span
                          className={`${styles.noteBadge} ${styles.noteBadgeStale}`}
                          title={note.staleReason || undefined}
                        >
                          Needs review
                        </span>
                      ) : (
                        <span className={styles.noteBadge}>Current</span>
                      )}
                      <span className={styles.noteBadge}>
                        Confidence {note.confidenceScore ?? "--"}
                      </span>
                    </div>
                  </div>
                  <div className={styles.noteText}>{note.noteText}</div>
                  <div className={styles.noteMeta}>
                    <span>
                      {formatTimestamp(note.updatedAt || note.createdAt)}
                    </span>
                    {note.createdBy ? <span>by {note.createdBy}</span> : null}
                  </div>
                </div>
                <div className={styles.noteActions}>
                  <button
                    type="button"
                    className={`${styles.inlineButton} ${styles.iconButton} ${styles.tooltipButton}`}
                    onClick={() => onEditNote(note)}
                    aria-label="Edit note"
                    data-tooltip-title="Edit note"
                    data-tooltip-body="Update this note"
                  >
                    <Pencil size={14} />
                  </button>
                  <button
                    type="button"
                    className={`${styles.inlineButton} ${styles.iconButton} ${styles.tooltipButton}`}
                    onClick={() => onDeleteNote(note.id)}
                    aria-label="Delete note"
                    data-tooltip-title="Delete note"
                    data-tooltip-body="Remove this note"
                  >
                    <Trash2 size={14} />
                  </button>
                </div>
              </div>
            );
          })}
        </div>
      ) : (
        <div className={styles.brainEmpty}>
          No notes added yet. Add domain context to improve AI answers.
        </div>
      )}

      {/* Collapsible Sync History */}
      {trainingHistory && trainingHistory.length > 0 && (
        <div className={styles.syncHistorySection}>
          <button
            type="button"
            className={styles.syncHistoryToggle}
            onClick={() => setShowSyncHistory(!showSyncHistory)}
            data-testid="sync-history-toggle"
          >
            {showSyncHistory ? (
              <ChevronDown size={14} />
            ) : (
              <ChevronRight size={14} />
            )}
            <span>Sync History ({trainingHistory.length})</span>
          </button>
          {showSyncHistory && <TrainingHistory history={trainingHistory} />}
        </div>
      )}
    </div>
  );
}
