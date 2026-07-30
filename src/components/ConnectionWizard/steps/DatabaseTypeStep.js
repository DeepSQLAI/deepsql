import { useState } from 'react'
import { ChevronDown, ChevronUp, Settings, Database, Copy, Check } from 'lucide-react'
import { DATABASE_TYPES, SETUP_REQUIREMENTS } from '../utils/constants'
import { SelectableCard } from '../components/SelectableCard'
import styles from '../ConnectionWizard.module.css'

/**
 * Step 1: Select database type (MySQL or PostgreSQL)
 * Features custom database logos for instant recognition
 * Shows setup requirements for slow query analysis
 */
export function DatabaseTypeStep({ formData, updateFormData, errors }) {
  const [showSetup, setShowSetup] = useState(false)
  const [copiedSql, setCopiedSql] = useState(null)

  const setupInfo = formData.dbType ? SETUP_REQUIREMENTS[formData.dbType] : null

  const handleCopySql = async (sql, index) => {
    try {
      await navigator.clipboard.writeText(sql)
      setCopiedSql(index)
      setTimeout(() => setCopiedSql(null), 2000)
    } catch (err) {
      console.error('Failed to copy:', err)
    }
  }

  return (
    <div className={styles.stepContent}>
      <div className={styles.stepHeader}>
        <h2>Select Database Type</h2>
        <p>Choose the type of database you want to connect to</p>
      </div>

      <div className={styles.databaseTypeGrid}>
        {DATABASE_TYPES.map((db) => (
          <SelectableCard
            key={db.id}
            id={db.id}
            name={db.name}
            description={db.description}
            icon={db.id}
            iconType="database"
            selected={formData.dbType === db.id}
            onClick={(id) => updateFormData('dbType', id)}
            size="large"
          />
        ))}
      </div>

      {errors.dbType && (
        <p className={styles.errorMessage}>{errors.dbType}</p>
      )}

      {/* Setup Requirements Accordion */}
      {setupInfo && (
        <div className={styles.setupRequirements}>
          <button
            type="button"
            className={styles.setupHeader}
            onClick={() => setShowSetup(!showSetup)}
          >
            <div className={styles.setupHeaderLeft}>
              <Settings size={18} />
              <span>{setupInfo.title}</span>
            </div>
            {showSetup ? <ChevronUp size={18} /> : <ChevronDown size={18} />}
          </button>

          {showSetup && (
            <div className={styles.setupContent}>
              <p className={styles.setupDescription}>{setupInfo.description}</p>

              {/* SQL Steps */}
              {setupInfo.steps && setupInfo.steps.length > 0 && (
                <div className={styles.setupSection}>
                  <h4>
                    <Database size={14} /> Required Extension
                  </h4>
                  {setupInfo.steps.map((step, idx) => (
                    <div key={idx} className={styles.sqlStep}>
                      <div className={styles.sqlHeader}>
                        <span>{step.title}</span>
                        <button
                          type="button"
                          className={styles.copyButton}
                          onClick={() => handleCopySql(step.sql, idx)}
                          title="Copy SQL"
                        >
                          {copiedSql === idx ? <Check size={14} /> : <Copy size={14} />}
                        </button>
                      </div>
                      <pre className={styles.sqlCode}>{step.sql}</pre>
                      {step.note && <p className={styles.sqlNote}>{step.note}</p>}
                    </div>
                  ))}
                </div>
              )}

              {/* Server Parameters */}
              {setupInfo.serverParameters && (
                <div className={styles.setupSection}>
                  <h4>
                    <Settings size={14} /> Server Parameters
                  </h4>
                  <table className={styles.parameterTable}>
                    <thead>
                      <tr>
                        <th>Parameter</th>
                        <th>Value</th>
                        <th>Description</th>
                      </tr>
                    </thead>
                    <tbody>
                      {setupInfo.serverParameters.map((param, idx) => (
                        <tr key={idx} className={param.required ? styles.required : ''}>
                          <td>
                            <code>{param.name}</code>
                            {param.required && <span className={styles.requiredBadge}>Required</span>}
                          </td>
                          <td><code>{param.value}</code></td>
                          <td>{param.description}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}

              {/* SSL Note for PostgreSQL */}
              {setupInfo.sslNote && (
                <div className={styles.sslNote}>
                  <strong>SSL Required:</strong> {setupInfo.sslNote}
                </div>
              )}

              {/* Cloud Instructions (collapsed by default) */}
              {setupInfo.cloudInstructions && (
                <div className={styles.cloudInstructions}>
                  <h4>Cloud Provider Instructions</h4>
                  <div className={styles.cloudTabs}>
                    {Object.entries(setupInfo.cloudInstructions).map(([provider, info]) => (
                      <details key={provider} className={styles.cloudProvider}>
                        <summary>{info.title}</summary>
                        <ol className={styles.cloudSteps}>
                          {info.steps.map((step, idx) => (
                            <li key={idx}>{step}</li>
                          ))}
                        </ol>
                      </details>
                    ))}
                  </div>
                </div>
              )}
            </div>
          )}
        </div>
      )}

      <div className={styles.stepNote}>
        <p>
          <strong>Note:</strong> DBA Agent provides intelligent query assistance,
          performance insights, and optimization recommendations for your selected database.
        </p>
      </div>
    </div>
  )
}

export default DatabaseTypeStep
