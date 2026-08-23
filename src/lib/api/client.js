import axios from "axios";

// Use environment variable for API URL.
// In dev: use empty string so requests go through the Vite proxy.
// In prod: use VITE_API_URL when provided, otherwise use same-origin /api via nginx.
const getApiBaseUrl = () => {
  if (import.meta.env.DEV) {
    return ""; // Vite dev server proxies /api to backend
  }
  return import.meta.env.VITE_API_URL || "";
};

export const API_BASE_URL = getApiBaseUrl();
export const AUTH_CHANGE_EVENT = "deepsql-auth-change";

const AUTH_PUBLIC_PATHS = ["/login", "/signup", "/activate"];

const isPublicAuthPath = (pathname = "") =>
  AUTH_PUBLIC_PATHS.some((prefix) => pathname.startsWith(prefix));

const dispatchAuthChange = (detail) => {
  if (typeof window === "undefined") return;
  window.dispatchEvent(new CustomEvent(AUTH_CHANGE_EVENT, { detail }));
};

const apiClient = axios.create({
  baseURL: API_BASE_URL,
  headers: {
    "Content-Type": "application/json",
  },
  withCredentials: true,
  timeout: 120000,
});

let refreshPromise = null;

export const requestSessionRefresh = async () => {
  if (!refreshPromise) {
    refreshPromise = axios
      .post(
        `${API_BASE_URL}/api/auth/refresh`,
        {},
        {
          withCredentials: true,
          timeout: 120000,
          headers: { "Content-Type": "application/json" },
        },
      )
      .then((response) => {
        dispatchAuthChange({ action: "refresh", payload: response.data });
        return response.data;
      })
      .finally(() => {
        refreshPromise = null;
      });
  }
  return refreshPromise;
};

const shouldSkipRefresh = (requestUrl = "") => {
  return (
    requestUrl.includes("/api/auth/email/start") ||
    requestUrl.includes("/api/auth/email/verify") ||
    requestUrl.includes("/api/auth/login") ||
    requestUrl.includes("/api/auth/signup") ||
    requestUrl.includes("/api/auth/refresh") ||
    requestUrl.includes("/api/auth/invite/preview") ||
    requestUrl.includes("/api/auth/invite/accept") ||
    requestUrl.includes("/api/auth/google/start") ||
    requestUrl.includes("/api/auth/google/callback") ||
    requestUrl.includes("/api/admin/bootstrap/link")
  );
};

// Response interceptor
apiClient.interceptors.response.use(
  (response) => {
    return response;
  },
  async (error) => {
    if (error.response) {
      if (error.response.status === 401 && typeof window !== "undefined") {
        const originalRequest = error.config || {};
        const requestUrl = originalRequest.url || "";

        if (!originalRequest._retry && !shouldSkipRefresh(requestUrl)) {
          originalRequest._retry = true;
          try {
            await requestSessionRefresh();
            return apiClient(originalRequest);
          } catch {
            if (!isPublicAuthPath(window.location.pathname)) {
              dispatchAuthChange({ action: "logout", redirect: true });
            }
          }
        }
      }
      const message =
        error.response.data?.message || error.message || "An error occurred";
      const enhancedError = new Error(message);
      enhancedError.status = error.response.status;
      enhancedError.code = error.code;
      enhancedError.responseData = error.response.data;
      enhancedError.errorCode = error.response.data?.errorCode;
      enhancedError.requiresConfirmation =
        error.response.data?.requiresConfirmation === true;
      return Promise.reject(enhancedError);
    } else if (error.request) {
      // Request made but no response
      return Promise.reject(
        new Error("Network error: Could not reach the server"),
      );
    } else {
      // Something else happened
      return Promise.reject(error);
    }
  },
);

// Auth API
export const authAPI = {
  getCurrentUser: async () => {
    const response = await apiClient.get("/api/auth/me");
    return response.data;
  },

  login: async ({ email, password }) => {
    const response = await apiClient.post("/api/auth/login", { email, password });
    return response.data;
  },

  startEmailLogin: async (challengeId) => {
    const response = await apiClient.post("/api/auth/email/start", { challengeId });
    return response.data;
  },

  verifyEmailOtp: async ({ challengeId, otp }) => {
    const response = await apiClient.post("/api/auth/email/verify", {
      challengeId,
      otp,
    });
    return response.data;
  },

  startMfaEnrollment: async (challengeId) => {
    const response = await apiClient.post("/api/auth/mfa/enroll/start", {
      challengeId,
    });
    return response.data;
  },

  confirmMfaEnrollment: async ({ challengeId, code }) => {
    const response = await apiClient.post("/api/auth/mfa/enroll/confirm", {
      challengeId,
      code,
    });
    return response.data;
  },

  verifyMfa: async ({ challengeId, code }) => {
    const response = await apiClient.post("/api/auth/mfa/verify", {
      challengeId,
      code,
    });
    return response.data;
  },

  refresh: async () => {
    const response = await apiClient.post("/api/auth/refresh", {});
    return response.data;
  },

  logout: async () => {
    const response = await apiClient.post("/api/auth/logout", {});
    return response.data;
  },

  logoutAll: async () => {
    const response = await apiClient.post("/api/auth/logout-all", {});
    return response.data;
  },

  getSessions: async () => {
    const response = await apiClient.get("/api/auth/sessions");
    return response.data;
  },

  revokeSession: async (sessionId) => {
    const response = await apiClient.delete(`/api/auth/sessions/${sessionId}`);
    return response.data;
  },

  previewInvite: async (token) => {
    const response = await apiClient.get("/api/auth/invite/preview", {
      params: { token },
    });
    return response.data;
  },

  acceptInvite: async ({ token, username }) => {
    const response = await apiClient.post("/api/auth/invite/accept", {
      token,
      username,
    });
    return response.data;
  },

  getGoogleStartUrl: () => `${API_BASE_URL}/api/auth/google/start`,

  requestPrivateBeta: async ({ name, title, companyName, email }) => {
    const response = await apiClient.post("/api/auth/beta-signup", {
      name,
      title,
      companyName,
      email,
    });
    return response.data;
  },
};

// DeepSQL Agent conversations — per-user, identity-keyed index so a user can
// list + resume their agent chats from any device (server-side, cookie-auth).
export const agentConversationAPI = {
  list: async (connectionId) => {
    const response = await apiClient.get("/api/agent/conversations", {
      params: connectionId ? { connectionId } : {},
    });
    return response.data;
  },

  get: async (id) => {
    const response = await apiClient.get(`/api/agent/conversations/${id}`);
    return response.data;
  },

  create: async ({ connectionId, agentSessionId, title }) => {
    const response = await apiClient.post("/api/agent/conversations", {
      connectionId,
      agentSessionId,
      title,
    });
    return response.data;
  },

  update: async (id, { transcript, title, agentSessionId }) => {
    const response = await apiClient.put(`/api/agent/conversations/${id}`, {
      transcript,
      title,
      agentSessionId,
    });
    return response.data;
  },

  archive: async (id) => {
    const response = await apiClient.delete(`/api/agent/conversations/${id}`);
    return response.data;
  },
};

export const mcpTokensAPI = {
  listTokens: async () => {
    const response = await apiClient.get("/api/auth/mcp-tokens");
    return response.data;
  },

  createToken: async ({ name, expiresAt }) => {
    const response = await apiClient.post("/api/auth/mcp-tokens", {
      name,
      expiresAt: expiresAt || null,
    });
    return response.data;
  },

  revokeToken: async (tokenId) => {
    const response = await apiClient.delete(`/api/auth/mcp-tokens/${tokenId}`);
    return response.data;
  },
};

// Admin API (user management, roles)
export const adminAPI = {
  /**
   * List all users (ADMIN only)
   */
  listUsers: async () => {
    const response = await apiClient.get("/api/admin/users");
    return response.data;
  },

  createUser: async ({ username, email, role, password }) => {
    const response = await apiClient.post("/api/admin/users/invite", {
      username,
      email,
      role,
      password,
    });
    return response.data;
  },

  changeUserPassword: async (userId, password) => {
    const response = await apiClient.put(`/api/admin/users/${userId}/password`, {
      password,
    });
    return response.data;
  },

  /**
   * Get a specific user by ID (ADMIN only)
   */
  getUser: async (userId) => {
    const response = await apiClient.get(`/api/admin/users/${userId}`);
    return response.data;
  },

  /**
   * Update a user's role (ADMIN only)
   */
  updateUserRole: async (userId, role) => {
    const response = await apiClient.put(`/api/admin/users/${userId}/role`, {
      role,
    });
    return response.data;
  },

  /**
   * Delete a user (ADMIN only)
   */
  deleteUser: async (userId) => {
    const response = await apiClient.delete(`/api/admin/users/${userId}`);
    return response.data;
  },

  resendInvite: async (userId) => {
    const response = await apiClient.post(`/api/admin/users/${userId}/resend-invite`);
    return response.data;
  },

  lockUser: async (userId) => {
    const response = await apiClient.post(`/api/admin/users/${userId}/lock`);
    return response.data;
  },

  unlockUser: async (userId) => {
    const response = await apiClient.post(`/api/admin/users/${userId}/unlock`);
    return response.data;
  },

  disableUser: async (userId) => {
    const response = await apiClient.post(`/api/admin/users/${userId}/disable`);
    return response.data;
  },

  getUserConnectionAccess: async (userId) => {
    const response = await apiClient.get(`/api/admin/users/${userId}/connection-access`)
    return response.data
  },

  updateUserConnectionAccess: async (userId, connectionId, accessLevel) => {
    const response = await apiClient.put(`/api/admin/users/${userId}/connection-access/${connectionId}`, {
      accessLevel,
    })
    return response.data
  },

  revokeUserConnectionAccess: async (userId, connectionId) => {
    const response = await apiClient.delete(`/api/admin/users/${userId}/connection-access/${connectionId}`)
    return response.data
  },

  getUserConnectionChatPolicy: async (userId, connectionId) => {
    const response = await apiClient.get(`/api/admin/users/${userId}/connection-access/${connectionId}/chat-policy`)
    return response.data
  },

  updateUserConnectionChatPolicy: async (userId, connectionId, payload) => {
    const response = await apiClient.put(`/api/admin/users/${userId}/connection-access/${connectionId}/chat-policy`, payload)
    return response.data
  },

  previewConnectionChatPolicy: async (connectionId, plainEnglishPolicy) => {
    const response = await apiClient.post('/api/admin/connection-chat-policies/preview', {
      connectionId,
      plainEnglishPolicy,
    })
    return response.data
  },

  getConnectionAssignments: async (connectionId) => {
    const response = await apiClient.get(`/api/admin/connections/${connectionId}/connection-access`)
    return response.data
  },

  startImpersonation: async (userId) => {
    const response = await apiClient.post('/api/admin/impersonate', { userId })
    return response.data
  },

  stopImpersonation: async () => {
    const response = await apiClient.delete('/api/admin/impersonate')
    return response.data
  },

  getImpersonationStatus: async () => {
    const response = await apiClient.get('/api/admin/impersonate')
    return response.data
  },

  /**
   * Get all roles with their permissions (ADMIN only)
   */
  getRoles: async () => {
    const response = await apiClient.get("/api/admin/roles");
    return response.data;
  },

  /**
   * Get all available permissions (ADMIN only)
   */
  getPermissions: async () => {
    const response = await apiClient.get("/api/admin/permissions");
    return response.data;
  },

  listSecurityEvents: async (params = {}) => {
    const normalizedParams = { ...params };
    if (Array.isArray(normalizedParams.userIds)) {
      normalizedParams.userIds = normalizedParams.userIds.join(",");
    }
    const response = await apiClient.get("/api/admin/security/events", { params: normalizedParams });
    return response.data;
  },

  listSecuritySessions: async (userId) => {
    const response = await apiClient.get("/api/admin/security/sessions", {
      params: { userId },
    });
    return response.data;
  },

  getEmailSettings: async () => {
    const response = await apiClient.get("/api/admin/settings/email");
    return response.data;
  },

  updateEmailSettings: async (payload) => {
    const response = await apiClient.put("/api/admin/settings/email", payload);
    return response.data;
  },

  testEmailSettings: async (recipient) => {
    const response = await apiClient.post("/api/admin/settings/email/test", {
      recipient,
    });
    return response.data;
  },

  getSecuritySettings: async () => {
    const response = await apiClient.get("/api/admin/settings/security");
    return response.data;
  },

  updateSecuritySettings: async (payload) => {
    const response = await apiClient.put("/api/admin/settings/security", payload);
    return response.data;
  },

  getSlackSettings: async () => {
    const response = await apiClient.get("/api/admin/settings/slack");
    return response.data;
  },

  updateSlackSettings: async (payload) => {
    const response = await apiClient.put("/api/admin/settings/slack", payload);
    return response.data;
  },
};

export const slackLinkAPI = {
  getCurrentLinkCode: async () => {
    const response = await apiClient.get('/api/slack/link/code')
    return response.data
  },

  createLinkCode: async () => {
    const response = await apiClient.post('/api/slack/link/code')
    return response.data
  },

  listVisibleConnections: async () => {
    const response = await apiClient.get('/api/slack/link/connections')
    return response.data
  },
};

