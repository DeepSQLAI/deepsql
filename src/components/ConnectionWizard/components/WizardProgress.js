import { Check } from 'lucide-react'
import styles from '../ConnectionWizard.module.css'

/**
 * Horizontal progress indicator for the wizard steps
 */
export function WizardProgress({ steps, currentStep, onStepClick }) {
  return (
    <div className={styles.progressContainer}>
      <div className={styles.progressTrack}>
        {steps.map((step, index) => {
          const stepNumber = index + 1
          const isCompleted = stepNumber < currentStep
          const isCurrent = stepNumber === currentStep
          const isClickable = stepNumber < currentStep // Can only go back

          return (
            <div key={step.id} className={styles.progressStep}>
              {/* Connector line (not for first step) */}
              {index > 0 && (
                <div
                  className={`${styles.progressConnector} ${isCompleted || isCurrent ? styles.active : ''}`}
                />
              )}

              {/* Step circle */}
              <button
                type="button"
                className={`${styles.progressCircle} ${isCompleted ? styles.completed : ''} ${isCurrent ? styles.current : ''}`}
                onClick={() => isClickable && onStepClick?.(stepNumber)}
                disabled={!isClickable}
                title={step.title}
              >
                {isCompleted ? <Check size={14} /> : stepNumber}
              </button>

              {/* Step label (visible on larger screens) */}
              <span
                className={`${styles.progressLabel} ${isCurrent ? styles.currentLabel : ''}`}
              >
                {step.title}
              </span>
            </div>
          )
        })}
      </div>
    </div>
  )
}

export default WizardProgress
