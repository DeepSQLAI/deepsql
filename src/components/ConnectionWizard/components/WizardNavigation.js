import { ChevronLeft, ChevronRight, Loader } from "lucide-react";
import styles from "../ConnectionWizard.module.css";

/**
 * Navigation buttons for the wizard (Back, Next, Submit)
 */
export function WizardNavigation({
  currentStep,
  isLastStep,
  canProceed,
  isSubmitting,
  isTesting,
  onBack,
  onNext,
  onSubmit,
  onCancel,
}) {
  return (
    <div className={styles.navigationContainer}>
      <div className={styles.navigationLeft}>
        <button
          type="button"
          className={styles.cancelButton}
          onClick={onCancel}
          disabled={isSubmitting || isTesting}
        >
          Cancel
        </button>
      </div>

      <div className={styles.navigationRight}>
        {currentStep > 0 && (
          <button
            type="button"
            className={styles.backButton}
            onClick={onBack}
            disabled={isSubmitting || isTesting}
          >
            <ChevronLeft size={18} />
            Back
          </button>
        )}

        {isLastStep ? (
          <button
            type="button"
            className={styles.submitButton}
            onClick={onSubmit}
            disabled={!canProceed || isSubmitting || isTesting}
          >
            {isSubmitting || isTesting ? (
              <>
                <Loader size={18} className={styles.spinner} />
                {isTesting ? "Testing Connection..." : "Saving..."}
              </>
            ) : (
              "Save Connection"
            )}
          </button>
        ) : (
          <button
            type="button"
            className={styles.nextButton}
            onClick={onNext}
            disabled={!canProceed}
          >
            Next
            <ChevronRight size={18} />
          </button>
        )}
      </div>
    </div>
  );
}

export default WizardNavigation;