export const cliAuthAPI = {
  getPending: async (authorizationId) => {
    const response = await apiClient.get(`/api/auth/cli/pending/${encodeURIComponent(authorizationId)}`)
    return response.data
  },

  approve: async (authorizationId) => {
    const response = await apiClient.post('/api/auth/cli/approve', {
      authorizationId,
    })
    return response.data
  },

  deny: async (authorizationId) => {
    const response = await apiClient.post('/api/auth/cli/deny', {
      authorizationId,
    })
    return response.data
  },

  lookupDevice: async (userCode) => {
    const response = await apiClient.get('/api/auth/cli/device/pending', {
      params: { user_code: userCode },
    })
    return response.data
  },

  approveDevice: async (userCode) => {
    const response = await apiClient.post('/api/auth/cli/device/approve', {
      userCode,
    })
    return response.data
  },

  denyDevice: async (userCode) => {
    const response = await apiClient.post('/api/auth/cli/device/deny', {
      userCode,
    })
    return response.data
  },
};

// Permissions API (RBAC management)
export const permissionsAPI = {
  /**
   * Get current user's effective permissions.
   * Called after login to know what the user can do.
   */
  getMyPermissions: async () => {
    const response = await apiClient.get("/api/permissions/me");
    return response.data;
  },

  /**
   * Get full permission registry (ADMIN only).
   * Shows all permissions with their default roles and any overrides.
   */
  getRegistry: async () => {
    const response = await apiClient.get("/api/permissions/registry");
    return response.data;
  },

  /**
   * Get all roles with their effective permissions (ADMIN only).
   */
  getRoles: async () => {
    const response = await apiClient.get("/api/permissions/roles");
    return response.data;
  },

  /**
   * Get all permission overrides (ADMIN only).
   */
  getOverrides: async () => {
    const response = await apiClient.get("/api/permissions/overrides");
    return response.data;
  },

  /**
   * Create or update a permission override (ADMIN only).
   * @param {Object} override - { role, permission, granted, reason }
   */
  setOverride: async ({ role, permission, granted, reason }) => {
    const response = await apiClient.post("/api/permissions/overrides", {
      role,
      permission,
      granted,
      reason,
    });
    return response.data;
  },

  /**
   * Remove a permission override (ADMIN only).
   * Reverts to default behavior.
   * @param {string} role - Role name
   * @param {string} permission - Permission name
   */
  removeOverride: async (role, permission) => {
    const response = await apiClient.delete("/api/permissions/overrides", {
      data: { role, permission },
    });
    return response.data;
  },

  /**
   * Check if a role has a specific permission.
   */
  checkPermission: async (role, permission) => {
    const response = await apiClient.get("/api/permissions/check", {
      params: { role, permission },
    });
    return response.data;
  },
};

// Invite Code API (admin operations)
export const inviteCodeAPI = {
  generateCode: async (options = {}) => {
    const response = await apiClient.post("/api/invite-codes", options);
    return response.data;
  },

  listAllCodes: async () => {
    const response = await apiClient.get("/api/invite-codes");
    return response.data;
  },

  listValidCodes: async () => {
    const response = await apiClient.get("/api/invite-codes/valid");
    return response.data;
  },

  getCode: async (id) => {
    const response = await apiClient.get(`/api/invite-codes/${id}`);
    return response.data;
  },

  deactivateCode: async (id) => {
    const response = await apiClient.delete(`/api/invite-codes/${id}`);
    return response.data;
  },

  validateCode: async (code) => {
    const response = await apiClient.get(
      `/api/invite-codes/validate/${encodeURIComponent(code)}`,
    );
    return response.data;
  },
};

// Connection API
export const connectionAPI = {
  testConnection: async (connectionData) => {
    const response = await apiClient.post(
      "/api/connections/test",
      connectionData,
    );
    return response.data;
  },

  saveConnection: async (connectionData) => {
    const response = await apiClient.post("/api/connections", connectionData);
    return response.data;
  },

  getAllConnections: async () => {
    const response = await apiClient.get("/api/connections");
    return response.data;
  },

  deleteConnection: async (connectionId) => {
    const response = await apiClient.delete(`/api/connections/${connectionId}`);
    return response.data;
  },

  updateConnection: async (connectionId, connectionData) => {
    const response = await apiClient.put(
      `/api/connections/${connectionId}`,
      connectionData,
    );
    return response.data;
  },

  getInitStatus: async (connectionId) => {
    const response = await apiClient.get(
      `/api/connections/${connectionId}/init-status`,
    );
    return response.data;
  },

  getInitHistory: async (connectionId) => {
    const response = await apiClient.get(
      `/api/connections/${connectionId}/init-history`,
    );
    return response.data;
  },

  getInitSummary: async (connectionId) => {
    const response = await apiClient.get(
      `/api/connections/${connectionId}/init-summary`,
    );
    return response.data;
  },

  getBrainJobs: async (connectionId) => {
    const response = await apiClient.get(
      `/api/connections/${connectionId}/brain-jobs`,
    );
    return response.data;
  },

  runBrainJob: async (connectionId, jobKey) => {
    const response = await apiClient.post(
      `/api/connections/${connectionId}/brain-jobs/${jobKey}/run`,
    );
    return response.data;
  },

  getInitProgressStreamUrl: (connectionId) => {
    return `${API_BASE_URL}/api/connections/${connectionId}/init-progress`;
  },

  reinitialize: async (connectionId) => {
    const response = await apiClient.post(
      `/api/connections/${connectionId}/reinit`,
    );
    return response.data;
  },

  forceRebuild: async (connectionId) => {
    const response = await apiClient.post(
      `/api/connections/${connectionId}/reinit?force=true`,
    );
    return response.data;
  },

  cancelInit: async (connectionId) => {
    const response = await apiClient.post(
      `/api/connections/${connectionId}/cancel-init`,
    );
    return response.data;
  },

  warmupConnection: async (connectionId) => {
    try {
      const response = await apiClient.post(
        `/api/connections/${connectionId}/warmup`,
      );
      return response.data;
    } catch {
      // Warmup failure is not critical - don't throw
      console.warn("Schema warmup failed, first query may be slower");
      return { success: false };
    }
  },
};

// Schema API
export const schemaAPI = {
  scanSchema: async (connectionId) => {
    const response = await apiClient.post(
      `/api/connections/${connectionId}/scan`,
    );
    return response.data;
  },

  getSchema: async (connectionId) => {
    const response = await apiClient.get(
      `/api/connections/${connectionId}/schema`,
    );
    return response.data;
  },

  getVisualization: async (connectionId) => {
    const response = await apiClient.get(
      `/api/connections/${connectionId}/visualization`,
    );
    return response.data;
  },

  getSchemaChanges: async (connectionId) => {
    const response = await apiClient.get(
      `/api/schema-changes/${connectionId}/changes`,
    );
    return response.data;
  },
};

// Training API (RAG)
export const trainingAPI = {
  startSchemaTraining: async (connectionId) => {
    const response = await apiClient.post(
      `/api/training/schema/${connectionId}`,
    );
    return response.data;
  },

  getSchemaTrainingStatus: async (connectionId) => {
    const response = await apiClient.get(
      `/api/training/schema/status/${connectionId}`,
    );
    return response.data;
  },

  cancelSchemaTraining: async (connectionId) => {
    const response = await apiClient.post(
      `/api/training/schema/cancel/${connectionId}`,
    );
    return response.data;
  },

  getTrainingHistory: async (connectionId, limit = 5) => {
    const response = await apiClient.get(
      `/api/training/history/${connectionId}`,
      {
        params: { limit },
      },
    );
    return response.data;
  },

  getQueueMetrics: async () => {
    const response = await apiClient.get("/api/training/queue/metrics");
    return response.data;
  },

  addDocumentation: async (payload) => {
    const response = await apiClient.post(
      "/api/training/documentation",
      payload,
    );
    return response.data;
  },

  getSchemaTrainingStreamUrl: (connectionId) => {
    return `${API_BASE_URL}/api/training/schema/stream/${connectionId}`;
  },

  getTrainingStats: async (connectionId) => {
    const response = await apiClient.get(`/api/training/stats/${connectionId}`);
    return response.data;
  },
};

