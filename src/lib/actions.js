/**
 * Actions Configuration - SINGLE SOURCE OF TRUTH
 *
 * This file maps UI action names to permissions.
 * Components use action names, not permission names directly.
 *
 * Benefits:
 * - Rename permission → change 1 file, not 50
 * - Split permission → update mapping only
 * - Components don't know about permission system
 * - Easy to add new features
 *
 * Adding a new feature:
 * 1. Add action here with permission
 * 2. Wrap feature with <ActionGuard action="action-name">
 * Done!
 */

import { PERMISSIONS } from './permissions'

/**
 * Action definitions.
 * Each action maps to a permission and includes metadata.
 *
 * @property {string} permission - The permission code required
 * @property {string} label - Human-readable label for the action
 * @property {string} [disabledMessage] - Message shown when action is disabled
 * @property {boolean} [hideWhenDisabled] - If true, hide instead of disable
 */
export const ACTIONS = {
  // ==================== QUERY ACTIONS ====================
  'execute-query': {
    permission: PERMISSIONS.EXECUTE_QUERIES,
    label: 'Execute Query',
    disabledMessage: 'You need Developer role to execute queries',
  },
  'kill-query': {
    permission: PERMISSIONS.EXECUTE_QUERIES,
    label: 'Kill Query',
    disabledMessage: 'You need Developer role to kill queries',
  },
  'save-query': {
    permission: PERMISSIONS.EXECUTE_QUERIES,
    label: 'Save Query',
    disabledMessage: 'You need Developer role to save queries',
  },

  // ==================== CHAT ACTIONS ====================
  'use-chat': {
    permission: PERMISSIONS.USE_CHAT,
    label: 'Chat',
    disabledMessage: 'You need Developer role to use chat',
  },

  // ==================== ANALYSIS ACTIONS ====================
  'analyze-schema': {
    permission: PERMISSIONS.RUN_ANALYSIS,
    label: 'Analyze Schema',
    disabledMessage: 'You need Developer role to run analysis',
  },
  'analyze-key-columns': {
    permission: PERMISSIONS.RUN_ANALYSIS,
    label: 'Analyze Key Columns',
    disabledMessage: 'You need Developer role to run analysis',
  },
  'analyze-anti-patterns': {
    permission: PERMISSIONS.RUN_ANALYSIS,
    label: 'Detect Anti-Patterns',
    disabledMessage: 'You need Developer role to run analysis',
  },
  'analyze-query-patterns': {
    permission: PERMISSIONS.RUN_ANALYSIS,
    label: 'Analyze Query Patterns',
    disabledMessage: 'You need Developer role to run analysis',
  },
  'analyze-slow-queries': {
    permission: PERMISSIONS.RUN_ANALYSIS,
    label: 'Analyze Slow Queries',
    disabledMessage: 'You need Developer role to run analysis',
  },
  'optimize-query': {
    permission: PERMISSIONS.RUN_ANALYSIS,
    label: 'Optimize Query',
    disabledMessage: 'You need Developer role to optimize queries',
  },
  'refresh-brain': {
    permission: PERMISSIONS.RUN_ANALYSIS,
    label: 'Refresh Brain Data',
    disabledMessage: 'You need Developer role to refresh data',
  },
  'refresh-column-values': {
    permission: PERMISSIONS.RUN_ANALYSIS,
    label: 'Refresh Column Values',
    disabledMessage: 'You need Developer role to refresh column values',
  },
  'embed-column-values': {
    permission: PERMISSIONS.RUN_ANALYSIS,
    label: 'Embed Column Values',
    disabledMessage: 'You need Developer role to embed column values',
  },
  'embed-data': {
    permission: PERMISSIONS.RUN_ANALYSIS,
    label: 'Embed Data',
    disabledMessage: 'You need Developer role to embed data',
  },
  'run-scalability-simulation': {
    permission: PERMISSIONS.RUN_ANALYSIS,
    label: 'Run Scalability Simulation',
    disabledMessage: 'You need Developer role to run simulations',
  },

  // ==================== BRAIN 2.0 ML ACTIONS ====================
  // Config Tuning
  'identify-knobs': {
    permission: PERMISSIONS.RUN_ANALYSIS,
    label: 'Identify Knobs',
    disabledMessage: 'You need Developer role to identify configuration knobs',
  },
  'generate-recommendations': {
    permission: PERMISSIONS.RUN_ANALYSIS,
    label: 'Generate Recommendations',
    disabledMessage: 'You need Developer role to generate tuning recommendations',
  },
  'create-experiment': {
    permission: PERMISSIONS.RUN_ANALYSIS,
    label: 'Create Experiment',
    disabledMessage: 'You need Developer role to create tuning experiments',
  },
  // Workload Intelligence
  'collect-metrics': {
    permission: PERMISSIONS.RUN_ANALYSIS,
    label: 'Collect Metrics',
    disabledMessage: 'You need Developer role to collect workload metrics',
  },
  'characterize-workload': {
    permission: PERMISSIONS.RUN_ANALYSIS,
    label: 'Characterize Workload',
    disabledMessage: 'You need Developer role to characterize workload patterns',
  },

  // ==================== INGESTION ACTIONS ====================
  'run-ingestion': {
    permission: PERMISSIONS.RUN_INGESTION,
    label: 'Run Ingestion',
    disabledMessage: 'You need Developer role to run ingestion',
  },
  'configure-source': {
    permission: PERMISSIONS.RUN_INGESTION,
    label: 'Configure Source',
    disabledMessage: 'You need Developer role to configure sources',
  },
  'upload-log': {
    permission: PERMISSIONS.RUN_INGESTION,
    label: 'Upload Log',
    disabledMessage: 'You need Developer role to upload logs',
  },
  'cancel-ingestion': {
    permission: PERMISSIONS.RUN_INGESTION,
    label: 'Cancel Ingestion',
    disabledMessage: 'You need Developer role to cancel ingestion',
  },
  'toggle-auto-ingest': {
    permission: PERMISSIONS.RUN_INGESTION,
    label: 'Toggle Auto-Ingest',
    disabledMessage: 'You need Developer role to change auto-ingest settings',
  },

  // ==================== PLAYBOOK ACTIONS ====================
  'execute-playbook': {
    permission: PERMISSIONS.EXECUTE_PLAYBOOKS,
    label: 'Execute Playbook',
    disabledMessage: 'You need Developer role to execute playbooks',
  },
  'create-playbook': {
    permission: PERMISSIONS.EXECUTE_PLAYBOOKS,
    label: 'Create Playbook',
    disabledMessage: 'You need Developer role to create playbooks',
  },
  'edit-playbook': {
    permission: PERMISSIONS.EXECUTE_PLAYBOOKS,
    label: 'Edit Playbook',
    disabledMessage: 'You need Developer role to edit playbooks',
  },
  'delete-playbook': {
    permission: PERMISSIONS.EXECUTE_PLAYBOOKS,
    label: 'Delete Playbook',
    disabledMessage: 'You need Developer role to delete playbooks',
  },

  // ==================== INDEX ACTIONS ====================
  'apply-index': {
    permission: PERMISSIONS.USE_INDEX_ADVISOR,
    label: 'Apply Index',
    disabledMessage: 'You need Developer role to apply index recommendations',
  },
  'create-index': {
    permission: PERMISSIONS.USE_INDEX_ADVISOR,
    label: 'Create Index',
    disabledMessage: 'You need Developer role to create indexes',
  },

  // ==================== ALERT ACTIONS ====================
  'acknowledge-alert': {
    permission: PERMISSIONS.MANAGE_ALERTS,
    label: 'Acknowledge Alert',
    disabledMessage: 'You need Developer role to acknowledge alerts',
  },
  'acknowledge-regression': {
    permission: PERMISSIONS.MANAGE_ALERTS,
    label: 'Acknowledge Regression',
    disabledMessage: 'You need Developer role to acknowledge regressions',
  },
  'resolve-regression': {
    permission: PERMISSIONS.MANAGE_ALERTS,
    label: 'Resolve Regression',
    disabledMessage: 'You need Developer role to resolve regressions',
  },
  'dismiss-alert': {
    permission: PERMISSIONS.MANAGE_ALERTS,
    label: 'Dismiss Alert',
    disabledMessage: 'You need Developer role to dismiss alerts',
  },

  // ==================== EXPORT ACTIONS ====================
  'export-results': {
    permission: PERMISSIONS.EXPORT_DATA,
    label: 'Export Results',
    disabledMessage: 'You need Developer role to export data',
  },
  'download-report': {
    permission: PERMISSIONS.EXPORT_DATA,
    label: 'Download Report',
    disabledMessage: 'You need Developer role to download reports',
  },

  // ==================== GROWTH MONITORING ACTIONS ====================
  'configure-growth-alerts': {
    permission: PERMISSIONS.RUN_ANALYSIS,
    label: 'Configure Alerts',
    disabledMessage: 'You need Developer role to configure alerts',
  },
  'run-growth-analysis': {
    permission: PERMISSIONS.RUN_ANALYSIS,
    label: 'Run Growth Analysis',
    disabledMessage: 'You need Developer role to run analysis',
  },

  // ==================== TRAINING ACTIONS ====================
  'train-connection': {
    permission: PERMISSIONS.RUN_ANALYSIS,
    label: 'Train Connection',
    disabledMessage: 'You need Developer role to train connections',
  },
  'train-brain': {
    permission: PERMISSIONS.RUN_ANALYSIS,
    label: 'Train Brain',
    disabledMessage: 'You need Developer role to train the brain',
  },
  'add-training-example': {
    permission: PERMISSIONS.RUN_ANALYSIS,
    label: 'Add Training Example',
    disabledMessage: 'You need Developer role to add training examples',
  },

  // ==================== CONNECTION ACTIONS (ADMIN) ====================
  'create-connection': {
    permission: PERMISSIONS.MANAGE_CONNECTIONS,
    label: 'Create Connection',
    disabledMessage: 'You need Admin role to create connections',
    hideWhenDisabled: true,
  },
  'edit-connection': {
    permission: PERMISSIONS.MANAGE_CONNECTIONS,
    label: 'Edit Connection',
    disabledMessage: 'You need Admin role to edit connections',
  },
  'delete-connection': {
    permission: PERMISSIONS.MANAGE_CONNECTIONS,
    label: 'Delete Connection',
    disabledMessage: 'You need Admin role to delete connections',
  },

  // ==================== USER MANAGEMENT ACTIONS (ADMIN) ====================
  'manage-users': {
    permission: PERMISSIONS.MANAGE_USERS,
    label: 'Manage Users',
    disabledMessage: 'You need Admin role to manage users',
    hideWhenDisabled: true,
  },
  'change-user-role': {
    permission: PERMISSIONS.MANAGE_USERS,
    label: 'Change Role',
    disabledMessage: 'You need Admin role to change user roles',
  },
  'delete-user': {
    permission: PERMISSIONS.MANAGE_USERS,
    label: 'Delete User',
    disabledMessage: 'You need Admin role to delete users',
  },
  'create-user': {
    permission: PERMISSIONS.MANAGE_USERS,
    label: 'Create User',
    disabledMessage: 'You need Admin role to create users',
  },

  // ==================== INVITE CODE ACTIONS (ADMIN) ====================
  'manage-invite-codes': {
    permission: PERMISSIONS.MANAGE_INVITE_CODES,
    label: 'Manage Invite Codes',
    disabledMessage: 'You need Admin role to manage invite codes',
    hideWhenDisabled: true,
  },
  'create-invite-code': {
    permission: PERMISSIONS.MANAGE_INVITE_CODES,
    label: 'Create Invite Code',
    disabledMessage: 'You need Admin role to create invite codes',
  },

  // ==================== SETTINGS ACTIONS (ADMIN) ====================
  'manage-settings': {
    permission: PERMISSIONS.MANAGE_SETTINGS,
    label: 'Manage Settings',
    disabledMessage: 'You need Admin role to manage settings',
    hideWhenDisabled: true,
  },

  // ==================== PERMISSION ACTIONS (ADMIN) ====================
  'manage-permissions': {
    permission: PERMISSIONS.MANAGE_PERMISSIONS,
    label: 'Manage Permissions',
    disabledMessage: 'You need Admin role to manage permissions',
    hideWhenDisabled: true,
  },

  // ==================== HISTORY/DELETE ACTIONS ====================
  'delete-history': {
    permission: PERMISSIONS.RUN_ANALYSIS,
    label: 'Delete History',
    disabledMessage: 'You need Developer role to delete history',
  },
  'clear-analysis': {
    permission: PERMISSIONS.RUN_ANALYSIS,
    label: 'Clear Analysis',
    disabledMessage: 'You need Developer role to clear analysis',
  },
}

/**
 * Get the permission required for an action.
 */
export function getActionPermission(action) {
  const actionConfig = ACTIONS[action]
  if (!actionConfig) {
    console.warn(`Unknown action: ${action}. Defaulting to deny.`)
    return null
  }
  return actionConfig.permission
}

/**
 * Get the action config (permission, label, etc.)
 */
export function getActionConfig(action) {
  return ACTIONS[action] || null
}

/**
 * Check if an action should be hidden when disabled.
 */
export function shouldHideWhenDisabled(action) {
  const config = ACTIONS[action]
  return config?.hideWhenDisabled === true
}

/**
 * Get the disabled message for an action.
 */
export function getDisabledMessage(action) {
  const config = ACTIONS[action]
  return config?.disabledMessage || 'Action not permitted'
}
