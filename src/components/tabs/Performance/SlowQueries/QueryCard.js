'use client'

import { AlertTriangle, ChevronDown, ChevronUp, Copy, Check, Loader2, Sparkles, Database, Zap } from 'lucide-react'
import ReactMarkdown from 'react-markdown'
import styles from '../SlowQueryAnalysisTab.module.css'

/**
 * Individual slow query card with expand/collapse, metrics, and analysis tools
 */
export default function QueryCard({
    query,
    idx,
    expanded,
    onToggle,
    copiedSql,
    onCopyToClipboard,
    optimizingQuery,
    optimizationResults,
    onOptimize,
    formatDuration,
    formatDate,
    getSeverityColor,
    getSeverityIcon,
    // Deprecated props (kept for backwards compatibility but no longer used)
    // eslint-disable-next-line no-unused-vars
    loadingExplain,
    // eslint-disable-next-line no-unused-vars
    explainResults,
    // eslint-disable-next-line no-unused-vars
    onRunExplain,
}) {
    const queryKey = query.queryId || idx
    const optimizationKey = query.queryId || query.queryText?.substring(0, 50)
    const isOptimizing = optimizingQuery === optimizationKey
    const optimization = optimizationResults[optimizationKey]

    return (
        <div className={styles.queryCard}>
            <div
                className={styles.queryHeader}
                onClick={() => onToggle(queryKey)}
            >
                <div className={styles.queryHeaderLeft}>
                    <div className={styles.severityBadge} style={{ backgroundColor: getSeverityColor(query.severity) }}>
                        {getSeverityIcon(query.severity)}
                        <span>{query.severity}</span>
                    </div>
                    {query.source && (
                        <div className={styles.sourceBadge}>
                            {query.source}
                        </div>
                    )}
                    <div className={styles.queryPreview}>
                        {query.queryText?.substring(0, 100)}...
                    </div>
                </div>

                <div className={styles.queryHeaderRight}>
                    <div className={styles.queryMetrics}>
                        <div className={styles.metric}>
                            <span className={styles.metricLabel}>Avg:</span>
                            <span className={styles.metricValue}>{formatDuration(query.avgExecutionTimeMs)}</span>
                        </div>
                        <div className={styles.metric}>
                            <span className={styles.metricLabel}>Calls:</span>
                            <span className={styles.metricValue}>{query.callCount?.toLocaleString()}</span>
                        </div>
                        <div className={styles.metric}>
                            <span className={styles.metricLabel}>Total:</span>
                            <span className={styles.metricValue}>{formatDuration(query.totalExecutionTimeMs)}</span>
                        </div>
                    </div>
                    <button className={styles.expandButton}>
                        {expanded ? <ChevronUp size={20} /> : <ChevronDown size={20} />}
                    </button>
                </div>
            </div>

            {expanded && (
                <div className={styles.queryDetails}>
                    {/* Full Query */}
                    <div className={styles.detailSection}>
                        <h4>Query</h4>
                        <div className={styles.sqlBlock}>
                            <code>{query.queryText}</code>
                            <button
                                className={styles.copyButton}
                                onClick={() => onCopyToClipboard(query.queryText, `query-${idx}`)}
                                title="Copy SQL"
                            >
                                {copiedSql === `query-${idx}` ? <Check size={16} /> : <Copy size={16} />}
                            </button>
                        </div>
                    </div>

                    {/* Metrics Grid */}
                    <div className={styles.detailSection}>
                        <h4>Performance Metrics</h4>
                        <div className={styles.metricsGrid}>
                            {query.source && (
                                <div className={styles.metricBox}>
                                    <div className={styles.metricBoxLabel}>Data Source</div>
                                    <div className={styles.metricBoxValue} style={{ fontSize: '0.9rem' }}>
                                        {query.source}
                                    </div>
                                </div>
                            )}
                            <div className={styles.metricBox}>
                                <div className={styles.metricBoxLabel}>Avg Execution</div>
                                <div className={styles.metricBoxValue}>{formatDuration(query.avgExecutionTimeMs)}</div>
                            </div>
                            <div className={styles.metricBox}>
                                <div className={styles.metricBoxLabel}>Max Execution</div>
                                <div className={styles.metricBoxValue}>{formatDuration(query.maxExecutionTimeMs)}</div>
                            </div>
                            <div className={styles.metricBox}>
                                <div className={styles.metricBoxLabel}>Min Execution</div>
                                <div className={styles.metricBoxValue}>{formatDuration(query.minExecutionTimeMs)}</div>
                            </div>
                            <div className={styles.metricBox}>
                                <div className={styles.metricBoxLabel}>Total Time</div>
                                <div className={styles.metricBoxValue}>{formatDuration(query.totalExecutionTimeMs)}</div>
                            </div>
                            <div className={styles.metricBox}>
                                <div className={styles.metricBoxLabel}>Call Count</div>
                                <div className={styles.metricBoxValue}>{query.callCount?.toLocaleString()}</div>
                            </div>
                            <div className={styles.metricBox}>
                                <div className={styles.metricBoxLabel}>Performance Impact</div>
                                <div className={styles.metricBoxValue}>{query.performanceImpact?.toFixed(2)}</div>
                            </div>
                        </div>
                    </div>

                    {/* Row Metrics */}
                    <div className={styles.detailSection}>
                        <h4>Row Statistics</h4>
                        <div className={styles.metricsGrid}>
                            <div className={styles.metricBox}>
                                <div className={styles.metricBoxLabel}>Rows Examined</div>
                                <div className={styles.metricBoxValue}>{query.rowsExamined?.toLocaleString()}</div>
                            </div>
                            <div className={styles.metricBox}>
                                <div className={styles.metricBoxLabel}>Rows Sent</div>
                                <div className={styles.metricBoxValue}>{query.rowsSent?.toLocaleString()}</div>
                            </div>
                            <div className={styles.metricBox}>
                                <div className={styles.metricBoxLabel}>Avg Examined</div>
                                <div className={styles.metricBoxValue}>{query.avgRowsExamined?.toLocaleString()}</div>
                            </div>
                            <div className={styles.metricBox}>
                                <div className={styles.metricBoxLabel}>Avg Sent</div>
                                <div className={styles.metricBoxValue}>{query.avgRowsSent?.toLocaleString()}</div>
                            </div>
                            <div className={styles.metricBox}>
                                <div className={styles.metricBoxLabel}>Efficiency Ratio</div>
                                <div className={styles.metricBoxValue}>
                                    {query.efficiencyRatio !== null && query.efficiencyRatio !== undefined
                                        ? `${(query.efficiencyRatio * 100).toFixed(4)}%`
                                        : 'N/A'}
                                </div>
                            </div>
                        </div>
                    </div>

                    {/* Timing */}
                    <div className={styles.detailSection}>
                        <h4>Timeline</h4>
                        <div className={styles.timeline}>
                            <div className={styles.timelineItem}>
                                <span className={styles.timelineLabel}>First Seen:</span>
                                <span className={styles.timelineValue}>{formatDate(query.firstSeen)}</span>
                            </div>
                            <div className={styles.timelineItem}>
                                <span className={styles.timelineLabel}>Last Seen:</span>
                                <span className={styles.timelineValue}>{formatDate(query.lastSeen)}</span>
                            </div>
                        </div>
                    </div>

                    {/* Suggestions */}
                    {query.suggestions && query.suggestions.length > 0 && (
                        <div className={styles.detailSection}>
                            <h4>Optimization Suggestions</h4>
                            <div className={styles.suggestionsList}>
                                {query.suggestions.map((suggestion, sidx) => (
                                    <div key={sidx} className={styles.suggestionItem}>
                                        <AlertTriangle size={16} />
                                        <span>{suggestion}</span>
                                    </div>
                                ))}
                            </div>
                        </div>
                    )}

                    {/* Suggested Indexes */}
                    {query.suggestedIndexes && query.suggestedIndexes.length > 0 && (
                        <div className={styles.detailSection}>
                            <h4>Suggested Indexes</h4>
                            {query.suggestedIndexes.map((index, iidx) => (
                                <div key={iidx} className={styles.indexSuggestion}>
                                    <div className={styles.indexInfo}>
                                        <strong>{index.tableName}</strong> - {index.columns?.join(', ')}
                                    </div>
                                    {index.estimatedImprovementPercent && (
                                        <div className={styles.improvementBadge}>
                                            +{index.estimatedImprovementPercent}% faster
                                        </div>
                                    )}
                                </div>
                            ))}
                        </div>
                    )}

                    {/* AI Optimization Action */}
                    <div className={styles.detailSection}>
                        <h4>Analysis</h4>
                        <div className={styles.queryActions}>
                            <button
                                className={styles.actionButton}
                                onClick={(e) => {
                                    e.stopPropagation()
                                    onOptimize(query)
                                }}
                                disabled={isOptimizing}
                            >
                                {isOptimizing ? (
                                    <Loader2 size={16} className={styles.spinner} />
                                ) : (
                                    <Sparkles size={16} />
                                )}
                                AI Optimize
                            </button>
                            <span style={{ fontSize: '11px', color: 'var(--color-light-6)' }}>
                                Runs EXPLAIN + AI analysis for comprehensive optimization
                            </span>
                        </div>
                    </div>

                    {/* AI Loading Indicator */}
                    {isOptimizing && (
                        <div className={styles.aiLoadingPanel}>
                            <Loader2 size={20} className={styles.spinner} />
                            <div className={styles.aiLoadingText}>
                                <strong>AI is analyzing your query...</strong>
                                <br />
                                Generating optimization suggestions and index recommendations
                            </div>
                        </div>
                    )}

                    {/* AI Optimization Results */}
                    {optimization && (
                        <div className={styles.detailSection}>
                            <h4>
                                <Sparkles size={16} style={{ color: 'var(--color-primary)' }} />
                                AI Optimization Suggestions
                            </h4>
                            {optimization.error ? (
                                <div className={styles.errorMessage}>
                                    {optimization.error}
                                </div>
                            ) : (
                                <div className={styles.optimizationResultPanel}>
                                    {optimization.optimizedQuery && (
                                        <div className={styles.optimizedQuerySection}>
                                            <strong>Optimized Query:</strong>
                                            <div className={styles.sqlBlock}>
                                                <code>{optimization.optimizedQuery}</code>
                                                <button
                                                    className={styles.copyButton}
                                                    onClick={() => onCopyToClipboard(optimization.optimizedQuery, `opt-${idx}`)}
                                                    title="Copy optimized SQL"
                                                >
                                                    {copiedSql === `opt-${idx}` ? <Check size={16} /> : <Copy size={16} />}
                                                </button>
                                            </div>
                                        </div>
                                    )}

                                    {optimization.suggestions?.length > 0 && (
                                        <div className={styles.suggestionsList}>
                                            {optimization.suggestions.map((sug, sidx) => (
                                                <div key={sidx} className={styles.optimizationSuggestion}>
                                                    <div className={styles.suggestionHeader}>
                                                        <span className={styles.suggestionCategory}>{sug.category}</span>
                                                        <span className={`${styles.priorityBadge} ${styles['priority' + sug.priority]}`}>
                                                            {sug.priority}
                                                        </span>
                                                    </div>
                                                    <strong>{sug.title}</strong>
                                                    <p>{sug.description}</p>
                                                    {sug.implementationSQL && (
                                                        <div className={styles.sqlBlock}>
                                                            <code>{sug.implementationSQL}</code>
                                                        </div>
                                                    )}
                                                    {sug.estimatedImpact && (
                                                        <span className={styles.impactBadge}>
                                                            ~{sug.estimatedImpact}% improvement
                                                        </span>
                                                    )}
                                                </div>
                                            ))}
                                        </div>
                                    )}

                                    {optimization.indexRecommendations?.length > 0 && (
                                        <div className={styles.indexRecommendations}>
                                            <strong>Index Recommendations:</strong>
                                            <ul>
                                                {optimization.indexRecommendations.map((idx_rec, i) => (
                                                    <li key={i}>{idx_rec}</li>
                                                ))}
                                            </ul>
                                        </div>
                                    )}

                                    {optimization.explanation && (
                                        <div className={styles.explanation}>
                                            <strong>Explanation:</strong>
                                            <div className={styles.markdownContent}>
                                                <ReactMarkdown>
                                                    {optimization.explanation}
                                                </ReactMarkdown>
                                            </div>
                                        </div>
                                    )}

                                    {optimization.estimatedImprovement && (
                                        <div className={styles.estimatedImprovement}>
                                            <Zap size={16} style={{ color: 'var(--color-success)' }} />
                                            Estimated Performance Improvement: {optimization.estimatedImprovement}%
                                        </div>
                                    )}

                                    {/* EXPLAIN Analysis (included in AI Optimize) */}
                                    {optimization.explainAnalysis && (
                                        <div className={styles.explainInOptimization}>
                                            <strong style={{ display: 'flex', alignItems: 'center', gap: '6px', marginBottom: '8px' }}>
                                                <Database size={14} style={{ color: 'var(--color-primary)' }} />
                                                Execution Plan Analysis
                                            </strong>
                                            {optimization.explainAnalysis.summaryStats && (
                                                <div className={styles.explainSummary}>
                                                    <p>{optimization.explainAnalysis.summaryStats}</p>
                                                </div>
                                            )}
                                            {optimization.explainAnalysis.issues?.length > 0 && (
                                                <div className={styles.explainIssues}>
                                                    {optimization.explainAnalysis.issues.map((issue, i) => (
                                                        <div key={i} className={styles.explainIssue} style={{ borderLeftColor: getSeverityColor(issue.severity) }}>
                                                            <span className={styles.issueSeverity} style={{ color: getSeverityColor(issue.severity) }}>
                                                                [{issue.severity}]
                                                            </span>
                                                            {issue.message}
                                                        </div>
                                                    ))}
                                                </div>
                                            )}
                                            {(optimization.explainAnalysis.planText || optimization.explainAnalysis.planJson) && (
                                                <details style={{ marginTop: '8px' }}>
                                                    <summary style={{ cursor: 'pointer', fontSize: '12px', color: 'var(--color-light-6)' }}>
                                                        View Raw Execution Plan
                                                    </summary>
                                                    <pre className={styles.rawPlan} style={{ marginTop: '8px', maxHeight: '200px', overflow: 'auto' }}>
                                                        {optimization.explainAnalysis.planText ||
                                                            (typeof optimization.explainAnalysis.planJson === 'string'
                                                                ? optimization.explainAnalysis.planJson
                                                                : JSON.stringify(optimization.explainAnalysis.planJson, null, 2))}
                                                    </pre>
                                                </details>
                                            )}
                                        </div>
                                    )}
                                </div>
                            )}
                        </div>
                    )}

                </div>
            )}
        </div>
    )
}