export const brainAPI = {
  getUnderstanding: async (connectionId) => {
    const response = await apiClient.get(
      `/api/brain/understanding/${connectionId}`,
    );
    return response.data;
  },
  profileConnection: async (connectionId) => {
    const response = await apiClient.post(`/api/brain/profile/${connectionId}`);
    return response.data;
  },
  resolveAmbiguity: async (payload) => {
    const response = await apiClient.post("/api/brain/ambiguity", payload);
    return response.data;
  },
  rescanSchema: async (connectionId) => {
    const response = await apiClient.post(
      `/api/brain/schema/rescan/${connectionId}`,
    );
    return response.data;
  },
  getTasks: async (connectionId, status = null) => {
    const response = await apiClient.get(`/api/brain/tasks/${connectionId}`, {
      params: status ? { status } : {},
    });
    return response.data;
  },
  createTask: async (payload) => {
    const response = await apiClient.post("/api/brain/tasks", payload);
    return response.data;
  },
  updateTaskStatus: async (taskId, status) => {
    const response = await apiClient.post(`/api/brain/tasks/${taskId}/status`, {
      status,
    });
    return response.data;
  },
  getNotes: async (connectionId, params = {}) => {
    const response = await apiClient.get(`/api/brain/notes/${connectionId}`, {
      params,
    });
    return response.data;
  },
  createNote: async (payload) => {
    const response = await apiClient.post("/api/brain/notes", payload);
    return response.data;
  },
  updateNote: async (noteId, payload) => {
    const response = await apiClient.post(
      `/api/brain/notes/${noteId}`,
      payload,
    );
    return response.data;
  },
  deleteNote: async (noteId) => {
    const response = await apiClient.post(`/api/brain/notes/${noteId}/delete`);
    return response.data;
  },
  getNoteSuggestions: async (connectionId, limit = 10) => {
    const response = await apiClient.get(
      `/api/brain/notes/suggestions/${connectionId}`,
      {
        params: { limit },
      },
    );
    return response.data;
  },
  proposeNoteFromTurn: async (payload) => {
    const response = await apiClient.post("/api/brain/notes/propose", payload);
    return response.status === 204 ? null : response.data;
  },
  acceptNote: async (payload) => {
    const response = await apiClient.post("/api/brain/notes/accept", payload);
    return response.data;
  },
  getKeyColumns: async (connectionId, params = {}) => {
    const response = await apiClient.get(
      `/api/brain/key-columns/${connectionId}`,
      { params },
    );
    return response.data;
  },
  analyzeKeyColumns: async (connectionId) => {
    const response = await apiClient.post(
      `/api/brain/key-columns/analyze/${connectionId}`,
    );
    return response.data;
  },
  acknowledgeAntiPattern: async (patternId) => {
    const response = await apiClient.post(
      `/api/brain/key-columns/anti-pattern/${patternId}/acknowledge`,
    );
    return response.data;
  },
  // Inferred Relationships
  getInferredRelationships: async (connectionId) => {
    const response = await apiClient.get(
      `/api/brain/inferred-relationships/${connectionId}`,
    );
    return response.data;
  },
  analyzeInferredRelationships: async (connectionId) => {
    const response = await apiClient.post(
      `/api/brain/inferred-relationships/analyze/${connectionId}`,
    );
    return response.data;
  },
  validateInferredRelationship: async (relationshipId, status) => {
    const response = await apiClient.post(
      `/api/brain/inferred-relationships/${relationshipId}/validate`,
      { status },
    );
    return response.data;
  },

  // Column Values (for chat context and filtering)
  getColumnValues: async (connectionId) => {
    const response = await apiClient.get(
      `/api/brain/column-values/${connectionId}`,
    );
    return response.data;
  },
  getColumnValuesStats: async (connectionId) => {
    const response = await apiClient.get(
      `/api/brain/column-values/${connectionId}/stats`,
    );
    return response.data;
  },
  refreshColumnValues: async (connectionId) => {
    const response = await apiClient.post(
      `/api/brain/column-values/${connectionId}/refresh`,
    );
    return response.data;
  },
  embedAllColumnValues: async () => {
    const response = await apiClient.post(`/api/brain/column-values/embed-all`);
    return response.data;
  },

  // Schema Classification
  getSchemaClassification: async (connectionId) => {
    const response = await apiClient.get(
      `/api/brain/schema-classification/${connectionId}`,
    );
    return response.data;
  },
  analyzeSchemaClassification: async (connectionId) => {
    const response = await apiClient.post(
      `/api/brain/schema-classification/analyze/${connectionId}`,
    );
    return response.data;
  },
  getTableClassifications: async (connectionId, role = null) => {
    const params = role ? { role } : {};
    const response = await apiClient.get(
      `/api/brain/schema-classification/tables/${connectionId}`,
      { params },
    );
    return response.data;
  },
  getFactTables: async (connectionId) => {
    const response = await apiClient.get(
      `/api/brain/schema-classification/fact-tables/${connectionId}`,
    );
    return response.data;
  },
  getDimensionTables: async (connectionId) => {
    const response = await apiClient.get(
      `/api/brain/schema-classification/dimensions/${connectionId}`,
    );
    return response.data;
  },

  // Query Anti-Patterns
  getQueryAntiPatterns: async (connectionId, limit = null) => {
    const params = limit ? { limit } : {};
    const response = await apiClient.get(
      `/api/brain/query-anti-patterns/${connectionId}`,
      { params },
    );
    return response.data;
  },
  analyzeQueryAntiPatterns: async (connectionId) => {
    const response = await apiClient.post(
      `/api/brain/query-anti-patterns/analyze/${connectionId}`,
    );
    return response.data;
  },

  // Scalability Simulation
  getScalabilitySimulations: async (connectionId) => {
    const response = await apiClient.get(
      `/api/brain/scalability/${connectionId}`,
    );
    return response.data;
  },
  getLatestSimulation: async (connectionId) => {
    const response = await apiClient.get(
      `/api/brain/scalability/latest/${connectionId}`,
    );
    return response.data;
  },
  runScalabilitySimulation: async (connectionId, scenario = null) => {
    const params = scenario ? { scenario } : {};
    const response = await apiClient.post(
      `/api/brain/scalability/simulate/${connectionId}`,
      null,
      { params },
    );
    return response.data;
  },
  getTablePredictions: async (simulationId) => {
    const response = await apiClient.get(
      `/api/brain/scalability/predictions/${simulationId}`,
    );
    return response.data;
  },
  getHighRiskTables: async (connectionId) => {
    const response = await apiClient.get(
      `/api/brain/scalability/high-risk/${connectionId}`,
    );
    return response.data;
  },

  // Brain Score
  getLatestScore: async (connectionId) => {
    try {
      const response = await apiClient.get(
        `/api/brain/score/latest/${connectionId}`,
      );
      return response.data;
    } catch (err) {
      // Return null if score doesn't exist (404)
      // Note: err.status (not err.response.status) — the response interceptor
      // creates an enhanced Error with status as a direct property.
      if (err?.status === 404) {
        return null;
      }
      throw err;
    }
  },
  getScoreHistory: async (connectionId, limit = 10) => {
    const response = await apiClient.get(
      `/api/brain/score/history/${connectionId}`,
      {
        params: { limit },
      },
    );
    return response.data;
  },
  calculateScore: async (connectionId) => {
    const response = await apiClient.post(
      `/api/brain/score/calculate/${connectionId}`,
    );
    return response.data;
  },

  // ========== Brain 2.0 ML Features ==========

  // Workload Analysis
  collectWorkloadMetrics: async (connectionId) => {
    const response = await apiClient.post(
      `/api/brain/workload/collect/${connectionId}`,
    );
    return response.data;
  },
  characterizeWorkload: async (connectionId) => {
    const response = await apiClient.post(
      `/api/brain/workload/characterize/${connectionId}`,
    );
    return response.data;
  },
  getWorkloadProfile: async (connectionId) => {
    const response = await apiClient.get(
      `/api/brain/workload/profile/${connectionId}`,
    );
    return response.data;
  },
  getWorkloadStatus: async (connectionId) => {
    const response = await apiClient.get(
      `/api/brain/workload/status/${connectionId}`,
    );
    return response.data;
  },
  getSimilarWorkloads: async (connectionId, limit = 5) => {
    const response = await apiClient.get(
      `/api/brain/workload/similar/${connectionId}`,
      {
        params: { limit },
      },
    );
    return response.data;
  },

  // Config Tuning (Knob Identification)
  identifyKnobs: async (connectionId) => {
    const response = await apiClient.post(
      `/api/brain/config/knobs/${connectionId}`,
    );
    return response.data;
  },
  getTopKnobs: async (connectionId, limit = 10) => {
    const response = await apiClient.get(
      `/api/brain/config/top-knobs/${connectionId}`,
      {
        params: { limit },
      },
    );
    return response.data;
  },
  getKnobRankings: async (connectionId) => {
    const response = await apiClient.get(
      `/api/brain/config/rankings/${connectionId}`,
    );
    return response.data;
  },
  getConfigRecommendations: async (connectionId, targetMetric = null) => {
    const params = targetMetric ? { targetMetric } : {};
    const response = await apiClient.post(
      `/api/brain/config/recommendations/${connectionId}`,
      null,
      { params },
    );
    return response.data;
  },
  /**
   * Create a tuning experiment.
   * @param {string} connectionId - Connection ID
   * @param {Object} knobChanges - Map of knob name to settings map, e.g., { "innodb_buffer_pool_size": { "current": "128M", "recommended": "256M" } }
   * @param {string|null} recommendationId - Optional recommendation ID this experiment is based on
   */
  createTuningExperiment: async (
    connectionId,
    knobChanges,
    recommendationId = null,
  ) => {
    const response = await apiClient.post(
      `/api/brain/config/experiments/${connectionId}`,
      {
        knobChanges,
        recommendationId,
      },
    );
    return response.data;
  },
  getTuningExperiments: async (connectionId) => {
    const response = await apiClient.get(
      `/api/brain/config/experiments/${connectionId}`,
    );
    return response.data;
  },
  /**
   * Complete a tuning experiment - collects final metrics and calculates improvement.
   * @param {string} experimentId - The experiment ID to complete
   * @param {string} connectionId - The connection ID for the experiment
   */
  completeExperiment: async (experimentId, connectionId) => {
    const response = await apiClient.post(
      `/api/brain/config/experiments/${experimentId}/complete`,
      null,
      {
        params: { connectionId },
      },
    );
    return response.data;
  },
  /**
   * Cancel a running experiment.
   * @param {string} experimentId - The experiment ID to cancel
   * @param {string} connectionId - The connection ID for the experiment
   */
  cancelExperiment: async (experimentId, connectionId) => {
    const response = await apiClient.delete(
      `/api/brain/config/experiments/${experimentId}`,
      {
        params: { connectionId },
      },
    );
    return response.data;
  },

  // Column Statistics (Cardinality Estimation)
  collectColumnStatistics: async (connectionId, tableName) => {
    const response = await apiClient.post(
      `/api/brain/statistics/${connectionId}/tables/${tableName}`,
    );
    return response.data;
  },
  getColumnStatistics: async (connectionId) => {
    const response = await apiClient.get(
      `/api/brain/statistics/${connectionId}`,
    );
    return response.data;
  },
  /**
   * Get high cardinality columns (columns with many distinct values).
   * @param {string} connectionId - Connection ID
   * @param {number} minDistinct - Minimum distinct value count to consider "high cardinality" (default: 1000)
   */
  getHighCardinalityColumns: async (connectionId, minDistinct = 1000) => {
    const response = await apiClient.get(
      `/api/brain/statistics/${connectionId}/high-cardinality`,
      {
        params: { minDistinct },
      },
    );
    return response.data;
  },
  estimateCardinality: async (connectionId, request) => {
    const response = await apiClient.post(
      `/api/brain/statistics/${connectionId}/estimate`,
      request,
    );
    return response.data;
  },
  getCardinalityAccuracy: async (connectionId) => {
    const response = await apiClient.get(
      `/api/brain/statistics/${connectionId}/accuracy`,
    );
    return response.data;
  },

  // Query Plan Scoring & Patterns
  recordExecution: async (connectionId, request) => {
    const response = await apiClient.post(
      `/api/brain/executions/${connectionId}`,
      request,
    );
    return response.data;
  },
  getExecutionHistory: async (connectionId, limit = 100) => {
    const response = await apiClient.get(
      `/api/brain/executions/${connectionId}`,
      {
        params: { limit },
      },
    );
    return response.data;
  },
  getCalibrationStats: async (connectionId) => {
    const response = await apiClient.get(
      `/api/brain/calibration/${connectionId}`,
    );
    return response.data;
  },
  /**
   * Get optimization suggestions for a query based on learned plan patterns.
   * @param {string} connectionId - Connection ID
   * @param {string} query - The SQL query to get suggestions for
   */
  getPlanSuggestions: async (connectionId, query) => {
    const response = await apiClient.post(
      `/api/brain/patterns/${connectionId}/suggestions`,
      { query },
    );
    return response.data;
  },
  getReliablePatterns: async (connectionId) => {
    const response = await apiClient.get(
      `/api/brain/patterns/${connectionId}/reliable`,
    );
    return response.data;
  },
  getAllPatterns: async (connectionId, limit = 20) => {
    const response = await apiClient.get(
      `/api/brain/patterns/${connectionId}/most-used?limit=${limit}`,
    );
    return response.data;
  },
  getPatternStats: async (connectionId) => {
    const response = await apiClient.get(
      `/api/brain/patterns/${connectionId}/stats`,
    );
    return response.data;
  },
  /**
   * Record feedback on whether a pattern optimization was helpful.
   * Backend uses @RequestParam, so pass wasSuccessful as query param.
   * @param {string} patternId - The pattern ID
   * @param {boolean} wasSuccessful - Whether the optimization was helpful
   */
  recordPatternFeedback: async (patternId, wasSuccessful) => {
    const response = await apiClient.post(
      `/api/brain/patterns/${patternId}/feedback`,
      null,
      {
        params: { wasSuccessful },
      },
    );
    return response.data;
  },

  // ML Overview
  getMLOverview: async (connectionId) => {
    const response = await apiClient.get(
      `/api/brain/ml-overview/${connectionId}`,
    );
    return response.data;
  },

  // Query Intelligence backfill from slow query history
  backfillQueryIntelligence: async (connectionId) => {
    const response = await apiClient.post(
      `/api/brain/query-intelligence/${connectionId}/backfill`,
    );
    return response.data;
  },

  // EXPLAIN backfill - calculate estimated rows for existing query executions
  startExplainBackfill: async (connectionId, maxQueries = 500) => {
    const response = await apiClient.post(
      `/api/brain/query-intelligence/${connectionId}/explain-backfill`,
      null,
      {
        params: { maxQueries },
      },
    );
    return response.data;
  },

  getExplainProgress: async (connectionId) => {
    const response = await apiClient.get(
      `/api/brain/query-intelligence/${connectionId}/explain-progress`,
    );
    return response.data;
  },

  cancelExplainBackfill: async (connectionId) => {
    const response = await apiClient.post(
      `/api/brain/query-intelligence/${connectionId}/explain-cancel`,
    );
    return response.data;
  },
};

// Stats API
export const statsAPI = {
  getStats: async (connectionId) => {
    const response = await apiClient.get(
      `/api/connections/${connectionId}/stats`,
    );
    return response.data;
  },
};

// Query API
export const queryAPI = {
  getDatabaseObjects: async (connectionId) => {
    const response = await apiClient.get(
      `/api/connections/${connectionId}/objects`,
    );
    return response.data;
  },

  executeQuery: async (
    connectionId,
    query,
    limit = null,
    timeoutSeconds = null,
    signal = null,
    requestOptions = {},
  ) => {
    const payload = { query, limit };
    if (timeoutSeconds != null) {
      payload.timeoutSeconds = timeoutSeconds;
    }
    if (requestOptions.executionOrigin) {
      payload.executionOrigin = requestOptions.executionOrigin;
    }
    if (requestOptions.mutationConfirmed != null) {
      payload.mutationConfirmed = requestOptions.mutationConfirmed;
    }
    // Lets the caller cancel this run server-side; aborting the request alone
    // leaves the query running on the database.
    if (requestOptions.executionId) {
      payload.executionId = requestOptions.executionId;
    }
    // Use a longer axios timeout for queries with extended timeouts
    const axiosTimeout =
      timeoutSeconds != null
        ? (timeoutSeconds + 30) * 1000 // query timeout + 30s buffer for overhead
        : undefined; // use default
    const config = {};
    if (axiosTimeout) config.timeout = axiosTimeout;
    if (signal) config.signal = signal;
    const response = await apiClient.post(
      `/api/connections/${connectionId}/query`,
      payload,
      Object.keys(config).length ? config : undefined,
    );
    return response.data;
  },

  cancelQuery: async (connectionId, executionId) => {
    const response = await apiClient.post(
      `/api/connections/${connectionId}/query/${encodeURIComponent(executionId)}/cancel`,
    );
    return response.data;
  },

  getTableIndexes: async (connectionId, tableName) => {
    // Encode so schema-qualified ids (`crm.orders`) survive the path segment.
    const tableId = encodeURIComponent(String(tableName || ""));
    const response = await apiClient.get(
      `/api/connections/${connectionId}/tables/${tableId}/indexes`,
    );
    return response.data;
  },

  getTableStats: async (connectionId, tableName) => {
    const tableId = encodeURIComponent(String(tableName || ""));
    const response = await apiClient.get(
      `/api/connections/${connectionId}/tables/${tableId}/stats`,
    );
    return response.data;
  },
};

export const activeQueryAPI = {
  capture: async (connectionId) => {
    const response = await apiClient.post(
      `/api/active-queries/capture/${connectionId}`,
    );
    return response.data;
  },
  kill: async (connectionId, pid) => {
    const response = await apiClient.post(
      `/api/active-queries/kill/${connectionId}/${pid}`,
    );
    return response.data;
  },
};

