"use client";

import { useState } from "react";
import { X, AlertCircle, Lightbulb } from "lucide-react";
import { connectionAPI } from "@/lib/api/client";
import { useAuth } from "@/hooks/useAuth";
import { queryClient } from "@/lib/queryClient";
import { queryKeys } from "@/lib/queryKeys";
import SlowQuerySourceModal from "../SlowQuerySourceModal";
import { useConnectionWizard } from "./hooks/useConnectionWizard";
import { WizardProgress } from "./components/WizardProgress";
import { WizardNavigation } from "./components/WizardNavigation";
import { ConnectionCategoryStep } from "./steps/ConnectionCategoryStep";
import { DatabaseTypeStep } from "./steps/DatabaseTypeStep";
import { CloudProviderStep } from "./steps/CloudProviderStep";
import { EndpointCredentialsStep } from "./steps/EndpointCredentialsStep";
import { ConnectionSecurityStep } from "./steps/ConnectionSecurityStep";
import { ConnectivityMethodStep } from "./steps/ConnectivityMethodStep";
import styles from "./ConnectionWizard.module.css";

/**
 * Multi-step connection wizard for adding/editing database connections.
 *
 * Flow:
 *   Step 0 → Category selection
 *   Database → Steps 1-5 (type, deployment, connection, security, connectivity)
 */
