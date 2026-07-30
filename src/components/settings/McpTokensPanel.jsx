"use client";

import { useEffect, useMemo, useState } from "react";
import { Copy, KeyRound, Plus, RefreshCw, Trash2 } from "lucide-react";
import Button from "@/components/ui/Button";
import { mcpTokensAPI } from "@/lib/api/client";
import styles from "./McpTokensPanel.module.css";

function formatDateTime(value) {
  if (!value) return "Never";
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return "Invalid date";
  return parsed.toLocaleString();
}

export default function McpTokensPanel() {
  const [tokens, setTokens] = useState([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [creating, setCreating] = useState(false);
  const [revokingId, setRevokingId] = useState(null);
  const [error, setError] = useState("");
  const [createError, setCreateError] = useState("");
  const [name, setName] = useState("");
  const [expiresAt, setExpiresAt] = useState("");
  const [newTokenValue, setNewTokenValue] = useState("");
  const [copied, setCopied] = useState("");

  const hasTokens = tokens.length > 0;

  const sortedTokens = useMemo(
    () =>
      [...tokens].sort((a, b) => {
        const left = a.createdAt ? new Date(a.createdAt).getTime() : 0;
        const right = b.createdAt ? new Date(b.createdAt).getTime() : 0;
        return right - left;
      }),
    [tokens],
  );

  useEffect(() => {
    void loadTokens();
  }, []);

  useEffect(() => {
    if (!copied) return undefined;
    const timer = window.setTimeout(() => setCopied(""), 1800);
    return () => window.clearTimeout(timer);
  }, [copied]);

  const loadTokens = async ({ silent = false } = {}) => {
    if (silent) {
      setRefreshing(true);
    } else {
      setLoading(true);
    }
    setError("");
    try {
      const data = await mcpTokensAPI.listTokens();
      setTokens(Array.isArray(data) ? data : []);
    } catch (err) {
      setError(err.message || "Failed to load MCP tokens");
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  };

  const handleCreate = async (event) => {
    event.preventDefault();
    setCreateError("");
    setNewTokenValue("");

    const trimmedName = name.trim();
    if (!trimmedName) {
      setCreateError("Token name is required.");
      return;
    }

    setCreating(true);
    try {
      const created = await mcpTokensAPI.createToken({
        name: trimmedName,
        expiresAt: expiresAt || null,
      });
      setName("");
      setExpiresAt("");
      setNewTokenValue(created.token || "");
      await loadTokens({ silent: true });
    } catch (err) {
      setCreateError(err.message || "Failed to create MCP token");
    } finally {
      setCreating(false);
    }
  };

  const handleRevoke = async (tokenId) => {
    setRevokingId(tokenId);
    setError("");
    try {
      await mcpTokensAPI.revokeToken(tokenId);
      await loadTokens({ silent: true });
    } catch (err) {
      setError(err.message || "Failed to revoke MCP token");
    } finally {
      setRevokingId(null);
    }
  };

  const handleCopy = async (value, label) => {
    try {
      await navigator.clipboard.writeText(value);
      setCopied(label);
    } catch {
      setCopied("");
    }
  };

  return (
    <div className={styles.panel}>
      <div className={styles.hero}>
        <div>
          <h3 className={styles.title}>MCP Tokens</h3>
          <p className={styles.subtitle}>
            Create personal access tokens for Claude Code, Codex, and other MCP
            clients. Tokens authenticate as your current DeepSQL user.
          </p>
        </div>
        <Button
          variant="secondary"
          size="small"
          onClick={() => loadTokens({ silent: true })}
          loading={refreshing}
        >
          <RefreshCw size={14} />
          Refresh
        </Button>
      </div>

      <div className={styles.callout}>
        <KeyRound size={16} />
        <span>
          Tokens are shown only once at creation. Store them in your MCP client
          config immediately.
        </span>
      </div>

      <div className={styles.grid}>
        <section className={styles.card}>
          <div className={styles.cardHeader}>
            <h4>Create Token</h4>
            <span className={styles.muted}>Bound to your user identity</span>
          </div>

          <form className={styles.form} onSubmit={handleCreate}>
            <label className={styles.field}>
              <span className={styles.label}>Token name</span>
              <input
                className={styles.input}
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder="Claude Desktop"
                maxLength={120}
              />
            </label>

            <label className={styles.field}>
              <span className={styles.label}>Expires at</span>
              <input
                className={styles.input}
                type="datetime-local"
                value={expiresAt}
                onChange={(e) => setExpiresAt(e.target.value)}
              />
            </label>

            {createError ? (
              <p className={styles.error}>{createError}</p>
            ) : null}

            <Button type="submit" loading={creating}>
              <Plus size={14} />
              Create token
            </Button>
          </form>

          {newTokenValue ? (
            <div className={styles.tokenReveal}>
              <div className={styles.tokenRevealHeader}>
                <strong>New token</strong>
                <Button
                  variant="secondary"
                  size="small"
                  onClick={() => handleCopy(newTokenValue, "token")}
                >
                  <Copy size={14} />
                  {copied === "token" ? "Copied" : "Copy"}
                </Button>
              </div>
              <code className={styles.tokenValue}>{newTokenValue}</code>
            </div>
          ) : null}
        </section>

        <section className={styles.card}>
          <div className={styles.cardHeader}>
            <h4>Active Tokens</h4>
            <span className={styles.muted}>
              {hasTokens ? `${sortedTokens.length} token(s)` : "No tokens yet"}
            </span>
          </div>

          {loading ? (
            <p className={styles.muted}>Loading tokens...</p>
          ) : null}

          {!loading && error ? <p className={styles.error}>{error}</p> : null}

          {!loading && !error && !hasTokens ? (
            <div className={styles.emptyState}>
              <KeyRound size={18} />
              <span>No MCP tokens created yet.</span>
            </div>
          ) : null}

          {!loading && !error && hasTokens ? (
            <div className={styles.list}>
              {sortedTokens.map((token) => (
                <div key={token.id} className={styles.tokenRow}>
                  <div className={styles.tokenMeta}>
                    <div className={styles.tokenNameRow}>
                      <strong>{token.name}</strong>
                      <span className={styles.badge}>{token.status}</span>
                    </div>
                    <div className={styles.tokenDetails}>
                      <span>{token.tokenPrefix}</span>
                      <span>Created {formatDateTime(token.createdAt)}</span>
                      <span>Last used {formatDateTime(token.lastUsedAt)}</span>
                      <span>
                        Expires{" "}
                        {token.expiresAt ? formatDateTime(token.expiresAt) : "Never"}
                      </span>
                    </div>
                  </div>
                  <Button
                    variant="secondary"
                    size="small"
                    onClick={() => handleRevoke(token.id)}
                    loading={revokingId === token.id}
                  >
                    <Trash2 size={14} />
                    Revoke
                  </Button>
                </div>
              ))}
            </div>
          ) : null}
        </section>
      </div>
    </div>
  );
}