// Chat API
export const chatAPI = {
  sendMessage: async (
    connectionId,
    message,
    threadId = null,
    chatId = null,
    userId = null,
  ) => {
    const payload = {
      connectionId,
      message,
      threadId,
      chatId,
      userId,
    };

    // Chat requests can involve 2-3 sequential LLM calls (initial response + SQL execution +
    // summary + optional SQL repair), each taking 15-60s. The default 120s axios timeout is
    // insufficient. Use 300s (5 min) to accommodate the full multi-pass pipeline.
    // Retry up to 2 times with exponential backoff for network failures and gateway errors
    // (502/503 only — NOT 504 since the backend may have already started processing).
    const maxRetries = 2;
    const chatTimeout = 300000; // 5 minutes

    for (let attempt = 0; attempt <= maxRetries; attempt++) {
      try {
        const response = await apiClient.post("/api/chat", payload, {
          timeout: chatTimeout,
        });
        return response.data;
      } catch (error) {
        const isLastAttempt = attempt === maxRetries;
        // Only retry when the backend was provably unreachable (non-idempotent POST).
        // error.status is set by the response interceptor; absence means network failure.
        const isRetryable =
          !error.status || // true network failure (no response received)
          error.status === 502 || // bad gateway (backend down)
          error.status === 503; // service unavailable (backend down)

        if (isRetryable && !isLastAttempt) {
          const delay = 1000 * Math.pow(2, attempt);
          console.warn(
            `Chat request failed (attempt ${attempt + 1}/${maxRetries + 1}), retrying in ${delay}ms...`,
            error.message,
          );
          await new Promise((resolve) => setTimeout(resolve, delay));
          continue;
        }

        throw error;
      }
    }
  },

  /**
   * Stream a chat response via Server-Sent Events (POST-based SSE).
   * Uses native fetch + ReadableStream since EventSource only supports GET.
   *
   * @param {string} connectionId - Database connection ID
   * @param {string} message - User message
   * @param {Object} callbacks - { onToken, onDone, onError, onProgress, onMetadata, onResult }
   * @param {Object} [options] - { chatId, signal (AbortSignal) }
   * @returns {Promise<void>}
   */
  streamMessage: async (
    connectionId,
    message,
    { onToken, onDone, onError, onProgress, onMetadata, onResult } = {},
    { chatId = null, signal } = {},
  ) => {
    const baseUrl = API_BASE_URL || "";

    try {
      const makeStreamRequest = () =>
        fetch(`${baseUrl}/api/chat/stream`, {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
          },
          credentials: "include",
          body: JSON.stringify({ connectionId, message, chatId }),
          signal,
        });

      let response = await makeStreamRequest();

      if (response.status === 401) {
        await requestSessionRefresh();
        response = await makeStreamRequest();
      }

      if (!response.ok) {
        const errorText = await response.text();
        throw new Error(errorText || `HTTP ${response.status}`);
      }

      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = "";
      let currentEvent = null;
      const IDLE_TIMEOUT_MS = 180_000; // 3 minutes

      while (true) {
        const timeoutId = setTimeout(() => reader.cancel(), IDLE_TIMEOUT_MS);
        let result;
        try {
          result = await reader.read();
        } finally {
          clearTimeout(timeoutId);
        }
        const { done, value } = result;
        if (done) break;

        buffer += decoder.decode(value, { stream: true });

        // Process complete SSE blocks (separated by \n\n)
        const parts = buffer.split("\n\n");
        buffer = parts.pop(); // last element is incomplete

        for (const part of parts) {
          const lines = part.split("\n");
          for (const line of lines) {
            if (line.startsWith("event:")) {
              currentEvent = line.slice(6).trim();
            } else if (line.startsWith("data:")) {
              // SSE spec: optional single space after "data:", preserve all other whitespace
              const raw = line.slice(5);
              const payload = raw.startsWith(" ") ? raw.slice(1) : raw;

              if (currentEvent === "metadata") {
                try {
                  onMetadata?.(JSON.parse(payload));
                } catch {
                  // ignore malformed metadata
                }
              } else if (currentEvent === "result" || currentEvent === "final") {
                try {
                  onResult?.(JSON.parse(payload));
                } catch {
                  // ignore malformed result payload
                }
              } else if (currentEvent === "progress" || currentEvent === "status") {
                try {
                  onProgress?.(JSON.parse(payload));
                } catch {
                  onProgress?.({ message: payload });
                }
              } else if (currentEvent === "trace") {
                try {
                  onMetadata?.(JSON.parse(payload));
                } catch {
                  // ignore malformed trace payload
                }
              } else if (payload === "[DONE]") {
                onDone?.();
              } else if (payload.startsWith("Error:")) {
                onError?.(new Error(payload));
              } else {
                onToken?.(payload.replace(/\\n/g, "\n"));
              }
            }
          }
          currentEvent = null;
        }
      }

      // Flush remaining buffer
      if (buffer.trim()) {
        const lines = buffer.split("\n");
        for (const line of lines) {
          if (line.startsWith("data:")) {
            const flushRaw = line.slice(5);
            const payload = flushRaw.startsWith(" ")
              ? flushRaw.slice(1)
              : flushRaw;
            if (payload === "[DONE]") {
              onDone?.();
            } else if (!payload.startsWith("Error:")) {
              onToken?.(payload.replace(/\\n/g, "\n"));
            }
          }
        }
      }
    } catch (error) {
      if (error.name === "AbortError") return;
      onError?.(error);
    }
  },
};

// Chat History API
export const chatHistoryAPI = {
  createChat: async (connectionId, projectId, title) => {
    const response = await apiClient.post("/api/chats", {
      connectionId,
      projectId,
      title,
    });
    return response.data;
  },

  getAllChats: async (projectId = null, connectionId = null) => {
    const params = {};
    if (projectId) params.projectId = projectId;
    if (connectionId) params.connectionId = connectionId;
    const response = await apiClient.get("/api/chats", { params });
    return response.data;
  },

  getChatWithHistory: async (chatId) => {
    const response = await apiClient.get(`/api/chats/${chatId}`);
    return response.data;
  },

  updateChatTitle: async (chatId, title) => {
    const response = await apiClient.put(`/api/chats/${chatId}`, { title });
    return response.data;
  },

  deleteChat: async (chatId) => {
    const response = await apiClient.delete(`/api/chats/${chatId}`);
    return response.data;
  },

  getActiveChat: async (connectionId) => {
    const response = await apiClient.get("/api/chats/active", {
      params: { connectionId },
    });
    return response.data;
  },

  clearActiveChat: async (connectionId) => {
    const response = await apiClient.post("/api/chats/active/clear", null, {
      params: { connectionId },
    });
    return response.data;
  },
};

export const companyKnowledgeAPI = {
  list: async (connectionId) => {
    const response = await apiClient.get(`/api/company-knowledge/${connectionId}`);
    return response.data;
  },

  create: async (payload) => {
    const response = await apiClient.post("/api/company-knowledge", payload);
    return response.data;
  },

  update: async (entryId, payload) => {
    const response = await apiClient.put(`/api/company-knowledge/${entryId}`, payload);
    return response.data;
  },

  delete: async (entryId, connectionId) => {
    const response = await apiClient.delete(`/api/company-knowledge/${entryId}`, {
      params: { connectionId },
    });
    return response.data;
  },
};

// Code Scan API — feeds Company Knowledge from application source code.
export const codeScanAPI = {
  listSources: async (connectionId) => {
    const response = await apiClient.get("/api/code-scan/sources", {
      params: { connectionId },
    });
    return response.data;
  },

  createSource: async ({ connectionId, file, name, focus }) => {
    const formData = new FormData();
    formData.append("connectionId", connectionId);
    if (name) formData.append("name", name);
    if (focus) formData.append("focus", focus);
    formData.append("file", file);
    const response = await apiClient.post("/api/code-scan/sources", formData, {
      headers: { "Content-Type": "multipart/form-data" },
    });
    return response.data;
  },

  updateFocus: async ({ sourceId, connectionId, focus }) => {
    const response = await apiClient.put(
      `/api/code-scan/sources/${sourceId}/focus`,
      { focus: focus || "" },
      { params: { connectionId } },
    );
    return response.data;
  },

  deleteSource: async ({ sourceId, connectionId }) => {
    const response = await apiClient.delete(`/api/code-scan/sources/${sourceId}`, {
      params: { connectionId },
    });
    return response.data;
  },

  startScan: async ({ sourceId, connectionId, file, focus }) => {
    const formData = new FormData();
    formData.append("connectionId", connectionId);
    if (focus) formData.append("focus", focus);
    formData.append("file", file);
    const response = await apiClient.post(
      `/api/code-scan/sources/${sourceId}/scan`,
      formData,
      { headers: { "Content-Type": "multipart/form-data" } },
    );
    return response.data;
  },

  getJob: async ({ jobId, connectionId }) => {
    const response = await apiClient.get(`/api/code-scan/jobs/${jobId}`, {
      params: { connectionId },
    });
    return response.data;
  },

  listJobs: async ({ sourceId, connectionId }) => {
    const response = await apiClient.get(`/api/code-scan/sources/${sourceId}/jobs`, {
      params: { connectionId },
    });
    return response.data;
  },

  // Returns an EventSource — caller is responsible for closing it.
  streamJob: ({ jobId, connectionId }) => {
    const url = `${API_BASE_URL}/api/code-scan/jobs/${jobId}/stream?connectionId=${encodeURIComponent(connectionId)}`;
    return new EventSource(url, { withCredentials: true });
  },

  listSuggestions: async ({ connectionId, status = "PENDING", page = 0, size = 50 }) => {
    const response = await apiClient.get("/api/code-scan/suggestions", {
      params: { connectionId, status, page, size },
    });
    return response.data;
  },

  decide: async ({ suggestionId, connectionId, decision, note }) => {
    const response = await apiClient.post(
      `/api/code-scan/suggestions/${suggestionId}/decide`,
      { decision, note },
      { params: { connectionId } },
    );
    return response.data;
  },

  bulkDecide: async ({ connectionId, ids, decision, note }) => {
    // Each approve runs the full Apply pipeline (SchemaDoc/CompanyKnowledgeEntry
    // create + Azure embedding upsert), ~1-2s per item. Override the default
    // 120s axios timeout so a 100-item bulk doesn't get cut off client-side.
    const response = await apiClient.post(
      `/api/code-scan/suggestions/bulk-decide`,
      { ids, decision, note },
      {
        params: { connectionId },
        timeout: Math.max(120000, ids.length * 3000),
      },
    );
    return response.data;
  },
};

// Schema Context API — what's ambiguous in the schema (drives the Unresolved panel + scan focus).
export const schemaContextAPI = {
  ambiguity: async (connectionId) => {
    const response = await apiClient.get(`/api/schema-context/ambiguity/${connectionId}`);
    return response.data;
  },
};

// Chat Memory API (JDBC Spring AI memory)
export const chatMemoryAPI = {
  getStatus: async () => {
    const response = await apiClient.get("/api/memory/status");
    return response.data;
  },

  clearConversation: async (connectionId, chatId) => {
    const response = await apiClient.delete(
      `/api/memory/connection/${connectionId}/chat/${chatId}`,
    );
    return response.data;
  },

  getMessageCount: async (connectionId, chatId) => {
    const response = await apiClient.get(
      `/api/memory/connection/${connectionId}/chat/${chatId}/count`,
    );
    return response.data;
  },
};

export const agentRunAPI = {
  getRunTrace: async (runId) => {
    const response = await apiClient.get(`/api/agent-runs/${runId}`);
    return response.data;
  },
};

// Project API
export const projectAPI = {
  createProject: async (name, description, connectionId) => {
    const response = await apiClient.post("/api/projects", {
      name,
      description,
      connectionId,
    });
    return response.data;
  },

  getAllProjects: async (connectionId = null) => {
    const params = connectionId ? { connectionId } : {};
    const response = await apiClient.get("/api/projects", { params });
    return response.data;
  },

  getProject: async (projectId) => {
    const response = await apiClient.get(`/api/projects/${projectId}`);
    return response.data;
  },

  updateProject: async (projectId, name, description) => {
    const response = await apiClient.put(`/api/projects/${projectId}`, {
      name,
      description,
    });
    return response.data;
  },

  deleteProject: async (projectId) => {
    const response = await apiClient.delete(`/api/projects/${projectId}`);
    return response.data;
  },
};

// Advisor API
export const advisorAPI = {
  analyzeDatabase: async (connectionId) => {
    const response = await apiClient.get(
      `/api/advisor/analyze/${connectionId}`,
    );
    return response.data;
  },
};

