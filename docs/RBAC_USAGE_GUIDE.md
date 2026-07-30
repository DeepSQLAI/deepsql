# RBAC Usage Guide

This document explains how to use the new scalable RBAC (Role-Based Access Control) system.

## Quick Start

### For New Features

1. **Add permission to backend** (if new permission needed):
   ```java
   // backend/src/main/java/com/dbaagent/model/Permission.java
   NEW_FEATURE("Description of new feature", Role.EDITOR),  // Default to EDITOR
   ```

2. **Add action mapping**:
   ```javascript
   // src/lib/actions.js
   'new-feature-action': {
     permission: PERMISSIONS.NEW_FEATURE, // or existing permission
     label: 'New Feature',
     disabledMessage: 'You need Editor role for this feature',
   },
   ```

3. **Wrap your component**:
   ```jsx
   import { ActionGuard } from '@/components/ActionGuard'

   <ActionGuard action="new-feature-action">
     <button onClick={doThing}>New Feature</button>
   </ActionGuard>
   ```

Done! No other changes needed.

## Patterns

### Pattern 1: ActionGuard (Preferred)

```jsx
import { ActionGuard } from '@/components/ActionGuard'

// Disables button when not permitted
<ActionGuard action="execute-query">
  <button onClick={runQuery}>
    <Play size={14} />
    Run Query
  </button>
</ActionGuard>

// Hides content when not permitted
<ActionGuard action="manage-users" mode="hide">
  <AdminPanel />
</ActionGuard>

// Custom fallback
<ActionGuard action="use-chat" fallback={<UpgradePrompt />}>
  <ChatInput />
</ActionGuard>
```

### Pattern 2: ActionButton (For Simple Buttons)

```jsx
import { ActionButton } from '@/components/ActionGuard'

<ActionButton action="execute-query" onClick={runQuery} className={styles.button}>
  <Play size={14} />
  Run Query
</ActionButton>
```

### Pattern 3: Programmatic Check with canAction

```jsx
import { useAuth } from '@/hooks/useAuth'

function MyComponent() {
  const { canAction } = useAuth()

  // Only fetch data if user can see it
  useEffect(() => {
    if (canAction('view-slow-queries')) {
      fetchSlowQueries()
    }
  }, [canAction])

  // Conditional rendering
  return (
    <div>
      {canAction('run-analysis') && <AnalysisButton />}
    </div>
  )
}
```

### Pattern 4: PermissionGate (For Sections)

```jsx
import { PermissionGate, RoleGate } from '@/components/ActionGuard'

// Show only if has specific permission
<PermissionGate permission="MANAGE_USERS">
  <UserManagementSection />
</PermissionGate>

// Show only if at least ADMIN role
<RoleGate minRole="ADMIN">
  <AdminSettings />
</RoleGate>
```

## Migrating Existing Components

### Before (Old Pattern)
```jsx
import { useAuth, PERMISSIONS } from '@/hooks/useAuth'

function MyComponent() {
  const { hasPermission } = useAuth()
  const canRunAnalysis = hasPermission(PERMISSIONS.RUN_ANALYSIS)

  return (
    <button
      onClick={handleAnalyze}
      disabled={!canRunAnalysis}
      title={!canRunAnalysis ? 'You need Editor role' : ''}
    >
      {!canRunAnalysis ? <Lock size={14} /> : <Play size={14} />}
      {!canRunAnalysis ? 'View Only' : 'Analyze'}
    </button>
  )
}
```

### After (New Pattern)
```jsx
import { ActionGuard } from '@/components/ActionGuard'

function MyComponent() {
  return (
    <ActionGuard action="analyze-key-columns">
      <button onClick={handleAnalyze}>
        <Play size={14} />
        Analyze
      </button>
    </ActionGuard>
  )
}
```

## Available Actions

See `src/lib/actions.js` for the complete list. Common actions:

| Action | Permission | Description |
|--------|------------|-------------|
| `execute-query` | EXECUTE_QUERIES | Run SQL queries |
| `use-chat` | USE_CHAT | Use AI chat |
| `analyze-schema` | RUN_ANALYSIS | Run schema analysis |
| `analyze-key-columns` | RUN_ANALYSIS | Run key column analysis |
| `run-ingestion` | RUN_INGESTION | Run slow query ingestion |
| `configure-source` | RUN_INGESTION | Configure log sources |
| `execute-playbook` | EXECUTE_PLAYBOOKS | Execute playbooks |
| `acknowledge-alert` | MANAGE_ALERTS | Acknowledge alerts |
| `create-connection` | MANAGE_CONNECTIONS | Create DB connections |
| `manage-users` | MANAGE_USERS | User management |

## Adding a New Role

1. **Backend only** - Add to Role.java enum:
   ```java
   ANALYST("Data analyst with read and analysis capabilities"),
   ```
   The role automatically inherits permissions based on its position in the hierarchy.

2. Optionally create overrides via admin API:
   ```javascript
   // Grant USE_CHAT to ANALYST (if not inherited)
   await permissionsAPI.setOverride({
     role: 'ANALYST',
     permission: 'USE_CHAT',
     granted: true,
     reason: 'Analysts need chat for data questions'
   })
   ```

No frontend changes needed!

## Admin API

```javascript
import { permissionsAPI } from '@/lib/api/client'

// Get current user's permissions
const { permissions, role } = await permissionsAPI.getMyPermissions()

// Get full permission registry (admin)
const { permissions } = await permissionsAPI.getRegistry()

// Get all roles with permissions (admin)
const { roles } = await permissionsAPI.getRoles()

// Set an override (admin)
await permissionsAPI.setOverride({
  role: 'VIEWER',
  permission: 'USE_CHAT',
  granted: true,
  reason: 'Allow viewers to ask questions'
})

// Remove override (admin)
await permissionsAPI.removeOverride('VIEWER', 'USE_CHAT')
```

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                         Backend                              │
├─────────────────────────────────────────────────────────────┤
│  Permission.java (enum)                                      │
│  ├── Each permission has defaultMinRole                      │
│  └── Adding permission = 1 line                              │
│                                                              │
│  Role.java (enum with hierarchy)                             │
│  ├── VIEWER(0) → EDITOR(1) → ADMIN(2)                       │
│  └── Each role inherits from lower roles                     │
│                                                              │
│  RolePermissionOverride (entity)                             │
│  └── Sparse table for exceptions only                        │
│                                                              │
│  PermissionService                                           │
│  └── Computes effective = defaults + overrides               │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│                         Frontend                             │
├─────────────────────────────────────────────────────────────┤
│  permissions.js                                              │
│  └── PERMISSIONS enum (mirrors backend)                      │
│                                                              │
│  actions.js (SINGLE SOURCE OF TRUTH)                         │
│  └── Maps action names → permissions                         │
│                                                              │
│  useAuth hook                                                │
│  ├── canAction('action-name') - preferred                    │
│  ├── hasPermission('PERMISSION') - low-level                 │
│  └── getActionInfo('action-name') - for ActionGuard          │
│                                                              │
│  ActionGuard / ActionButton                                  │
│  └── Wraps UI elements with permission checks                │
└─────────────────────────────────────────────────────────────┘
```

## Benefits

1. **Adding new feature**: Add action mapping + wrap with ActionGuard
2. **Adding new role**: Add to enum, inherits permissions automatically
3. **Renaming permission**: Change 1 mapping file, not 50 components
4. **Admin override**: Change via API, no code deploy needed
