import { Database } from "lucide-react";
import styles from "../ConnectionWizard.module.css";

/**
 * Step 0: Choose connection category — Database only.
 * Auto-selects "database" and renders a single option.
 */
export function ConnectionCategoryStep({
  formData,
  updateFormData,
  errors,
}) {
  return (
    <div className={styles.stepContent}>
      <div className={styles.stepHeader}>
        <h2>What would you like to connect?</h2>
        <p>Choose the type of integration to set up</p>
      </div>

      <div className={styles.databaseTypeGrid}>
        <button
          type="button"
          className={`${styles.selectableCard} ${styles.large} ${formData.connectionCategory === "database" ? styles.selected : ""}`}
          onClick={() => updateFormData("connectionCategory", "database")}
          role="option"
          aria-selected={formData.connectionCategory === "database"}
        >
          <div className={styles.cardIcon}>
            <Database size={40} color="#336791" strokeWidth={1.5} />
          </div>
          <div className={styles.cardContent}>
            <span className={styles.cardName}>Database</span>
            <span className={styles.cardDescription}>Connect to PostgreSQL, MySQL, or other databases</span>
          </div>
        </button>
      </div>

      {errors.connectionCategory && (
        <p className={styles.errorMessage}>{errors.connectionCategory}</p>
      )}
    </div>
  );
}

export default ConnectionCategoryStep;