// Slow Queries API
export const slowQueriesAPI = {
  getHistory: async (connectionId) => {
    const response = await apiClient.get(
      `/api/slow-queries/history/${connectionId}`,
    );
    return response.data;
  },

  getLatestAnalysis: async (connectionId) => {
    const response = await apiClient.get(
      `/api/slow-queries/latest/${connectionId}`,
    );
    return response.data;
  },

  getHistoryById: async (id) => {
    const response = await apiClient.get(
      `/api/slow-queries/history/item/${id}`,
    );
    return response.data;
  },

  analyzeSlowQueries: async (connectionId, threshold = 100, limit = 10) => {
    const response = await apiClient.get(
      `/api/slow-queries/analyze/${connectionId}`,
      {
        params: { threshold, limit },
      },
    );
    return response.data;
  },

  saveExplainPlan: async (data) => {
    const response = await apiClient.post("/api/slow-queries/history", data);
    return response.data;
  },

  deleteHistory: async (id) => {
    const response = await apiClient.delete(`/api/slow-queries/history/${id}`);
    return response.data;
  },

  analyzeLogFile: async (connectionId, file, databaseType = "mysql") => {
    const formData = new FormData();
    formData.append("file", file);
    formData.append("connectionId", connectionId);
    formData.append("databaseType", databaseType);

    const response = await apiClient.post(
      "/api/slow-queries/analyze-file",
      formData,
      {
        headers: {
          "Content-Type": "multipart/form-data",
        },
      },
    );
    return response.data;
  },

  analyzeLogFileFromS3: async ({
    connectionId,
    s3Url,
    region,
    databaseType = "mysql",
    userId,
  }) => {
    const response = await apiClient.post("/api/slow-queries/analyze-file-s3", {
      connectionId,
      s3Url,
      region,
      databaseType,
      userId,
    });
    return response.data;
  },

  // Feature 1: AI-Powered Query Optimization
  optimizeQuery: async (request) => {
    const response = await apiClient.post(
      "/api/slow-queries/optimize",
      request,
    );
    return response.data;
  },

  /**
   * Build URL for the streaming SSE optimize endpoint.
   * Consumers should create a native EventSource from this URL.
   */
  buildOptimizeStreamUrl: (request) => {
    const base = `${API_BASE_URL}/api/slow-queries/optimize/stream`;
    const params = new URLSearchParams();
    params.set("connectionId", request.connectionId);
    params.set("queryText", request.queryText);
    if (request.sampleQuery) params.set("sampleQuery", request.sampleQuery);
    if (request.avgExecutionTimeMs != null)
      params.set("avgExecutionTimeMs", request.avgExecutionTimeMs);
    if (request.queryId) params.set("queryId", request.queryId);
    if (request.forceRefresh) params.set("forceRefresh", "true");
    return `${base}?${params.toString()}`;
  },

  batchOptimize: async (connectionId, limit = 5) => {
    const response = await apiClient.post(
      `/api/slow-queries/optimize/batch/${connectionId}?limit=${limit}`,
    );
    return response.data;
  },

  // Optimization Caching
  getCachedOptimization: async (connectionId, queryFingerprint) => {
    const response = await apiClient.get(
      `/api/slow-queries/optimize/cached/${connectionId}/${encodeURIComponent(queryFingerprint)}`,
    );
    return response.data;
  },

  getCachedOptimizations: async (connectionId, fingerprints) => {
    const response = await apiClient.post(
      `/api/slow-queries/optimize/cached/${connectionId}`,
      fingerprints,
    );
    return response.data;
  },

  getOptimizationCacheStats: async (connectionId) => {
    const response = await apiClient.get(
      `/api/slow-queries/optimize/cache-stats/${connectionId}`,
    );
    return response.data;
  },

  clearOptimizationCache: async (connectionId) => {
    const response = await apiClient.delete(
      `/api/slow-queries/optimize/cache/${connectionId}`,
    );
    return response.data;
  },

  getOptimizationCandidates: async (connectionId, queryFingerprint) => {
    const response = await apiClient.get(
      `/api/slow-queries/optimize/candidates/${connectionId}/${encodeURIComponent(queryFingerprint)}`,
    );
    return response.data;
  },

  benchmarkOptimizationCandidates: async (
    connectionId,
    queryFingerprint,
    payload = {},
  ) => {
    // Benchmarking can run 3 rounds of EXPLAIN ANALYZE per candidate with tiered
    // timeouts up to 10 minutes per execution. The default 120s axios timeout is
    // far too short — use 15 minutes to cover worst-case backend duration.
    const response = await apiClient.post(
      `/api/slow-queries/optimize/benchmark/${connectionId}/${encodeURIComponent(queryFingerprint)}`,
      payload,
      { timeout: 900000 },
    );
    return response.data;
  },

  // Feature 2: Slow Query Alerts
  getAlertSummary: async (connectionId) => {
    const response = await apiClient.get(
      `/api/slow-queries/alerts/${connectionId}`,
    );
    return response.data;
  },

  acknowledgeAlert: async (alertId, userId) => {
    const response = await apiClient.post(
      `/api/slow-queries/alerts/${alertId}/acknowledge?userId=${userId}`,
    );
    return response.data;
  },

  acknowledgeAllAlerts: async (connectionId, userId) => {
    const response = await apiClient.post(
      `/api/slow-queries/alerts/${connectionId}/acknowledge-all?userId=${userId}`,
    );
    return response.data;
  },

  processAlerts: async (connectionId, config = null) => {
    const response = await apiClient.post(
      `/api/slow-queries/alerts/process/${connectionId}`,
      config,
    );
    return response.data;
  },

  // Feature 3: Query Comparison
  compareAnalyses: async (historyId1, historyId2) => {
    const response = await apiClient.get(
      `/api/slow-queries/compare?historyId1=${historyId1}&historyId2=${historyId2}`,
    );
    return response.data;
  },

  // Feature 4: Dashboard Widgets
  getDashboardWidgets: async (connectionId) => {
    const response = await apiClient.get(
      `/api/slow-queries/dashboard/${connectionId}`,
    );
    return response.data;
  },

  getOverviewWidget: async (connectionId) => {
    const response = await apiClient.get(
      `/api/slow-queries/dashboard/${connectionId}/overview`,
    );
    return response.data;
  },

  getTrendWidget: async (connectionId) => {
    const response = await apiClient.get(
      `/api/slow-queries/dashboard/${connectionId}/trend`,
    );
    return response.data;
  },

  // Feature 5: Query Fingerprint Tracking
  getFingerprintSummary: async (connectionId) => {
    const response = await apiClient.get(
      `/api/slow-queries/fingerprints/${connectionId}`,
    );
    return response.data;
  },

  getFingerprints: async (connectionId, options = {}) => {
    const params = new URLSearchParams();
    if (options.queryType) params.append("queryType", options.queryType);
    if (options.trendDirection)
      params.append("trendDirection", options.trendDirection);
    if (options.regressingOnly)
      params.append("regressingOnly", options.regressingOnly);
    if (options.limit) params.append("limit", options.limit);

    const response = await apiClient.get(
      `/api/slow-queries/fingerprints/${connectionId}/list?${params.toString()}`,
    );
    return response.data;
  },

  getFingerprintTrend: async (fingerprintId) => {
    const response = await apiClient.get(
      `/api/slow-queries/fingerprints/trend/${fingerprintId}`,
    );
    return response.data;
  },

  resetFingerprintBaseline: async (fingerprintId) => {
    const response = await apiClient.post(
      `/api/slow-queries/fingerprints/${fingerprintId}/reset-baseline`,
    );
    return response.data;
  },

  processFingerprints: async (connectionId) => {
    const response = await apiClient.post(
      `/api/slow-queries/fingerprints/process/${connectionId}`,
    );
    return response.data;
  },

  // Feature 6: Explain Plan Integration
  getExplainPlan: async (connectionId, queryText, analyze = false) => {
    const response = await apiClient.post("/api/slow-queries/explain", {
      connectionId,
      queryText,
      analyze,
    });
    return response.data;
  },

  getCriticalQueryExplains: async (connectionId, limit = 5) => {
    const response = await apiClient.get(
      `/api/slow-queries/explain/critical/${connectionId}?limit=${limit}`,
    );
    return response.data;
  },

  getKeyCustomers: async (
    connectionId,
    { limit = 20, tableName = null } = {},
  ) => {
    const params = { limit };
    if (tableName) params.tableName = tableName;
    const response = await apiClient.get(
      `/api/slow-queries/key-customers/${connectionId}`,
      { params },
    );
    return response.data;
  },

  getInsights: async (connectionId, { window = "7d", limit = 20 } = {}) => {
    const response = await apiClient.get(
      `/api/slow-queries/insights/${connectionId}`,
      {
        params: { window, limit },
      },
    );
    return response.data;
  },

  getRemediationInsights: async (
    connectionId,
    { window = "7d", limit = 20 } = {},
  ) => {
    const response = await apiClient.get(
      `/api/slow-queries/insights/${connectionId}/remediation`,
      {
        params: { window, limit },
      },
    );
    return response.data;
  },

  getHotspotInsights: async (
    connectionId,
    { window = "7d", limit = 20 } = {},
  ) => {
    const response = await apiClient.get(
      `/api/slow-queries/insights/${connectionId}/hotspots`,
      {
        params: { window, limit },
      },
    );
    return response.data;
  },

  getSkewInsights: async (connectionId, { window = "7d", limit = 20 } = {}) => {
    const response = await apiClient.get(
      `/api/slow-queries/insights/${connectionId}/skew`,
      {
        params: { window, limit },
      },
    );
    return response.data;
  },

  getTailRiskInsights: async (
    connectionId,
    { window = "7d", limit = 20 } = {},
  ) => {
    const response = await apiClient.get(
      `/api/slow-queries/insights/${connectionId}/tail-risk`,
      {
        params: { window, limit },
      },
    );
    return response.data;
  },

  getPlanDriftInsights: async (
    connectionId,
    { window = "7d", limit = 20 } = {},
  ) => {
    const response = await apiClient.get(
      `/api/slow-queries/insights/${connectionId}/plan-drift`,
      {
        params: { window, limit },
      },
    );
    return response.data;
  },
};

export const slowLogSourceAPI = {
  getConfig: async (connectionId) => {
    const response = await apiClient.get(
      `/api/slow-log-source/${connectionId}`,
    );
    // Handle 204 No Content response (no config saved yet)
    if (response.status === 204 || !response.data) {
      return null;
    }
    return response.data;
  },
  saveConfig: async (payload) => {
    const response = await apiClient.post("/api/slow-log-source", payload);
    return response.data;
  },
  ingestNow: async (payload) => {
    const response = await apiClient.post(
      "/api/slow-log-source/ingest",
      payload,
    );
    return response.data;
  },
  // Async ingestion with progress tracking
  startAsyncIngestion: async (payload) => {
    const response = await apiClient.post(
      "/api/slow-log-source/ingest/async",
      payload,
    );
    return response.data;
  },
  getJobStatus: async (jobId) => {
    const response = await apiClient.get(
      `/api/slow-log-source/ingest/status/${jobId}`,
    );
    return response.data;
  },
  getActiveJob: async (connectionId) => {
    const response = await apiClient.get(
      `/api/slow-log-source/ingest/active/${connectionId}`,
    );
    return response.data;
  },
  cancelJob: async (jobId) => {
    const response = await apiClient.post(
      `/api/slow-log-source/ingest/cancel/${jobId}`,
    );
    return response.data;
  },
  resumeJob: async (jobId) => {
    const response = await apiClient.post(
      `/api/slow-log-source/ingest/resume/${jobId}`,
    );
    return response.data;
  },
  getJobHistory: async (connectionId, limit = 10) => {
    const response = await apiClient.get(
      `/api/slow-log-source/ingest/history/${connectionId}?limit=${limit}`,
    );
    return response.data;
  },
};

// Index Recommendations API
export const indexAPI = {
  getRecommendations: async (connectionId) => {
    const response = await apiClient.get(
      `/api/index-recommendations/${connectionId}`,
    );
    return response.data;
  },

  generateRecommendations: async (connectionId) => {
    const response = await apiClient.post(
      `/api/index-recommendations/generate/${connectionId}`,
    );
    return response.data;
  },

  applyRecommendation: async (id) => {
    const response = await apiClient.put(
      `/api/index-recommendations/${id}/apply`,
    );
    return response.data;
  },

  dismissRecommendation: async (id) => {
    const response = await apiClient.put(
      `/api/index-recommendations/${id}/dismiss`,
    );
    return response.data;
  },

  deleteRecommendation: async (id) => {
    const response = await apiClient.delete(`/api/index-recommendations/${id}`);
    return response.data;
  },
};

// Performance API
export const performanceAPI = {
  getPerformanceMetrics: async (connectionId) => {
    const response = await apiClient.get(`/api/performance/${connectionId}`);
    return response.data;
  },

  getDashboardData: async (connectionId, days = 30) => {
    const response = await apiClient.get(
      `/api/dashboard/performance/${connectionId}`,
      {
        params: { days },
      },
    );
    return response.data;
  },
};

// Lock Contention API
export const lockAPI = {
  getActiveLocks: async (connectionId) => {
    const response = await apiClient.get(
      `/api/lock-contention/active/${connectionId}`,
    );
    return response.data;
  },

  getStatistics: async (connectionId) => {
    const response = await apiClient.get(
      `/api/lock-contention/stats/${connectionId}`,
    );
    return response.data;
  },

  detectContentions: async (connectionId) => {
    const response = await apiClient.post(
      `/api/lock-contention/detect/${connectionId}`,
    );
    return response.data;
  },

  killSession: async (connectionId, pid) => {
    const response = await apiClient.post(
      `/api/lock-contention/kill/${connectionId}/${pid}`,
    );
    return response.data;
  },
};

// Active Queries API
export const activeQueriesAPI = {
  getActiveQueries: async (connectionId) => {
    const response = await apiClient.get(`/api/active-queries/${connectionId}`);
    return response.data;
  },

  getLatestQueries: async (connectionId) => {
    const response = await apiClient.get(
      `/api/active-queries/latest/${connectionId}`,
    );
    return response.data;
  },

  getStatistics: async (connectionId) => {
    const response = await apiClient.get(
      `/api/active-queries/stats/${connectionId}`,
    );
    return response.data;
  },

  getFilterOptions: async (connectionId) => {
    const response = await apiClient.get(
      `/api/active-queries/filter-options/${connectionId}`,
    );
    return response.data;
  },

  captureQueries: async (connectionId) => {
    const response = await apiClient.post(
      `/api/active-queries/capture/${connectionId}`,
    );
    return response.data;
  },

  killQuery: async (connectionId, pid) => {
    const response = await apiClient.post(
      `/api/active-queries/kill/${connectionId}/${pid}`,
    );
    return response.data;
  },
};

