# API Client Usage Guide

## Overview

All API calls in the DBA Agent frontend now use the centralized API client located at `src/lib/api/client.js`. This provides:

- ✅ **Centralized configuration** - Single place to update API URLs
- ✅ **Consistent error handling** - Unified error messages
- ✅ **Environment support** - Automatic dev/prod URL switching
- ✅ **Request/Response interceptors** - Easy to add auth, logging, etc.
- ✅ **Type safety** - Clear API method signatures

## How to Use

### Import the API you need:

```javascript
import { connectionAPI, playbookAPI, advisorAPI } from '@/lib/api/client'
```

### Call API methods:

```javascript
// ❌ OLD WAY (Don't do this)
const response = await fetch('http://localhost:8080/api/playbooks')
const data = await response.json()

// ✅ NEW WAY (Use this)
const data = await playbookAPI.getAllPlaybooks()
```

## Available APIs

### 1. **Connection API** (`connectionAPI`)
```javascript
await connectionAPI.testConnection(connectionData)
await connectionAPI.saveConnection(connectionData)
await connectionAPI.getAllConnections()
await connectionAPI.deleteConnection(connectionId)
await connectionAPI.updateConnection(connectionId, data)
```

### 2. **Schema API** (`schemaAPI`)
```javascript
await schemaAPI.scanSchema(connectionId)
await schemaAPI.getSchema(connectionId)
await schemaAPI.getVisualization(connectionId)
```

### 3. **Stats API** (`statsAPI`)
```javascript
await statsAPI.getStats(connectionId)
```

### 4. **Query API** (`queryAPI`)
```javascript
await queryAPI.getDatabaseObjects(connectionId)
await queryAPI.executeQuery(connectionId, query, limit)
await queryAPI.getTableIndexes(connectionId, tableName)
```

### 5. **Chat API** (`chatAPI`)
```javascript
await chatAPI.sendMessage(connectionId, message, threadId)
```

### 6. **Advisor API** (`advisorAPI`)
```javascript
await advisorAPI.analyzeDatabase(connectionId)
```

### 7. **Slow Queries API** (`slowQueriesAPI`)
```javascript
await slowQueriesAPI.getHistory(connectionId)
await slowQueriesAPI.saveExplainPlan(data)
await slowQueriesAPI.deleteHistory(id)
```

### 8. **Index API** (`indexAPI`)
```javascript
await indexAPI.getRecommendations(connectionId)
await indexAPI.generateRecommendations(connectionId)
await indexAPI.applyRecommendation(id)
await indexAPI.dismissRecommendation(id)
await indexAPI.deleteRecommendation(id)
```

### 9. **Performance API** (`performanceAPI`)
```javascript
await performanceAPI.getPerformanceMetrics(connectionId)
await performanceAPI.getDashboardData(connectionId, days)
```

### 10. **Lock API** (`lockAPI`)
```javascript
await lockAPI.getActiveLocks(connectionId)
await lockAPI.getStatistics(connectionId)
await lockAPI.detectContentions(connectionId)
await lockAPI.killSession(connectionId, pid)
```

### 11. **Active Queries API** (`activeQueriesAPI`)
```javascript
await activeQueriesAPI.getActiveQueries(connectionId)
await activeQueriesAPI.getLatestQueries(connectionId)
await activeQueriesAPI.getStatistics(connectionId)
await activeQueriesAPI.getFilterOptions(connectionId)
await activeQueriesAPI.captureQueries(connectionId)
await activeQueriesAPI.killQuery(connectionId, pid)
```

### 12. **Configuration API** (`configAPI`)
```javascript
await configAPI.getConfiguration(connectionId)
await configAPI.analyzeConfiguration(connectionId)
await configAPI.updateConfiguration(connectionId, settings)
```

### 13. **Explain Plan API** (`explainAPI`)
```javascript
await explainAPI.getHistory(connectionId)
await explainAPI.analyzeQuery(connectionId, query, useAnalyze)
await explainAPI.saveHistory(data)
await explainAPI.deleteHistory(id)
```

