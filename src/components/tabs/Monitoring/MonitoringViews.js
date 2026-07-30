import React, { useMemo } from 'react';
import { 
  ArrowUpRight, 
  ArrowDownRight, 
  Clock, 
  Database, 
  Table, 
  FileText, 
  AlertTriangle,
  CheckCircle,
  Zap
} from 'lucide-react';
import { useQuery } from '@tanstack/react-query';
import { schemaAPI, growthMonitoringAPI, performanceActionsAPI } from '@/lib/api/client';
import { PerformanceActionCard } from '../Performance/components';
import styles from './AnalyticsTab.module.css';

// --- Helper Components ---

function Badge({ type, children }) {
  const bg = type === 'TABLE' ? '#000' : '#000';
  const color = '#fff';
  return (
    <span style={{ 
      backgroundColor: bg, 
      color, 
      padding: '4px 12px', 
      borderRadius: '999px', 
      fontSize: '10px', 
      fontWeight: '800', 
      letterSpacing: '1px',
      textTransform: 'uppercase'
    }}>
      ENTRY: {children}
    </span>
  );
}

function TimeAgo({ date }) {
  if (!date) return null;
  const diff = Date.now() - new Date(date).getTime();
  const hours = Math.floor(diff / (1000 * 60 * 60));
  const days = Math.floor(hours / 24);
  
  let text = '';
  if (days > 0) text = `${days}D AGO`;
  else if (hours > 0) text = `${hours}H AGO`;
  else text = 'JUST NOW';

  return (
    <span style={{ 
      fontSize: '10px', 
      fontWeight: '700', 
      color: '#9ca3af', 
      letterSpacing: '1px' 
    }}>
      {text}
    </span>
  );
}

// --- Views ---

export function NewChangesView({ connectionId }) {
  const { data: changes, isLoading } = useQuery({
    queryKey: ['schema-changes', connectionId],
    queryFn: () => schemaAPI.getSchemaChanges(connectionId),
    enabled: !!connectionId
  });

  if (isLoading) return <div className={styles.loadingState}>Loading changes...</div>;

  if (!changes || changes.length === 0) {
    return <div className={styles.emptyState}>No schema changes detected in the last 24 hours.</div>;
  }

  return (
    <div className={styles.changesGrid}>
      {changes.map((change, idx) => (
        <div key={change.id || idx} className={styles.changeCard}>
          <div className={styles.changeHeader}>
            <Badge type={change.objectType}>{change.objectType || 'UNKNOWN'}</Badge>
            <TimeAgo date={change.detectedAt} />
          </div>
          
          <h3 className={styles.changeTitle}>{change.objectName}</h3>
          <p className={styles.changeDesc}>{change.details || 'No description available.'}</p>
          
          {change.objectType === 'TABLE' && change.metadata?.columns && (
            <div className={styles.columnGrid}>
              {change.metadata.columns.map((col, i) => (
                <div key={i} className={styles.columnPill}>
                  <span className={styles.colName}>{col.name}</span>
                  <span className={styles.colType}>{col.type}</span>
                </div>
              ))}
            </div>
          )}

          {change.objectType === 'QUERY' && change.metadata && (
            <div className={styles.queryStats}>
              <div className={styles.efficiencyBadge}>
                <span className={styles.effLabel}>EFFICIENCY</span>
                <span className={styles.effValue}>{change.metadata.efficiency}</span>
              </div>
              {change.metadata.nodes && (
                <div className={styles.nodesList}>
                  <span className={styles.nodesLabel}>NODES</span>
                  <div className={styles.nodesContainer}>
                    {change.metadata.nodes.map((node, i) => (
                      <span key={i} className={styles.nodePill}>{node}</span>
                    ))}
                  </div>
                </div>
              )}
            </div>
          )}
        </div>
      ))}
    </div>
  );
}