// Configuration API
export const configAPI = {
  getConfiguration: async (connectionId) => {
    const response = await apiClient.get(`/api/configuration/${connectionId}`);
    return response.data;
  },

  analyzeConfiguration: async (connectionId) => {
    const response = await apiClient.post(
      `/api/configuration/analyze/${connectionId}`,
    );
    return response.data;
  },

  updateConfiguration: async (connectionId, settings) => {
    const response = await apiClient.post(
      `/api/configuration/${connectionId}`,
      settings,
    );
    return response.data;
  },

  applyRecommendation: async (id) => {
    const response = await apiClient.put(`/api/configuration/${id}/apply`);
    return response.data;
  },

  dismissRecommendation: async (id) => {
    const response = await apiClient.put(`/api/configuration/${id}/dismiss`);
    return response.data;
  },
};

// Explain Plan API
export const explainAPI = {
  getHistory: async (connectionId) => {
    const response = await apiClient.get(
      `/api/explain/history/${connectionId}`,
    );
    return response.data;
  },

  analyzeQuery: async (connectionId, query, useAnalyze = false) => {
    const response = await apiClient.post("/api/explain/analyze", {
      connectionId,
      query,
      useAnalyze,
    });
    return response.data;
  },

  // AI rewrite for an arbitrary editor query: index-aware, sargable rewrite +
  // a plain-language diagnosis of why existing indexes aren't being used.
  // Always forceRefresh — clicking Optimize is an explicit action and must
  // regenerate a fresh rewrite for the exact query, never serve a cached one.
  optimizeQuery: async (connectionId, query) => {
    const response = await apiClient.post("/api/slow-queries/optimize", {
      connectionId,
      queryText: query,
      sampleQuery: query,
      forceRefresh: true,
    });
    return response.data;
  },

  saveHistory: async (data) => {
    const response = await apiClient.post("/api/explain/history", data);
    return response.data;
  },

  deleteHistory: async (id) => {
    const response = await apiClient.delete(`/api/explain/history/${id}`);
    return response.data;
  },
};

// Saved Queries API
export const savedQueriesAPI = {
  // Create a new saved query
  createQuery: async (queryData) => {
    const response = await apiClient.post("/api/saved-queries", queryData);
    return response.data;
  },

  // Get all saved queries for a connection
  getQueriesByConnection: async (connectionId) => {
    const response = await apiClient.get(
      `/api/saved-queries/connection/${connectionId}`,
    );
    return response.data;
  },

  // Get a specific saved query by ID
  getQueryById: async (id) => {
    const response = await apiClient.get(`/api/saved-queries/${id}`);
    return response.data;
  },

  // Update a saved query
  updateQuery: async (id, updates) => {
    const response = await apiClient.put(`/api/saved-queries/${id}`, updates);
    return response.data;
  },

  // Delete a saved query
  deleteQuery: async (id) => {
    const response = await apiClient.delete(`/api/saved-queries/${id}`);
    return response.data;
  },

  // Toggle favorite status
  toggleFavorite: async (id) => {
    const response = await apiClient.post(`/api/saved-queries/${id}/favorite`);
    return response.data;
  },

  // Get favorite queries
  getFavoriteQueries: async (connectionId) => {
    const response = await apiClient.get(
      `/api/saved-queries/connection/${connectionId}/favorites`,
    );
    return response.data;
  },

  // Get queries by folder
  getQueriesByFolder: async (connectionId, folder) => {
    const response = await apiClient.get(
      `/api/saved-queries/connection/${connectionId}/folder/${folder}`,
    );
    return response.data;
  },

  // Search queries
  searchQueries: async (connectionId, searchTerm) => {
    const response = await apiClient.get(
      `/api/saved-queries/connection/${connectionId}/search`,
      {
        params: { q: searchTerm },
      },
    );
    return response.data;
  },

  // Get distinct folders
  getFolders: async (connectionId) => {
    const response = await apiClient.get(
      `/api/saved-queries/connection/${connectionId}/folders`,
    );
    return response.data;
  },
};

// Saved Dashboards API
// OpenAI-compatible LLM proxy (the backend holds the model key; the browser
// never sees it). gpt-5.4 is a reasoning model: it requires
// `max_completion_tokens` (NOT `max_tokens`) and rejects a custom temperature.
export const llmAPI = {
  chat: async ({ messages, maxCompletionTokens = 16000, model = "gpt-5.4" }) => {
    const response = await apiClient.post("/api/llm/v1/chat/completions", {
      model,
      messages,
      max_completion_tokens: maxCompletionTokens,
    });
    return response.data;
  },
};

// Dashboard generation, delegated server-side to the embedded DeepSQL Agent.
// (Customized Hermes runtime — see agent/README.md.) The
// agent grounds on the brain + schema via MCP, writes each query, and verifies it
// by running execute_sql and inspecting the rows (dates in-window, KPI value type
// matches format, totals plausible) before emitting the spec. Returns
// { success, dashboardConfig }.
// Read-only query broker for generated dashboard artifacts. The sandboxed iframe
// posts its SQL to the parent; the parent calls this (server-side read-only guard
// + access scope) and posts rows back. Never let the iframe hit the DB directly.
export const dashboardQueryAPI = {
  run: async (connectionId, sql, limit, signal) => {
    try {
      const { data } = await apiClient.post(
        "/api/dashboards/query",
        { connectionId, sql, limit },
        { signal },
      );
      if (!data?.success) throw new Error(data?.error || "Query failed");
      return { columns: data.columns || [], rows: data.rows || [] };
    } catch (e) {
      // A non-2xx (e.g. a 400 for bad SQL) makes axios reject before the
      // success-check above ever runs. The apiClient response interceptor
      // already rewrote it into an Error whose .message only looks at
      // response.data.message — this endpoint's payload uses `error`, not
      // `message`, so it falls back to the generic "Request failed with
      // status code 400" and stashes the real body in .responseData instead.
      // Useless for the Queries panel, whose whole point is showing the real
      // reason a widget's query failed.
      const serverMessage = e?.responseData?.error;
      if (serverMessage && serverMessage !== e.message) throw new Error(serverMessage);
      throw e;
    }
  },
};

// Public (no-login) dashboard access for shared links. Plain fetch — NOT the
// authed apiClient — so the 401 interceptor never fires for anonymous viewers.
function passwordRequired(data) {
  const err = new Error(data?.error || "Password required");
  err.requiresPassword = true;
  return err;
}
export const publicDashboardAPI = {
  get: async (token, password) => {
    const res = await fetch(`${API_BASE_URL}/api/public/dashboards/${encodeURIComponent(token)}`, {
      headers: password ? { "X-Dashboard-Password": password } : {},
    });
    const data = await res.json().catch(() => ({}));
    if (res.status === 401 && data?.requiresPassword) throw passwordRequired(data);
    if (!res.ok || !data?.success) throw new Error(res.status === 404 ? "This dashboard link is unavailable." : (data?.error || `HTTP ${res.status}`));
    return data;
  },
  query: async (token, sql, limit, signal, password) => {
    const res = await fetch(`${API_BASE_URL}/api/public/dashboards/${encodeURIComponent(token)}/query`, {
      method: "POST",
      headers: { "Content-Type": "application/json", ...(password ? { "X-Dashboard-Password": password } : {}) },
      body: JSON.stringify({ sql, limit }),
      signal,
    });
    const data = await res.json().catch(() => ({}));
    if (res.status === 401 && data?.requiresPassword) throw passwordRequired(data);
    if (!res.ok || !data?.success) throw new Error(data?.error || `HTTP ${res.status}`);
    return { columns: data.columns || [], rows: data.rows || [] };
  },
};

export const dashboardGenAPI = {
  generate: async (connectionId, prompt, currentConfig) => {
    const response = await apiClient.post("/api/dashboards/generate", {
      connectionId,
      prompt,
      currentConfig: currentConfig || null,
    });
    return response.data;
  },

  // Streaming variant: POST + Server-Sent Events so the UI can show the agent's
  // live grounding/validation steps. POST (not native EventSource) because the
  // body carries the current dashboard config when refining. Handlers:
  //   onStep({ type, message }) · onDone({ success, dashboardConfig }) · onError(Error)
  // Returns an abort fn.
  generateStream: (connectionId, prompt, currentConfig, dashboardId, handlers = {}) => {
    const { onStep, onChunk, onDone, onError, onCreated } = handlers;
    const controller = new AbortController();
    const body = JSON.stringify({ connectionId, prompt, currentConfig: currentConfig || null, dashboardId: dashboardId || null });
    const doFetch = () =>
      fetch(`${API_BASE_URL}/api/dashboards/generate/stream`, {
        method: "POST",
        headers: { "Content-Type": "application/json", Accept: "text/event-stream" },
        credentials: "include",
        body,
        signal: controller.signal,
      });
    // The generation itself now survives a route change (the store that owns
    // this call keeps running after the component unmounts) — but a page
    // *reload/close* still tears down this fetch out from under us. Calling
    // controller.abort() from a pagehide listener does NOT reliably turn this
    // into an AbortError first: the browser's own network-stack teardown on
    // navigation can reject the fetch with a bare `TypeError: Failed to fetch`
    // — indistinguishable in shape from a genuinely dead backend — before our
    // abort() call is even processed. Track unload via a flag instead of
    // trusting the error's identity, and check the flag in the catch block
    // below so an unload-induced failure is dropped rather than surfacing as
    // a fake error that gets permanently written into the saved chat history.
    let unloading = false;
    const markUnloading = () => { unloading = true; controller.abort(); };
    window.addEventListener("pagehide", markUnloading);
    (async () => {
      try {
        let res = await doFetch();
        // The 15-min access token can lapse while the dashboard sits open. This
        // raw-fetch SSE path bypasses the axios 401 interceptor, so on a 401
        // refresh the session once (deduped app-wide) and retry before surfacing
        // the error — mirrors agentClient's postJson. Without this, an idle tab
        // shows a bare "HTTP 401" bubble on the next dashboard edit.
        if (res.status === 401) {
          try {
            await requestSessionRefresh();
            res = await doFetch();
          } catch {
            /* refresh failed — fall through and surface the original 401 */
          }
        }
        if (!res.ok || !res.body) {
          let msg = `HTTP ${res.status}`;
          try { const j = await res.json(); msg = j.error || msg; } catch { /* non-JSON */ }
          throw new Error(msg);
        }
        const reader = res.body.getReader();
        const decoder = new TextDecoder();
        let buffer = "";
        let finished = false;
        const flush = (raw) => {
          let event = "message";
          const dataLines = [];
          for (const line of raw.split("\n")) {
            if (line.startsWith("event:")) event = line.slice(6).trim();
            else if (line.startsWith("data:")) dataLines.push(line.slice(5).trim());
          }
          if (!dataLines.length) return;
          let data = {};
          try { data = JSON.parse(dataLines.join("\n")); } catch { return; }
          // Sent immediately, before any step — the backend has already
          // resolved (or created) the dashboard and durably recorded the
          // user's message server-side, so the caller can adopt this id
          // right away rather than waiting for the whole generation to finish.
          if (event === "created") onCreated && onCreated(data);
          else if (event === "step") onStep && onStep(data);
          else if (event === "chunk") onChunk && onChunk(data);
          // `chat` = out-of-context reply (hi/thanks); must not fall through to `done`
          // or the workspace appends the canned "Done — built…" save message.
          else if (event === "chat") { finished = true; onDone && onDone(data); }
          else if (event === "done") { finished = true; onDone && onDone(data); }
          else if (event === "error") { finished = true; onError && onError(new Error(data?.error || "Generation failed")); }
        };
        for (;;) {
          const { value, done } = await reader.read();
          if (done) break;
          buffer += decoder.decode(value, { stream: true });
          let idx;
          while ((idx = buffer.indexOf("\n\n")) >= 0) {
            flush(buffer.slice(0, idx));
            buffer = buffer.slice(idx + 2);
          }
        }
        if (!finished) onError && onError(new Error("Generation ended unexpectedly"));
      } catch (e) {
        if (e?.name === "AbortError" || unloading) return;
        onError && onError(e);
      } finally {
        window.removeEventListener("pagehide", markUnloading);
      }
    })();
    return () => {
      window.removeEventListener("pagehide", markUnloading);
      controller.abort();
    };
  },
};

// Retrieval brain context — the same grounding (relevant tables/columns/FKs,
// business definitions, rules) the DeepSQL agent retrieves via get_brain_context.
export const retrievalAPI = {
  getContext: async (connectionId, question) => {
    const response = await apiClient.post(
      `/api/training/context/${connectionId}`,
      { question },
    );
    return response.data;
  },
};

