import { useState, useCallback, useMemo, useEffect } from "react";
import {
  DEFAULT_FORM_DATA,
  DATABASE_TYPES,
  DB_WIZARD_STEPS,
} from "../utils/constants";
import { validateStep, isStepValid } from "../utils/validators";

/**
 * Custom hook for managing Connection Wizard state.
 *
 * Database flow: steps 1-5 (type, deployment, connection, security, connectivity)
 */
export function useConnectionWizard(existingConnection = null) {
  const isEditMode = existingConnection !== null;

  // Initialize form data from existing connection or defaults
  const initialFormData = useMemo(() => {
    if (!existingConnection) {
      return { ...DEFAULT_FORM_DATA };
    }

    // Map existing connection to form data
    const dbInfo = DATABASE_TYPES.find(
      (db) => db.id === existingConnection.dbType,
    );

    return {
      ...DEFAULT_FORM_DATA,
      connectionCategory: "database",
      dbType: existingConnection.dbType || "",
      cloudProvider: existingConnection.cloudProvider || "",
      managedService: existingConnection.managedService || "",
      connectionName: existingConnection.connectionName || "",
      host: existingConnection.host || "",
      port: existingConnection.port || dbInfo?.defaultPort || "",
      database: existingConnection.database || "",
      username: existingConnection.username || "",
      password: "", // Never pre-fill password
      sslMode:
        existingConnection.sslMode ||
        (existingConnection.ssl ? "server-only" : "none"),
      sslCaCertificate: "", // Never pre-fill certs
      sslClientCertificate: "",
      sslClientKey: "",
      sslClientKeyPassphrase: "",
      connectivityMethod: existingConnection.sshEnabled
        ? "ssh-tunnel"
        : "direct",
      sshEnabled: existingConnection.sshEnabled || false,
      sshAuthType: existingConnection.sshAuthType || "PASSWORD",
      sshHost: existingConnection.sshHost || "",
      sshPort: existingConnection.sshPort || 22,
      sshUsername: existingConnection.sshUsername || "",
      sshPassword: "", // Never pre-fill
      sshPrivateKey: "", // Never pre-fill
      sshPassphrase: "", // Never pre-fill
      instanceClass: existingConnection.instanceClass || "",
      instanceVcpus: existingConnection.instanceVcpus || "",
      instanceMemoryGb: existingConnection.instanceMemoryGb || "",
      storageType: existingConnection.storageType || "",
      storageMaxIops: existingConnection.storageMaxIops || "",
    };
  }, [existingConnection]);

  const [formData, setFormData] = useState(initialFormData);
  const [currentStep, setCurrentStep] = useState(isEditMode ? 1 : 0);
  const [errors, setErrors] = useState({});
  const [touched, setTouched] = useState({});

  // Re-sync state when existingConnection changes (edit modal opens/closes)
  useEffect(() => {
    setFormData(initialFormData);
    setCurrentStep(existingConnection ? 1 : 0);
    setErrors({});
    setTouched({});
  }, [existingConnection, initialFormData]);

  // Determine which steps are visible based on form data
  const visibleSteps = useMemo(() => {
    // Database flow: SSL step (4) is hidden when SSH tunnel is selected
    if (formData.connectivityMethod === "ssh-tunnel") {
      return DB_WIZARD_STEPS.filter((step) => step.id !== 4);
    }
    return DB_WIZARD_STEPS;
  }, [formData.connectivityMethod]);

  // Derive the effective step, clamping if visible steps shrank
  const effectiveStep =
    currentStep > visibleSteps.length ? visibleSteps.length : currentStep;

  // Map current step to actual step number considering hidden steps (DB flow only)
  const getActualStep = useCallback(
    (displayStep) => {
      if (displayStep === 0) return 0;
      if (formData.connectivityMethod === "ssh-tunnel" && displayStep >= 4) {
        return displayStep + 1; // Skip step 4
      }
      return displayStep;
    },
    [formData.connectivityMethod],
  );

  // Update form data
  const updateFormData = useCallback((field, value) => {
    setFormData((prev) => {
      const updated = { ...prev, [field]: value };

      // Handle connectivity method change
      if (field === "connectivityMethod") {
        if (value === "ssh-tunnel") {
          updated.sshEnabled = true;
          updated.sslMode = "none";
          updated.sslCaCertificate = "";
          updated.sslClientCertificate = "";
          updated.sslClientKey = "";
          updated.sslClientKeyPassphrase = "";
        } else {
          updated.sshEnabled = false;
          updated.sshHost = "";
          updated.sshPort = 22;
          updated.sshUsername = "";
          updated.sshPassword = "";
          updated.sshPrivateKey = "";
          updated.sshPassphrase = "";
        }
      }

      // Handle database type change - set default port
      if (field === "dbType") {
        const dbInfo = DATABASE_TYPES.find((db) => db.id === value);
        if (dbInfo && !prev.port) {
          updated.port = dbInfo.defaultPort;
        }
      }

      // Handle cloud provider change - reset managed service
      if (field === "cloudProvider") {
        updated.managedService = "";
      }

      // Handle SSH auth type change - clear other auth fields
      if (field === "sshAuthType") {
        if (value === "PASSWORD") {
          updated.sshPrivateKey = "";
          updated.sshPassphrase = "";
        } else {
          updated.sshPassword = "";
        }
      }

      // Handle category change - reset DB fields
      if (field === "connectionCategory") {
        updated.crmType = "";
        updated.crmConnectionName = "";
        updated.crmCredentials = {};
      }

      return updated;
    });

    // Mark field as touched
    setTouched((prev) => ({ ...prev, [field]: true }));

    // Clear error for this field
    setErrors((prev) => {
      const updated = { ...prev };
      delete updated[field];
      return updated;
    });
  }, []);

  // Validate current step
  const validateCurrentStep = useCallback(() => {
    if (effectiveStep === 0) {
      if (!formData.connectionCategory) {
        setErrors({
          connectionCategory: "Please select a connection category",
        });
        return false;
      }
      setErrors({});
      return true;
    }

    const actualStep = getActualStep(effectiveStep);
    const stepErrors = validateStep(actualStep, formData, isEditMode);
    setErrors(stepErrors);
    return Object.keys(stepErrors).length === 0;
  }, [effectiveStep, formData, isEditMode, getActualStep]);

  // Go to next step
  const nextStep = useCallback(() => {
    if (validateCurrentStep()) {
      const maxStep = visibleSteps.length;
      if (effectiveStep < maxStep) {
        setCurrentStep(effectiveStep + 1);
        setErrors({});
      }
    }
  }, [validateCurrentStep, effectiveStep, visibleSteps.length]);

  // Go to previous step
  const prevStep = useCallback(() => {
    if (effectiveStep > 0) {
      setCurrentStep(effectiveStep - 1);
      setErrors({});
    }
  }, [effectiveStep]);

  // Go to specific step
  const goToStep = useCallback((step) => {
    if (step >= 0 && step <= 5) {
      setCurrentStep(step);
      setErrors({});
    }
  }, []);

  // Check if we can proceed to next step
  const canProceed = useMemo(() => {
    if (effectiveStep === 0) {
      return !!formData.connectionCategory;
    }
    const actualStep = getActualStep(effectiveStep);
    return isStepValid(actualStep, formData, isEditMode);
  }, [effectiveStep, formData, isEditMode, getActualStep]);

  // Check if this is the last step
  const isLastStep = useMemo(() => {
    return effectiveStep === visibleSteps.length;
  }, [effectiveStep, visibleSteps.length]);

  // Get connection request payload for API (database flow only)
  const getConnectionPayload = useCallback(() => {
    const payload = {
      connectionName: formData.connectionName,
      dbType: formData.dbType,
      host: formData.host,
      port: formData.port ? parseInt(formData.port, 10) : null,
      database: formData.database,
      username: formData.username,
      cloudProvider: formData.cloudProvider || null,
      managedService: formData.managedService || null,
      instanceClass: formData.instanceClass || null,
      instanceVcpus: formData.instanceVcpus
        ? parseInt(formData.instanceVcpus, 10)
        : null,
      instanceMemoryGb: formData.instanceMemoryGb
        ? parseFloat(formData.instanceMemoryGb)
        : null,
      storageType: formData.storageType || null,
      storageMaxIops: formData.storageMaxIops
        ? parseInt(formData.storageMaxIops, 10)
        : null,
    };

    // Password - only include if provided (for edit mode, empty means keep existing)
    if (formData.password) {
      payload.password = formData.password;
    }

    // SSL configuration
    payload.sslMode = formData.sslMode;
    payload.ssl = formData.sslMode !== "none";

    if (formData.sslMode !== "none") {
      if (formData.sslCaCertificate) {
        payload.sslCaCertificate = formData.sslCaCertificate;
      }
      if (formData.sslMode === "server-client") {
        if (formData.sslClientCertificate) {
          payload.sslClientCertificate = formData.sslClientCertificate;
        }
        if (formData.sslClientKey) {
          payload.sslClientKey = formData.sslClientKey;
        }
        if (formData.sslClientKeyPassphrase) {
          payload.sslClientKeyPassphrase = formData.sslClientKeyPassphrase;
        }
      }
    }

    // SSH tunnel configuration
    payload.sshEnabled = formData.connectivityMethod === "ssh-tunnel";

    if (payload.sshEnabled) {
      payload.sshAuthType = formData.sshAuthType;
      payload.sshHost = formData.sshHost;
      payload.sshPort = formData.sshPort ? parseInt(formData.sshPort, 10) : 22;
      payload.sshUsername = formData.sshUsername;

      if (formData.sshAuthType === "PASSWORD") {
        if (formData.sshPassword) {
          payload.sshPassword = formData.sshPassword;
        }
      } else if (formData.sshAuthType === "PRIVATE_KEY") {
        if (formData.sshPrivateKey) {
          payload.sshPrivateKey = formData.sshPrivateKey;
        }
        if (formData.sshPassphrase) {
          payload.sshPassphrase = formData.sshPassphrase;
        }
      }
    }

    return payload;
  }, [formData]);

  // Reset the wizard
  const reset = useCallback(() => {
    setFormData({ ...DEFAULT_FORM_DATA });
    setCurrentStep(0);
    setErrors({});
    setTouched({});
  }, []);

  return {
    formData,
    updateFormData,
    currentStep: effectiveStep,
    visibleSteps,
    errors,
    touched,
    isEditMode,
    existingConnection,
    nextStep,
    prevStep,
    goToStep,
    validateCurrentStep,
    canProceed,
    isLastStep,
    getConnectionPayload,
    reset,
    getActualStep,
  };
}

export default useConnectionWizard;