export function ConnectionWizard({
  isOpen,
  onClose,
  onConnectionSaved,
  existingConnection = null,
}) {
  const { isAdmin } = useAuth();
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [isTesting, setIsTesting] = useState(false);
  const [error, setError] = useState(null);
  const [showSuccess, setShowSuccess] = useState(false);
  const [savedConnectionId, setSavedConnectionId] = useState(null);
  const [showSlowQueryConfig, setShowSlowQueryConfig] = useState(false);

  const {
    formData,
    updateFormData,
    currentStep,
    visibleSteps,
    errors,
    isEditMode,
    nextStep,
    prevStep,
    goToStep,
    validateCurrentStep,
    canProceed,
    isLastStep,
    getConnectionPayload,
    reset,
    getActualStep,
  } = useConnectionWizard(existingConnection);

  const handleClose = () => {
    reset();
    setError(null);
    onClose();
  };

  const handleSuccessDone = () => {
    reset();
    setShowSuccess(false);
    onConnectionSaved?.(savedConnectionId);
  };

  // Submit for database connections
  const handleDbSubmit = async () => {
    const payload = getConnectionPayload();
    setIsTesting(true);

    // Test connection first
    const testResult = await connectionAPI.testConnection(payload);
    if (!testResult.success) {
      setError(testResult.message || "Connection test failed");
      setIsTesting(false);
      return;
    }

    setIsTesting(false);
    setIsSubmitting(true);

    let result;
    if (isEditMode && existingConnection?.id) {
      result = await connectionAPI.updateConnection(
        existingConnection.id,
        payload,
      );
    } else {
      result = await connectionAPI.saveConnection(payload);
    }

    if (result.success) {
      await queryClient.invalidateQueries({ queryKey: queryKeys.connections.all });
      if (isEditMode) {
        reset();
        onConnectionSaved?.(result.connectionId);
      } else {
        setSavedConnectionId(result.connectionId);
        setShowSuccess(true);
      }
    } else {
      setError(result.message || "Failed to save connection");
    }
  };

  const handleSubmit = async () => {
    if (!validateCurrentStep()) {
      return;
    }

    setError(null);

    try {
      await handleDbSubmit();
    } catch (err) {
      console.error("Connection error:", err);
      setError(
        err.response?.data?.message || err.message || "An error occurred",
      );
    } finally {
      setIsSubmitting(false);
      setIsTesting(false);
    }
  };

  // Render the current step content
  const renderStepContent = () => {
    // Step 0: category selection
    if (currentStep === 0) {
      return (
        <ConnectionCategoryStep
          formData={formData}
          updateFormData={updateFormData}
          errors={errors}
          isAdmin={isAdmin}
        />
      );
    }

    // Database flow
    const actualStep = getActualStep(currentStep);

    switch (actualStep) {
      case 1:
        return (
          <DatabaseTypeStep
            formData={formData}
            updateFormData={updateFormData}
            errors={errors}
          />
        );
      case 2:
        return (
          <CloudProviderStep
            formData={formData}
            updateFormData={updateFormData}
            errors={errors}
          />
        );
      case 3:
        return (
          <EndpointCredentialsStep
            formData={formData}
            updateFormData={updateFormData}
            errors={errors}
            isEditMode={isEditMode}
          />
        );
      case 4:
        return (
          <ConnectionSecurityStep
            formData={formData}
            updateFormData={updateFormData}
            errors={errors}
            isEditMode={isEditMode}
            existingConnection={existingConnection}
          />
        );
      case 5:
        return (
          <ConnectivityMethodStep
            formData={formData}
            updateFormData={updateFormData}
            errors={errors}
            isEditMode={isEditMode}
            existingConnection={existingConnection}
          />
        );
      default:
        return null;
    }
  };

  if (!isOpen) return null;

  // Success screen after new connection saved
  if (showSuccess) {
    return (
      <div className={styles.overlay} onClick={() => {}}>
        <div className={styles.modal} onClick={(e) => e.stopPropagation()}>
          <div className={styles.header}>
            <h1 className={styles.title}>Connection Saved</h1>
            <button
              type="button"
              className={styles.closeButton}
              onClick={handleSuccessDone}
            >
              <X size={20} />
            </button>
          </div>

          <div className={styles.content}>
            <div style={{ textAlign: "center", padding: "32px 24px" }}>
              <div
                style={{
                  width: 48,
                  height: 48,
                  borderRadius: "50%",
                  background: "#f0fdf4",
                  display: "flex",
                  alignItems: "center",
                  justifyContent: "center",
                  margin: "0 auto 16px",
                }}
              >
                <svg
                  width="24"
                  height="24"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="#16a34a"
                  strokeWidth="2"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                >
                  <polyline points="20 6 9 17 4 12" />
                </svg>
              </div>
              <h2
                style={{
                  fontSize: "18px",
                  fontWeight: 600,
                  color: "#111",
                  marginBottom: "8px",
                }}
              >
                Connection saved successfully!
              </h2>
              <p
                style={{
                  fontSize: "14px",
                  color: "#6b7280",
                  marginBottom: "24px",
                }}
              >
                Brain is now initializing in the background. You can start using the connection immediately.
              </p>

              {/* Slow query config hint */}
              <div
                  style={{
                    background: "#fffbeb",
                    border: "1px solid #fde68a",
                    borderRadius: "8px",
                    padding: "16px",
                    textAlign: "left",
                    marginBottom: "24px",
                  }}
                >
                  <div
                    style={{
                      display: "flex",
                      alignItems: "center",
                      gap: "8px",
                      marginBottom: "8px",
                    }}
                  >
                    <Lightbulb size={16} style={{ color: "#d97706" }} />
                    <span
                      style={{
                        fontWeight: 600,
                        fontSize: "13px",
                        color: "#92400e",
                      }}
                    >
                      Recommended: Configure Query Logs
                    </span>
                  </div>
                  <p style={{ fontSize: "13px", color: "#78350f", margin: 0 }}>
                    Connect slow query logs for AI-powered query optimization
                    and performance insights.
                  </p>
                </div>

              <div
                style={{
                  display: "flex",
                  gap: "12px",
                  justifyContent: "center",
                }}
              >
                <button
                  type="button"
                  onClick={() => setShowSlowQueryConfig(true)}
                  style={{
                    padding: "8px 16px",
                    borderRadius: "8px",
                    border: "1px solid #6366f1",
                    background: "#6366f1",
                    color: "white",
                    cursor: "pointer",
                    fontSize: "13px",
                    fontWeight: 500,
                  }}
                >
                  Configure Now
                </button>
                <button
                  type="button"
                  onClick={handleSuccessDone}
                  style={{
                    padding: "8px 16px",
                    borderRadius: "8px",
                    border: "1px solid #e5e7eb",
                    background: "white",
                    color: "#374151",
                    cursor: "pointer",
                    fontSize: "13px",
                    fontWeight: 500,
                  }}
                >
                  Go to Dashboard
                </button>
              </div>
            </div>
          </div>

          {showSlowQueryConfig && (
            <SlowQuerySourceModal
              connectionId={savedConnectionId}
              connectionName={formData.connectionName}
              dbType={formData.dbType}
              onClose={() => setShowSlowQueryConfig(false)}
            />
          )}
        </div>
      </div>
    );
  }

  // Determine title
  const getTitle = () => {
    if (currentStep === 0) return "Add Connection";
    if (isEditMode) return "Edit Connection";
    return "Add Connection";
  };

  return (
    <div className={styles.overlay} onClick={(e) => e.stopPropagation()}>
      <div className={styles.modal} onClick={(e) => e.stopPropagation()}>
        {/* Header */}
        <div className={styles.header}>
          <h1 className={styles.title}>{getTitle()}</h1>
          <button
            type="button"
            className={styles.closeButton}
            onClick={handleClose}
            disabled={isSubmitting || isTesting}
            aria-label="Close add connection"
          >
            <X size={20} />
          </button>
        </div>

        {/* Progress indicator — show only after category selection */}
        {currentStep > 0 && (
          <WizardProgress
            steps={visibleSteps}
            currentStep={currentStep}
            onStepClick={goToStep}
          />
        )}

        {/* Error message */}
        {error && (
          <div className={styles.errorBanner}>
            <AlertCircle size={18} />
            <span>{error}</span>
            <button
              type="button"
              className={styles.dismissError}
              onClick={() => setError(null)}
            >
              <X size={16} />
            </button>
          </div>
        )}

        {/* Step content */}
        <div className={styles.content}>{renderStepContent()}</div>

        {/* Navigation */}
        <WizardNavigation
          currentStep={currentStep}
          isLastStep={isLastStep}
          canProceed={canProceed}
          isSubmitting={isSubmitting}
          isTesting={isTesting}
          onBack={prevStep}
          onNext={nextStep}
          onSubmit={handleSubmit}
          onCancel={handleClose}
        />
      </div>
    </div>
  );
}

export default ConnectionWizard;