export const savedDashboardsAPI = {
  // Create a new saved dashboard
  createDashboard: async (dashboardData) => {
    const response = await apiClient.post(
      "/api/saved-dashboards",
      dashboardData,
    );
    return response.data;
  },

  // Get all saved dashboards for a connection
  getDashboardsByConnection: async (connectionId) => {
    const response = await apiClient.get(
      `/api/saved-dashboards/connection/${connectionId}`,
    );
    return response.data;
  },

  // Get a specific saved dashboard by ID
  getDashboardById: async (id) => {
    const response = await apiClient.get(`/api/saved-dashboards/${id}`);
    return response.data;
  },

  // Update a saved dashboard
  updateDashboard: async (id, updates) => {
    const response = await apiClient.put(
      `/api/saved-dashboards/${id}`,
      updates,
    );
    return response.data;
  },

  // Publish a dashboard to the web (opt-in, revocable). Returns { shareToken }.
  enableShare: async (id) => {
    const response = await apiClient.post(`/api/saved-dashboards/${id}/share`);
    return response.data;
  },

  // Revoke the public link.
  disableShare: async (id) => {
    const response = await apiClient.delete(`/api/saved-dashboards/${id}/share`);
    return response.data;
  },

  // Set (or, with an empty value, clear) the public link's password.
  setSharePassword: async (id, password) => {
    const response = await apiClient.put(`/api/saved-dashboards/${id}/share/password`, { password: password || "" });
    return response.data;
  },

  // Delete a saved dashboard
  deleteDashboard: async (id) => {
    const response = await apiClient.delete(`/api/saved-dashboards/${id}`);
    return response.data;
  },

  // Toggle favorite status
  toggleFavorite: async (id) => {
    const response = await apiClient.post(
      `/api/saved-dashboards/${id}/favorite`,
    );
    return response.data;
  },

  // Get favorite dashboards
  getFavoriteDashboards: async (connectionId) => {
    const response = await apiClient.get(
      `/api/saved-dashboards/connection/${connectionId}/favorites`,
    );
    return response.data;
  },

  // Get dashboards by folder
  getDashboardsByFolder: async (connectionId, folder) => {
    const response = await apiClient.get(
      `/api/saved-dashboards/connection/${connectionId}/folder/${folder}`,
    );
    return response.data;
  },

  // Search dashboards
  searchDashboards: async (connectionId, searchTerm) => {
    const response = await apiClient.get(
      `/api/saved-dashboards/connection/${connectionId}/search`,
      {
        params: { q: searchTerm },
      },
    );
    return response.data;
  },

  // Get distinct folders
  getFolders: async (connectionId) => {
    const response = await apiClient.get(
      `/api/saved-dashboards/connection/${connectionId}/folders`,
    );
    return response.data;
  },

  // Duplicate a dashboard as a new, independent draft
  cloneDashboard: async (id) => {
    const response = await apiClient.post(`/api/saved-dashboards/${id}/clone`);
    return response.data;
  },

  // Version history, most recent first
  getVersionHistory: async (id) => {
    const response = await apiClient.get(`/api/saved-dashboards/${id}/versions`);
    return response.data;
  },

  // Restore a prior version as the current config
  restoreVersion: async (id, versionId) => {
    const response = await apiClient.post(
      `/api/saved-dashboards/${id}/versions/${versionId}/restore`,
    );
    return response.data;
  },

  // Natural-language alerts, evaluated on a schedule by the DeepSQL agent
  getAlerts: async (id) => {
    const response = await apiClient.get(`/api/saved-dashboards/${id}/alerts`);
    return response.data;
  },
  createAlert: async (id, alert) => {
    const response = await apiClient.post(`/api/saved-dashboards/${id}/alerts`, alert);
    return response.data;
  },
  updateAlert: async (id, alertId, updates) => {
    const response = await apiClient.put(`/api/saved-dashboards/${id}/alerts/${alertId}`, updates);
    return response.data;
  },
  deleteAlert: async (id, alertId) => {
    const response = await apiClient.delete(`/api/saved-dashboards/${id}/alerts/${alertId}`);
    return response.data;
  },
};

// Playbook API
export const playbookAPI = {
  // Get all playbooks
  getAllPlaybooks: async (params = {}) => {
    const response = await apiClient.get("/api/playbooks", { params });
    return response.data;
  },

  // Get specific playbook
  getPlaybook: async (playbookId) => {
    const response = await apiClient.get(`/api/playbooks/${playbookId}`);
    return response.data;
  },

  // Create playbook
  createPlaybook: async (playbookData) => {
    const response = await apiClient.post("/api/playbooks", playbookData);
    return response.data;
  },

  // Update playbook
  updatePlaybook: async (playbookId, playbookData) => {
    const response = await apiClient.put(
      `/api/playbooks/${playbookId}`,
      playbookData,
    );
    return response.data;
  },

  // Delete playbook
  deletePlaybook: async (playbookId) => {
    const response = await apiClient.delete(`/api/playbooks/${playbookId}`);
    return response.data;
  },

  // Toggle playbook active status
  togglePlaybook: async (playbookId) => {
    const response = await apiClient.post(
      `/api/playbooks/${playbookId}/toggle`,
    );
    return response.data;
  },

  // Execute playbook
  executePlaybook: async (playbookId, connectionId) => {
    const response = await apiClient.post(
      `/api/playbooks/${playbookId}/execute`,
      {
        connectionId,
      },
    );
    return response.data;
  },

  // Get run history
  getRunHistory: async (connectionId, limit = 50) => {
    const response = await apiClient.get(
      `/api/playbooks/runs/${connectionId}`,
      {
        params: { limit },
      },
    );
    return response.data;
  },

  // Cancel run
  cancelRun: async (runId) => {
    const response = await apiClient.post(
      `/api/playbooks/runs/${runId}/cancel`,
    );
    return response.data;
  },

  // Get alerts
  getAlerts: async (connectionId, unacknowledgedOnly = false) => {
    const response = await apiClient.get(
      `/api/playbooks/alerts/${connectionId}`,
      {
        params: { unacknowledgedOnly },
      },
    );
    return response.data;
  },

  // Acknowledge alert
  acknowledgeAlert: async (alertId, acknowledgedBy = "user") => {
    const response = await apiClient.post(
      `/api/playbooks/alerts/${alertId}/acknowledge`,
      {
        acknowledgedBy,
      },
    );
    return response.data;
  },
};

// Growth Monitoring API
export const growthMonitoringAPI = {
  // Get growth history
  getGrowthHistory: async (connectionId, tableName = null, days = 7) => {
    const params = { days };
    if (tableName) {
      params.tableName = tableName;
    }
    const response = await apiClient.get(
      `/api/growth-monitoring/history/${connectionId}`,
      {
        params,
      },
    );
    return response.data;
  },

  // Get detected anomalies
  getAnomalies: async (
    connectionId,
    tableName = null,
    unacknowledgedOnly = false,
    days = 30,
  ) => {
    const params = {
      unacknowledgedOnly,
      days,
    };
    if (tableName) {
      params.tableName = tableName;
    }
    const response = await apiClient.get(
      `/api/growth-monitoring/anomalies/${connectionId}`,
      {
        params,
      },
    );
    return response.data;
  },

  // Acknowledge anomaly
  acknowledgeAnomaly: async (anomalyId, acknowledgedBy = "user") => {
    const response = await apiClient.post(
      `/api/growth-monitoring/anomalies/${anomalyId}/acknowledge`,
      {
        acknowledgedBy,
      },
    );
    return response.data;
  },

  // Get alert configuration
  getConfiguration: async (connectionId, tableName = null) => {
    const params = {};
    if (tableName) {
      params.tableName = tableName;
    }
    const response = await apiClient.get(
      `/api/growth-monitoring/config/${connectionId}`,
      {
        params,
      },
    );
    return response.data;
  },

  // Save alert configuration
  saveConfiguration: async (config) => {
    const response = await apiClient.post(
      "/api/growth-monitoring/config",
      config,
    );
    return response.data;
  },

  // Get growth trends for charts
  getGrowthTrends: async (connectionId, tableName = null, days = 30) => {
    const params = { days };
    if (tableName) {
      params.tableName = tableName;
    }
    const response = await apiClient.get(
      `/api/growth-monitoring/trends/${connectionId}`,
      {
        params,
      },
    );
    return response.data;
  },

  // Manual trigger for snapshot capture (testing/debugging)
  // Note: Now runs asynchronously - returns immediately while capture happens in background
  manualCapture: async (connectionId) => {
    const response = await apiClient.post(
      `/api/growth-monitoring/capture/${connectionId}`,
      {},
      { timeout: 30000 }, // 30 seconds - just to start the async job
    );
    return response.data;
  },

  // Manual trigger for data cleanup (testing/debugging)
  manualCleanup: async () => {
    const response = await apiClient.post("/api/growth-monitoring/cleanup");
    return response.data;
  },
};

// ========== SENTINEL-DBA ANALYTICS API ==========

export const sentinelAPI = {
  // Get Death Clock - critical resource exhaustion forecasts
  getDeathClock: async (connectionId) => {
    const response = await apiClient.get(
      `/api/sentinel/death-clock/${connectionId}`,
    );
    return response.data;
  },

  // Get all capacity forecasts
  getForecasts: async (connectionId, tableName = null) => {
    const params = tableName ? { tableName } : {};
    const response = await apiClient.get(
      `/api/sentinel/forecasts/${connectionId}`,
      { params },
    );
    return response.data;
  },

  // Generate new forecast for a table
  generateForecast: async (connectionId, tableName) => {
    const response = await apiClient.post(
      `/api/sentinel/forecasts/${connectionId}`,
      null,
      {
        params: { tableName },
      },
    );
    return response.data;
  },

  // Calculate velocity and acceleration
  getVelocity: async (connectionId, tableName, historicalDays = 30) => {
    const response = await apiClient.get(
      `/api/sentinel/velocity/${connectionId}`,
      {
        params: { tableName, historicalDays },
      },
    );
    return response.data;
  },

  // Get database events (deployments, schema changes, etc.)
  getEvents: async (connectionId, days = 30) => {
    const response = await apiClient.get(
      `/api/sentinel/events/${connectionId}`,
      {
        params: { days },
      },
    );
    return response.data;
  },

  // Log a deployment event
  logDeploymentEvent: async (eventData) => {
    const response = await apiClient.post(
      "/api/sentinel/events/deployment",
      eventData,
    );
    return response.data;
  },

  // Log a schema change event
  logSchemaChangeEvent: async (eventData) => {
    const response = await apiClient.post(
      "/api/sentinel/events/schema-change",
      eventData,
    );
    return response.data;
  },

  // Get AI-generated recommendations
  getRecommendations: async (connectionId, status = null, priority = null) => {
    const params = {};
    if (status) params.status = status;
    if (priority) params.priority = priority;
    const response = await apiClient.get(
      `/api/sentinel/recommendations/${connectionId}`,
      { params },
    );
    return response.data;
  },

  // Update recommendation status
  updateRecommendationStatus: async (
    recommendationId,
    status,
    updatedBy = "user",
  ) => {
    const response = await apiClient.post(
      `/api/sentinel/recommendations/${recommendationId}/status`,
      { status, updatedBy },
    );
    return response.data;
  },

  // Get Sentinel executive summary
  getSummary: async (connectionId) => {
    const response = await apiClient.get(
      `/api/sentinel/summary/${connectionId}`,
    );
    return response.data;
  },
};

// Query Performance API
export const queryPerformanceAPI = {
  // Record a query execution
  recordQueryExecution: async (data) => {
    const response = await apiClient.post(
      "/api/query-performance/record",
      data,
    );
    return response.data;
  },

  // Get all tracked queries for a connection
  getTrackedQueries: async (connectionId) => {
    const response = await apiClient.get(
      `/api/query-performance/queries/${connectionId}`,
    );
    return response.data;
  },

  // Get performance history for a specific query
  getQueryHistory: async (connectionId, queryHash, days = 7) => {
    const response = await apiClient.get(
      `/api/query-performance/history/${connectionId}/${queryHash}`,
      { params: { days } },
    );
    return response.data;
  },

  // Get performance trend data for charting
  getPerformanceTrend: async (connectionId, queryHash, days = 7) => {
    const response = await apiClient.get(
      `/api/query-performance/trend/${connectionId}/${queryHash}`,
      { params: { days } },
    );
    return response.data;
  },

  // Get all performance regressions
  getRegressions: async (connectionId, unacknowledgedOnly = false) => {
    const response = await apiClient.get(
      `/api/query-performance/regressions/${connectionId}`,
      { params: { unacknowledgedOnly } },
    );
    return response.data;
  },

  // Acknowledge a regression
  acknowledgeRegression: async (regressionId, acknowledgedBy = "user") => {
    const response = await apiClient.post(
      `/api/query-performance/regressions/${regressionId}/acknowledge`,
      { acknowledgedBy },
    );
    return response.data;
  },

  // Resolve a regression
  resolveRegression: async (regressionId, resolutionNotes) => {
    const response = await apiClient.post(
      `/api/query-performance/regressions/${regressionId}/resolve`,
      { resolutionNotes },
    );
    return response.data;
  },

  // Trigger manual analysis
  triggerAnalysis: async (connectionId) => {
    const response = await apiClient.post(
      `/api/query-performance/analyze/${connectionId}`,
    );
    return response.data;
  },
};

