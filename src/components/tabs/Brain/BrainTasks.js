'use client'

import { AlertCircle } from 'lucide-react'
import { formatTimestamp } from './utils/formatUtils'
import styles from '../Core/RagTrainingTab.module.css'

/**
 * Brain tasks section showing open BCNF review tasks
 */
export function BrainTasks({
    tasks = [],
    loading = false,
    error = null,
    onReviewTask,
    onCompleteTask
}) {
    return (
        <div className={styles.brainTasks}>
            <div className={styles.brainSectionHeader}>
                <h3>Brain tasks</h3>
                <span>{tasks.length} open</span>
            </div>

            {loading ? (
                <div className={styles.brainEmpty}>Loading tasks...</div>
            ) : error ? (
                <div className={styles.brainError}>
                    <AlertCircle size={14} />
                    <span>{error}</span>
                </div>
            ) : tasks.length ? (
                <div className={styles.brainTaskList}>
                    {tasks.map((task) => {
                        const taskLabel = task.tableLabel || task.tableName || task.summary
                        const taskPayload = {
                            tableName: task.tableName || '',
                            tableLabel: task.tableLabel || task.tableName || task.summary,
                            bcnfScore: task.bcnfScore,
                            issues: task.issues || [],
                            suggestions: task.suggestions || []
                        }

                        return (
                            <div key={task.id} className={styles.brainTaskRow}>
                                <div>
                                    <div className={styles.brainTaskTitle}>{task.summary || taskLabel}</div>
                                    <div className={styles.brainTaskMeta}>
                                        {taskLabel} • {formatTimestamp(task.createdAt)}
                                    </div>
                                </div>
                                <div className={styles.brainTaskActions}>
                                    <button
                                        type="button"
                                        className={styles.inlineButton}
                                        onClick={() => onReviewTask(taskPayload)}
                                    >
                                        Review
                                    </button>
                                    <button
                                        type="button"
                                        className={styles.inlineButton}
                                        onClick={() => onCompleteTask(task.id)}
                                    >
                                        Mark done
                                    </button>
                                </div>
                            </div>
                        )
                    })}
                </div>
            ) : (
                <div className={styles.brainEmpty}>No open tasks.</div>
            )}
        </div>
    )
}