export function TablesGrowthView({ connectionId }) {
  const { data: historyData, isLoading } = useQuery({
    queryKey: ['growth-history', connectionId],
    queryFn: () => growthMonitoringAPI.getGrowthHistory(connectionId, null, 30),
    enabled: !!connectionId
  });

  const topGrowthTables = useMemo(() => {
    if (!historyData?.history) return [];
    
    // Group by table
    const tables = {};
    historyData.history.forEach(entry => {
      if (!tables[entry.tableName]) tables[entry.tableName] = [];
      tables[entry.tableName].push(entry);
    });

    // Calculate growth
    const growth = Object.keys(tables).map(tableName => {
      const entries = tables[tableName].sort((a, b) => new Date(a.snapshotTimestamp) - new Date(b.snapshotTimestamp));
      if (entries.length < 2) return null;
      
      const first = entries[0];
      const last = entries[entries.length - 1];
      const sizeDiff = (last.sizeBytes || 0) - (first.sizeBytes || 0);
      const pct = first.sizeBytes > 0 ? (sizeDiff / first.sizeBytes) * 100 : 0;
      
      return {
        tableName,
        growthBytes: sizeDiff,
        growthPct: pct,
        currentSize: last.sizeBytes,
        lastUpdated: last.snapshotTimestamp
      };
    }).filter(Boolean);

    // Sort by growth bytes desc (absolute growth usually more important than % for small tables)
    return growth.sort((a, b) => b.growthBytes - a.growthBytes).slice(0, 5);
  }, [historyData]);

  if (isLoading) return <div className={styles.loadingState}>Loading growth data...</div>;

  if (topGrowthTables.length === 0) {
    return (
      <div className={styles.emptyState}>
        <Database size={48} className={styles.emptyIcon} />
        <p>No significant growth detected yet.</p>
      </div>
    );
  }

  return (
    <div className={styles.growthList}>
      {topGrowthTables.map((table) => (
        <div key={table.tableName} className={styles.growthCard}>
          <div className={styles.growthHeader}>
            <div className={styles.tableInfo}>
              <Table size={18} className={styles.tableIcon} />
              <span className={styles.tableName}>{table.tableName}</span>
            </div>
            <div className={styles.growthBadge} style={{ backgroundColor: table.growthBytes > 0 ? '#fee2e2' : '#f3f4f6', color: table.growthBytes > 0 ? '#ef4444' : '#6b7280' }}>
              {table.growthBytes > 0 ? '+' : ''}{formatBytes(table.growthBytes)}
            </div>
          </div>
          <div className={styles.growthStats}>
            <div className={styles.statItem}>
              <span className={styles.statLabel}>Current Size</span>
              <span className={styles.statValue}>{formatBytes(table.currentSize)}</span>
            </div>
            <div className={styles.statItem}>
              <span className={styles.statLabel}>Growth %</span>
              <span className={styles.statValue} style={{ color: table.growthPct > 10 ? '#ef4444' : 'inherit' }}>
                {table.growthPct > 0 ? '+' : ''}{table.growthPct.toFixed(1)}%
              </span>
            </div>
            <div className={styles.statItem}>
              <span className={styles.statLabel}>Last Updated</span>
              <span className={styles.statValue}>{new Date(table.lastUpdated).toLocaleDateString()}</span>
            </div>
          </div>
        </div>
      ))}
    </div>
  );
}

export function RecommendationsView({ connectionId }) {
  const { data: actions, isLoading } = useQuery({
    queryKey: ['performance-actions', connectionId],
    queryFn: () => performanceActionsAPI.getTopActions(connectionId, 10),
    enabled: !!connectionId
  });

  if (isLoading) return <div className={styles.loadingState}>Loading recommendations...</div>;

  if (!actions || actions.length === 0) {
    return (
      <div className={styles.emptyState}>
        <CheckCircle size={48} className={styles.emptyIcon} />
        <p>No pending recommendations. Great job!</p>
      </div>
    );
  }

  return (
    <div className={styles.recommendationsList}>
      {actions.map((action, idx) => (
        <div key={action.id} className={styles.recommendationWrapper}>
          <PerformanceActionCard 
            action={action} 
            index={idx + 1} 
            variant="full"
          />
        </div>
      ))}
    </div>
  );
}

// Helper
function formatBytes(bytes) {
  if (bytes === 0) return '0 B';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
  const i = Math.floor(Math.log(Math.abs(bytes)) / Math.log(k));
  return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
}
