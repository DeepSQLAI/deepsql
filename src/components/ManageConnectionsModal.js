"use client";

import { useState, useEffect } from "react";
import {
  X,
  Edit2,
  Trash2,
  Database,
  Loader,
  Plus,
  Shield,
  RefreshCw,
} from "lucide-react";
import styles from "./ManageConnectionsModal.module.css";
import ConnectionWizard from "./ConnectionWizard";
import ConnectionSlowQueryConfig from "./ConnectionSlowQueryConfig";
import SlowQuerySourceModal from "./SlowQuerySourceModal";
import { connectionAPI, brainAPI } from "@/lib/api/client";
import { useAuth } from "@/hooks/useAuth";
import { PERMISSIONS } from "@/lib/permissions";
import { getConnectionAccessBadge, getConnectionAccessLabel } from "@/lib/features";

export default function ManageConnectionsModal({
  isOpen,
  onClose,
  onConnectionDeleted,
  onConnectionSaved,
}) {
  const { isAdmin, hasPermission } = useAuth();
  const [connections, setConnections] = useState([]);
  const [loading, setLoading] = useState(false);
  const [deleteConfirmId, setDeleteConfirmId] = useState(null);
  const [refreshingId, setRefreshingId] = useState(null);
  const [showAddModal, setShowAddModal] = useState(false);
  const [editingConnection, setEditingConnection] = useState(null);
  const [slowQuerySourceConn, setSlowQuerySourceConn] = useState(null);

  useEffect(() => {
    if (isOpen) {
      fetchConnections();
    }
  }, [isOpen]);

  const fetchConnections = async () => {
    try {
      setLoading(true);
      const dbData = await connectionAPI.getAllConnections();
      setConnections(dbData);
    } catch (err) {
      console.error("Failed to fetch connections:", err);
    } finally {
      setLoading(false);
    }
  };

  const handleEdit = (connection) => {
    if (connection?.canManageConfig === false) {
      return;
    }
    setEditingConnection(connection);
  };

  const handleEditConnectionSaved = async () => {
    setEditingConnection(null);
    await fetchConnections();
    if (onConnectionSaved) {
      onConnectionSaved();
    }
  };

  const handleDelete = async (id) => {
    if (deleteConfirmId !== id) {
      setDeleteConfirmId(id);
      setTimeout(() => setDeleteConfirmId(null), 3000);
      return;
    }

    try {
      setLoading(true);
      await connectionAPI.deleteConnection(id);
      await fetchConnections();
      setDeleteConfirmId(null);
      if (onConnectionDeleted) {
        onConnectionDeleted(id);
      }
    } catch (err) {
      console.error("Failed to delete connection:", err);
      alert("Failed to delete connection: " + err.message);
    } finally {
      setLoading(false);
    }
  };

  const handleRefreshSchema = async (connectionId) => {
    try {
      setRefreshingId(connectionId);
      await brainAPI.rescanSchema(connectionId);
      alert(
        "Schema refreshed successfully! Latest schema changes have been synced.",
      );
    } catch (err) {
      console.error("Schema refresh failed:", err);
      alert(
        "Schema refresh failed: " +
          (err.response?.data?.message || err.message),
      );
    } finally {
      setRefreshingId(null);
    }
  };

  const handleNewConnectionSaved = async (newConnectionId) => {
    setShowAddModal(false);
    await fetchConnections();
    if (onConnectionSaved) {
      onConnectionSaved(newConnectionId);
    }
  };

  if (!isOpen) return null;

  // Enforced here rather than only at the call sites: this modal adds, edits and deletes
  // database connections, and it is opened from the sidebar, the Agent view and the user
  // menu. Gating each entry point separately means the next new one silently reopens the
  // hole. The backend already 403s these writes; this keeps the UI honest about it.
  if (!hasPermission(PERMISSIONS.MANAGE_CONNECTIONS)) return null;

  return (
    <div className={styles.overlay} onClick={onClose}>
      <div className={styles.modal} onClick={(e) => e.stopPropagation()}>
        <div className={styles.header}>
          <div className={styles.title}>
            <Database size={24} />
            <h2>Manage Connections</h2>
          </div>
          <div className={styles.headerActions}>
            <button
              className={styles.addButton}
              onClick={() => setShowAddModal(true)}
              title="Add new connection"
            >
              <Plus size={18} />
              Add Connection
            </button>
            <button className={styles.closeButton} onClick={onClose}>
              <X size={20} />
            </button>
          </div>
        </div>

        <div className={styles.content}>
          {loading && connections.length === 0 ? (
            <div className={styles.loading}>
              <Loader className={styles.spinner} size={32} />
              <p>Loading connections...</p>
            </div>
          ) : connections.length === 0 ? (
            <div className={styles.empty}>
              <Database size={48} className={styles.emptyIcon} />
              <h3>No connections found</h3>
              <p>Create a new connection to get started</p>
            </div>
          ) : (
            <div className={styles.tableWrapper}>
              <table className={styles.table}>
                <thead>
                  <tr>
                    <th>Name</th>
                    <th>Access</th>
                    <th>Type</th>
                    <th>Host</th>
                    <th>Port</th>
                    <th>Database</th>
                    <th>Username</th>
                    <th>SSH</th>
                    <th>Slow Query Source</th>
                    <th>Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {connections.map((conn) => (
                    <tr key={conn.id}>
                      <td>
                        <div className="flex flex-col gap-1">
                          <strong>{conn.connectionName}</strong>
                          {getConnectionAccessLabel(conn) && (
                            <span className="text-xs text-gray-500">{getConnectionAccessLabel(conn)}</span>
                          )}
                        </div>
                      </td>
                      <td>
                        <span className={styles.badge}>
                          {getConnectionAccessBadge(conn) || "Unknown"}
                        </span>
                      </td>
                      <td>
                        <span className={styles.badge}>
                          {conn.dbType?.toUpperCase()}
                        </span>
                      </td>
                      <td>
                        <code>{conn.host}</code>
                      </td>
                      <td>
                        <code>{conn.port}</code>
                      </td>
                      <td>
                        <code>{conn.database}</code>
                      </td>
                      <td>{conn.username}</td>
                      <td>
                        {conn.sshEnabled ? (
                          <span
                            className={styles.sshBadge}
                            title={`SSH via ${conn.sshHost || "bastion"}`}
                          >
                            <Shield size={14} />
                          </span>
                        ) : (
                          <span className={styles.noSsh}>-</span>
                        )}
                      </td>
                      <td>
                        <ConnectionSlowQueryConfig
                          connectionId={conn.id}
                          onClick={() => {
                            if (conn.canManageConfig) {
                              setSlowQuerySourceConn(conn)
                            }
                          }}
                        />
                      </td>
                      <td>
                        <div className={styles.actions}>
                          <button
                            className={styles.refreshButton}
                            onClick={() => handleRefreshSchema(conn.id)}
                            title="Refresh schema - resync latest schema changes"
                            disabled={loading || refreshingId === conn.id || !conn.canManageContent}
                          >
                            {refreshingId === conn.id ? (
                              <Loader size={16} className={styles.spinner} />
                            ) : (
                              <RefreshCw size={16} />
                            )}
                          </button>
                          <button
                            className={styles.editButton}
                            onClick={() => handleEdit(conn)}
                            title="Edit connection"
                            disabled={loading || !conn.canManageConfig}
                          >
                            <Edit2 size={16} />
                          </button>
                          <button
                            className={`${styles.deleteButton} ${deleteConfirmId === conn.id ? styles.confirm : ""}`}
                            onClick={() => handleDelete(conn.id)}
                            title={
                              deleteConfirmId === conn.id
                                ? "Click again to confirm"
                                : "Delete connection"
                            }
                            disabled={loading || !conn.canManageConfig}
                          >
                            <Trash2 size={16} />
                            {deleteConfirmId === conn.id && (
                              <span>Confirm?</span>
                            )}
                          </button>
                        </div>
                        {!conn.canManageConfig && (
                          <div className="mt-2 text-xs text-gray-500">
                            This shared connection is read-only for configuration.
                          </div>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </div>

      {/* Add Connection Wizard */}
      <ConnectionWizard
        isOpen={showAddModal}
        onClose={() => setShowAddModal(false)}
        onConnectionSaved={handleNewConnectionSaved}
      />

      {/* Edit Connection Wizard */}
      <ConnectionWizard
        isOpen={editingConnection !== null}
        onClose={() => setEditingConnection(null)}
        onConnectionSaved={handleEditConnectionSaved}
        existingConnection={editingConnection}
      />

      {/* Slow Query Source Modal */}
      {slowQuerySourceConn && (
        <SlowQuerySourceModal
          connectionId={slowQuerySourceConn.id}
          connectionName={slowQuerySourceConn.connectionName}
          dbType={slowQuerySourceConn.dbType}
          onClose={() => setSlowQuerySourceConn(null)}
        />
      )}

    </div>
  );
}