### 14. **Playbook API** (`playbookAPI`)
```javascript
// Get playbooks
await playbookAPI.getAllPlaybooks(params)
await playbookAPI.getPlaybook(playbookId)

// Manage playbooks
await playbookAPI.createPlaybook(data)
await playbookAPI.updatePlaybook(playbookId, data)
await playbookAPI.deletePlaybook(playbookId)
await playbookAPI.togglePlaybook(playbookId)

// Execute and monitor
await playbookAPI.executePlaybook(playbookId, connectionId)
await playbookAPI.getRunHistory(connectionId, limit)
await playbookAPI.cancelRun(runId)

// Alerts
await playbookAPI.getAlerts(connectionId, unacknowledgedOnly)
await playbookAPI.acknowledgeAlert(alertId, acknowledgedBy)
```

## Error Handling

The API client automatically handles errors:

```javascript
try {
  const data = await playbookAPI.getAllPlaybooks()
  // Use data
} catch (error) {
  // Error is already formatted with a clear message
  console.error('Error:', error.message)
  // Show to user: error.message
}
```

## Environment Configuration

The API client automatically uses the correct URL:

- **Development**: `http://localhost:8080`
- **Production**: your deployment's API base URL (set `VITE_API_URL`)
- **Custom**: Set `NEXT_PUBLIC_API_URL` environment variable

## Updated Components

All components have been successfully migrated to use the centralized API client:

✅ **PlaybooksTab.js** - Uses `playbookAPI`
✅ **ConfigurationTunerTab.js** - Uses `configAPI`
✅ **LockContentionTab.js** - Uses `lockAPI`
✅ **PerformanceDashboard.js** - Uses `performanceAPI`
✅ **IndexRecommendationsTab.js** - Uses `indexAPI`
✅ **DatabaseAdvisorTab.js** - Uses `advisorAPI`
✅ **SlowQueryAnalysisTab.js** - Uses `slowQueriesAPI`
✅ **ActiveQueryTab.js** - Uses `activeQueriesAPI`
✅ **ExplainPlanTab.js** - Uses `explainAPI`

### To Update Other Components

1. Import the appropriate API:
   ```javascript
   import { advisorAPI } from '@/lib/api/client'
   ```

2. Replace fetch calls:
   ```javascript
   // Before
   const res = await fetch(`http://localhost:8080/api/advisor/analyze/${connectionId}`)
   const data = await res.json()

   // After
   const data = await advisorAPI.analyzeDatabase(connectionId)
   ```

3. Update error handling (errors are now thrown, not returned):
   ```javascript
   // Before
   if (data.success) { ... } else { ... }

   // After
   try {
     const data = await advisorAPI.analyzeDatabase(connectionId)
     // data is already the response
   } catch (error) {
     // Handle error
   }
   ```

## Benefits

### Before (Direct fetch):
```javascript
const response = await fetch('http://localhost:8080/api/playbooks', {
  method: 'GET',
  headers: { 'Content-Type': 'application/json' }
})
const data = await response.json()
if (!data.success) throw new Error(data.message)
```

### After (Centralized API):
```javascript
const data = await playbookAPI.getAllPlaybooks()
```

**Much cleaner!** 🎉

## Adding New API Methods

To add a new API endpoint:

1. Add the method to the appropriate API object in `client.js`:
   ```javascript
   export const playbookAPI = {
     // ... existing methods

     newMethod: async (param) => {
       const response = await apiClient.get(`/api/playbooks/${param}`)
       return response.data
     }
   }
   ```

2. Use it in your component:
   ```javascript
   import { playbookAPI } from '@/lib/api/client'

   const data = await playbookAPI.newMethod(param)
   ```

## Migration Checklist

To migrate a component to use the centralized API:

- [ ] Import the appropriate API from `@/lib/api/client`
- [ ] Replace `fetch()` calls with API methods
- [ ] Remove manual URL construction
- [ ] Remove manual header setting
- [ ] Update error handling
- [ ] Test the component

## Notes

- All API methods return the `response.data` directly (no need to call `.json()`)
- Errors are thrown automatically (use try/catch)
- Base URL is configured automatically based on environment
- Timeout is set to 30 seconds by default
- All requests include `Content-Type: application/json` header