// Resource Limits API
export const resourceLimitsAPI = {
  getResourceLimits: async (connectionId) => {
    const response = await apiClient.get(
      `/api/resource-limits/${connectionId}`,
    );
    return response.data;
  },

  saveResourceLimits: async (connectionId, limits) => {
    const response = await apiClient.post("/api/resource-limits", {
      connectionId,
      ...limits,
    });
    return response.data;
  },

  deleteResourceLimits: async (connectionId) => {
    const response = await apiClient.delete(
      `/api/resource-limits/${connectionId}`,
    );
    return response.data;
  },
};

// Monitoring API
export const monitoringAPI = {
  triggerSnapshot: async (connectionId) => {
    const response = await apiClient.post(
      `/api/growth-monitoring/capture/${connectionId}`,
    );
    return response.data;
  },

  getGrowthHistory: async (connectionId, days = 30, tableName = null) => {
    const params = { days };
    if (tableName) params.tableName = tableName;
    const response = await apiClient.get(
      `/api/growth-monitoring/history/${connectionId}`,
      { params },
    );
    return response.data;
  },
};

// Performance Insights API
export const performanceInsightsAPI = {
  // Get performance snapshots for a time range
  getSnapshots: async (connectionId, hours = 1) => {
    const response = await apiClient.get(
      `/api/performance-insights/${connectionId}`,
      {
        params: { hours },
      },
    );
    return response.data;
  },

  // Get recent snapshots
  getRecentSnapshots: async (connectionId, limit = 12) => {
    const response = await apiClient.get(
      `/api/performance-insights/${connectionId}/recent`,
      {
        params: { limit },
      },
    );
    return response.data;
  }, // Manually trigger snapshot collection
  collectSnapshot: async (connectionId) => {
    const response = await apiClient.post(
      `/api/performance-insights/${connectionId}/collect`,
    );
    return response.data;
  }, // Get performance summary
  getSummary: async (connectionId, hours = 1) => {
    const response = await apiClient.get(
      `/api/performance-insights/${connectionId}/summary`,
      {
        params: { hours },
      },
    );
    return response.data;
  },

  // Get table usage statistics for heatmap
  getTableUsage: async (connectionId) => {
    const response = await apiClient.get(
      `/api/performance-insights/table-usage/${connectionId}`,
    );
    return response.data;
  },
};

// Feedback API - User feedback on AI responses
export const feedbackAPI = {
  // Record thumbs up
  thumbsUp: async (data) => {
    const response = await apiClient.post("/api/feedback/thumbs-up", data);
    return response.data;
  },

  // Record thumbs down
  thumbsDown: async (data) => {
    const response = await apiClient.post("/api/feedback/thumbs-down", data);
    return response.data;
  },

  // Record a correction
  correction: async (data) => {
    const response = await apiClient.post("/api/feedback/correction", data);
    return response.data;
  },

  // Record a teaching
  teaching: async (data) => {
    const response = await apiClient.post("/api/feedback/teaching", data);
    return response.data;
  },

  // Record column values
  columnValues: async (data) => {
    const response = await apiClient.post("/api/feedback/column-values", data);
    return response.data;
  },

  // Get recent feedback for a connection
  getRecent: async (connectionId, limit = 50) => {
    const response = await apiClient.get(
      `/api/feedback/connection/${connectionId}`,
      {
        params: { limit },
      },
    );
    return response.data;
  },

  // Get feedback by type
  getByType: async (connectionId, type) => {
    const response = await apiClient.get(
      `/api/feedback/connection/${connectionId}/type/${type}`,
    );
    return response.data;
  },

  // Get feedback for a chat
  getForChat: async (chatId) => {
    const response = await apiClient.get(`/api/feedback/chat/${chatId}`);
    return response.data;
  },

  // Get feedback for a table
  getForTable: async (connectionId, tableName) => {
    const response = await apiClient.get(
      `/api/feedback/connection/${connectionId}/table/${tableName}`,
    );
    return response.data;
  },

  // Get feedback statistics
  getStats: async (connectionId) => {
    const response = await apiClient.get(
      `/api/feedback/connection/${connectionId}/stats`,
    );
    return response.data;
  },

  // Delete feedback
  delete: async (feedbackId) => {
    const response = await apiClient.delete(`/api/feedback/${feedbackId}`);
    return response.data;
  },

  // Re-embed all unembedded feedback
  reembed: async (connectionId) => {
    const response = await apiClient.post(
      `/api/feedback/connection/${connectionId}/reembed`,
    );
    return response.data;
  },
};

// Business Rule API - Connection-scoped SQL guardrails learned from feedback
export const businessRuleAPI = {
  // Fetch active rules and question-applicable guardrails
  getForConnection: async (connectionId, question = null) => {
    const params = {};
    if (question) params.question = question;
    const response = await apiClient.get(
      `/api/business-rules/connection/${connectionId}`,
      { params },
    );
    return response.data;
  },

  // Learn rules from free text
  learn: async (connectionId, data) => {
    const response = await apiClient.post(
      `/api/business-rules/connection/${connectionId}/learn`,
      data,
    );
    return response.data;
  },
};

// Performance Actions API - Unified performance recommendations
export const performanceActionsAPI = {
  // Get all pending actions sorted by ROI
  getActions: async (connectionId) => {
    const response = await apiClient.get(
      `/api/performance-actions/${connectionId}`,
    );
    return response.data;
  },

  // Get top N actions by ROI
  getTopActions: async (connectionId, limit = 10) => {
    const response = await apiClient.get(
      `/api/performance-actions/${connectionId}/top`,
      {
        params: { limit },
      },
    );
    return response.data;
  },

  // Get actions by category
  getActionsByCategory: async (connectionId, category) => {
    const response = await apiClient.get(
      `/api/performance-actions/${connectionId}/category/${category}`,
    );
    return response.data;
  },

  // Get actions by source
  getActionsBySource: async (connectionId, source) => {
    const response = await apiClient.get(
      `/api/performance-actions/${connectionId}/source/${source}`,
    );
    return response.data;
  },

  // Get summary statistics
  getSummary: async (connectionId) => {
    const response = await apiClient.get(
      `/api/performance-actions/${connectionId}/summary`,
    );
    return response.data;
  },

  // Refresh actions from all sources
  refreshActions: async (connectionId) => {
    const response = await apiClient.post(
      `/api/performance-actions/${connectionId}/refresh`,
    );
    return response.data;
  },

  // Get affected queries for an action (from latest slow query analysis)
  getAffectedQueries: async (actionId) => {
    const response = await apiClient.get(
      `/api/performance-actions/action/${actionId}/affected-queries`,
    );
    return response.data;
  },

  // Update action status
  updateStatus: async (actionId, status, resolvedBy = null, notes = null) => {
    const response = await apiClient.put(
      `/api/performance-actions/${actionId}/status`,
      {
        status,
        resolvedBy,
        notes,
      },
    );
    return response.data;
  },

  // Batch update action statuses
  batchUpdateStatus: async (
    actionIds,
    status,
    resolvedBy = null,
    notes = null,
  ) => {
    const response = await apiClient.put(
      "/api/performance-actions/batch-status",
      {
        actionIds,
        status,
        resolvedBy,
        notes,
      },
    );
    return response.data;
  },
};

// Setup / Onboarding API
export const setupAPI = {
  createBootstrapLink: async ({ orgName, username, email }) => {
    const response = await apiClient.post("/api/admin/bootstrap/link", {
      orgName,
      username,
      email,
    });
    return response.data;
  },

  initializeApp: async ({ orgName, adminUsername, adminEmail }) => {
    const response = await apiClient.post("/api/admin/bootstrap/link", {
      orgName,
      username: adminUsername,
      email: adminEmail,
    });
    return response.data;
  },

  /**
   * Returns current setup state: setupComplete, hasOrganizationInfo,
   * hasConnections, hasLlmConfig. Public endpoint — no auth required.
   */
  getStatus: async () => {
    const response = await apiClient.get("/api/setup/status");
    return response.data;
  },

  /**
   * Persist organisation name and team size.
   */
  saveOrganization: async ({ orgName, teamSize }) => {
    const response = await apiClient.post("/api/setup/organization", {
      orgName,
      teamSize,
    });
    return response.data;
  },

  /**
   * Get the currently saved LLM config (API key is masked).
   */
  getLlmConfig: async () => {
    const response = await apiClient.get("/api/setup/llm-config");
    return response.data;
  },

  /**
   * Save the LLM provider config and encrypted API key.
   */
  saveLlmConfig: async ({ provider, apiKey, endpoint, chatModel }) => {
    const response = await apiClient.post("/api/setup/llm-config", {
      provider,
      apiKey,
      endpoint,
      chatModel,
    });
    return response.data;
  },

  /**
   * Test an LLM provider API key without saving it.
   * Returns { valid: boolean, error?: string }
   */
  testLlmConfig: async ({ provider, apiKey, endpoint }) => {
    const response = await apiClient.post("/api/setup/llm-config/test", {
      provider,
      apiKey,
      endpoint,
    });
    return response.data;
  },

  /**
   * Mark setup as complete; after this the app behaves normally.
   */
  markComplete: async () => {
    const response = await apiClient.post("/api/setup/complete");
    return response.data;
  },
};

export const slackDigestAPI = {
  listDigests: (params = {}) =>
    apiClient.get("/api/admin/slack/digests", { params }).then((r) => r.data),
  triggerDigest: () =>
    apiClient.post("/api/admin/slack/digest/trigger").then((r) => r.data),
  getConfig: () =>
    apiClient.get("/api/admin/slack/digest/config").then((r) => r.data),
  updateConfig: (data) =>
    apiClient.put("/api/admin/slack/digest/config", data).then((r) => r.data),
};

/**
 * Slow-query analytics — the 30-day per-query time series, regressions,
 * and per-customer breakdown. Backed by /api/slow-query-analytics/**.
 */
export const slowQueryAnalyticsAPI = {
  getQueries: (connectionId) =>
    apiClient
      .get(`/api/slow-query-analytics/${connectionId}/queries`)
      .then((r) => r.data),
  getTimeline: (connectionId, fingerprint) =>
    apiClient
      .get(`/api/slow-query-analytics/${connectionId}/timeline/${fingerprint}`)
      .then((r) => r.data),
  getRegressions: (connectionId, minFactor = 1.5) =>
    apiClient
      .get(`/api/slow-query-analytics/${connectionId}/regressions`, {
        params: { minFactor },
      })
      .then((r) => r.data),
  getCustomers: (connectionId, fingerprint, day = null) =>
    apiClient
      .get(
        `/api/slow-query-analytics/${connectionId}/query/${fingerprint}/customers`,
        { params: day ? { day } : {} },
      )
      .then((r) => r.data),
  getSamples: (connectionId, fingerprint) =>
    apiClient
      .get(`/api/slow-query-analytics/${connectionId}/query/${fingerprint}/samples`)
      .then((r) => r.data),
  listCustomers: (connectionId) =>
    apiClient
      .get(`/api/slow-query-analytics/${connectionId}/customers`)
      .then((r) => r.data),
  getCustomerQueries: (connectionId, customerId) =>
    apiClient
      .get(`/api/slow-query-analytics/${connectionId}/customer/${customerId}/queries`)
      .then((r) => r.data),
  getCustomerQuerySamples: (connectionId, customerId, fingerprint) =>
    apiClient
      .get(
        `/api/slow-query-analytics/${connectionId}/customer/${customerId}/query/${fingerprint}/samples`,
      )
      .then((r) => r.data),
  getTenantColumnSuggestions: (connectionId) =>
    apiClient
      .get(`/api/slow-query-analytics/${connectionId}/tenant-column-suggestions`)
      .then((r) => r.data),
  getConfig: (connectionId) =>
    apiClient
      .get(`/api/slow-query-analytics/${connectionId}/config`)
      .then((r) => r.data),
  putConfig: (connectionId, body) =>
    apiClient
      .put(`/api/slow-query-analytics/${connectionId}/config`, body)
      .then((r) => r.data),
  analyzeNow: (connectionId) =>
    apiClient
      .post(`/api/slow-query-analytics/${connectionId}/analyze-now`, undefined, { timeout: 300000 })
      .then((r) => r.data),
  resetAnalytics: (connectionId) =>
    apiClient
      .delete(`/api/slow-query-analytics/${connectionId}/reset`)
      .then((r) => r.data),
};

/**
 * Holistic Workload Analysis — the on-demand orchestrator that composes one
 * report (index recs, rewrites, pre-aggregation, index health). `run` returns
 * immediately with a reportId; the job runs in the background, polled via
 * `status` and read in full via `latest` / `getReport`.
 */
export const workloadAnalysisAPI = {
  run: (connectionId) =>
    apiClient
      .post(`/api/workload-analysis/${connectionId}/run`, undefined, { timeout: 60000 })
      .then((r) => r.data),
  status: (connectionId) =>
    apiClient
      .get(`/api/workload-analysis/${connectionId}/status`)
      .then((r) => (r.status === 204 ? null : r.data)),
  latest: (connectionId) =>
    apiClient
      .get(`/api/workload-analysis/${connectionId}/latest`)
      .then((r) => (r.status === 204 ? null : r.data)),
  history: (connectionId) =>
    apiClient
      .get(`/api/workload-analysis/${connectionId}/history`)
      .then((r) => r.data),
  getReport: (reportId) =>
    apiClient
      .get(`/api/workload-analysis/report/${reportId}`)
      .then((r) => r.data),
};

export default apiClient;
