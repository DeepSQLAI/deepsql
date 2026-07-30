/**
 * Validation utilities for the Connection Wizard
 */

/**
 * Check if a value is non-empty
 */
export const required = (value) => {
  if (value === null || value === undefined) return "This field is required";
  if (typeof value === "string" && value.trim() === "")
    return "This field is required";
  return null;
};

/**
 * Check if a value is a valid port number
 */
export const isValidPort = (value) => {
  if (!value) return null; // Port is optional, will use default
  const port = parseInt(value, 10);
  if (isNaN(port) || port < 1 || port > 65535) {
    return "Port must be between 1 and 65535";
  }
  return null;
};

/**
 * Check if a value looks like a valid hostname or IP
 */
export const isValidHost = (value) => {
  if (!value || value.trim() === "") return "Host is required";
  // Basic check - allows hostnames, IPv4, and IPv6
  const hostPattern =
    /^[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?(\.[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?)*$|^\d{1,3}(\.\d{1,3}){3}$|^\[?[a-fA-F0-9:]+\]?$/;
  if (!hostPattern.test(value.trim())) {
    return "Please enter a valid hostname or IP address";
  }
  return null;
};

/**
 * Check if a value looks like a valid PEM certificate
 */
export const isValidPemCertificate = (value) => {
  if (!value || value.trim() === "") return null; // Optional by default
  const trimmed = value.trim();
  if (!trimmed.includes("-----BEGIN") || !trimmed.includes("-----END")) {
    return "Certificate must be in PEM format (-----BEGIN ... -----END)";
  }
  return null;
};

/**
 * Check if a value looks like a valid PEM private key
 */
export const isValidPemKey = (value) => {
  if (!value || value.trim() === "") return null; // Optional by default
  const trimmed = value.trim();
  if (!trimmed.includes("-----BEGIN") || !trimmed.includes("-----END")) {
    return "Private key must be in PEM format (-----BEGIN ... -----END)";
  }
  if (!trimmed.includes("PRIVATE KEY")) {
    return "This does not appear to be a private key";
  }
  return null;
};

/**
 * Validate a single step
 * @param {number} step - Step number (1-5)
 * @param {object} formData - Current form data
 * @param {boolean} isEditMode - Whether we're editing an existing connection
 * @returns {object} - Object with field names as keys and error messages as values
 */
export const validateStep = (step, formData, isEditMode = false) => {
  const errors = {};

  switch (step) {
    case 1: // Database Type
      if (!formData.dbType) {
        errors.dbType = "Please select a database type";
      }
      break;

    case 2: // Cloud Provider (optional)
      // Cloud provider is optional, no validation needed
      break;

    case 3: // Connection Details
      if (!formData.connectionName?.trim()) {
        errors.connectionName = "Connection name is required";
      }

      const hostError = isValidHost(formData.host);
      if (hostError) {
        errors.host = hostError;
      }

      const portError = isValidPort(formData.port);
      if (portError) {
        errors.port = portError;
      }

      if (!formData.database?.trim()) {
        errors.database = "Database name is required";
      }

      if (!formData.username?.trim()) {
        errors.username = "Username is required";
      }

      // Password required for new connections, optional for edit
      if (!isEditMode && !formData.password?.trim()) {
        errors.password = "Password is required";
      }
      break;

    case 4: // SSL Configuration
      if (formData.sslMode === "server-client") {
        // CA certificate required only for mTLS (server-client), not for server-only
        // server-only uses sslmode=require which encrypts without verifying the CA
        if (!isEditMode && !formData.sslCaCertificate?.trim()) {
          errors.sslCaCertificate = "CA certificate is required for mTLS";
        }
        const caCertError = isValidPemCertificate(formData.sslCaCertificate);
        if (caCertError && formData.sslCaCertificate?.trim()) {
          errors.sslCaCertificate = caCertError;
        }
      } else if (formData.sslMode === "server-only") {
        // CA certificate is optional for server-only — validates format only if provided
        const caCertError = isValidPemCertificate(formData.sslCaCertificate);
        if (caCertError && formData.sslCaCertificate?.trim()) {
          errors.sslCaCertificate = caCertError;
        }
      }

      if (formData.sslMode === "server-client") {
        // Client cert and key required for mTLS (unless editing and already configured)
        if (!isEditMode && !formData.sslClientCertificate?.trim()) {
          errors.sslClientCertificate =
            "Client certificate is required for mTLS";
        }
        const clientCertError = isValidPemCertificate(
          formData.sslClientCertificate,
        );
        if (clientCertError && formData.sslClientCertificate?.trim()) {
          errors.sslClientCertificate = clientCertError;
        }

        if (!isEditMode && !formData.sslClientKey?.trim()) {
          errors.sslClientKey = "Client key is required for mTLS";
        }
        const clientKeyError = isValidPemKey(formData.sslClientKey);
        if (clientKeyError && formData.sslClientKey?.trim()) {
          errors.sslClientKey = clientKeyError;
        }
      }
      break;

    case 5: // Connectivity Method
      if (!formData.connectivityMethod) {
        errors.connectivityMethod = "Please select a connectivity method";
      }

      if (formData.connectivityMethod === "ssh-tunnel") {
        if (!formData.sshHost?.trim()) {
          errors.sshHost = "SSH host is required";
        }

        const sshPortError = isValidPort(formData.sshPort);
        if (sshPortError) {
          errors.sshPort = sshPortError;
        }

        if (!formData.sshUsername?.trim()) {
          errors.sshUsername = "SSH username is required";
        }

        // SSH auth validation
        if (formData.sshAuthType === "PASSWORD") {
          if (!isEditMode && !formData.sshPassword?.trim()) {
            errors.sshPassword = "SSH password is required";
          }
        } else if (formData.sshAuthType === "PRIVATE_KEY") {
          if (!isEditMode && !formData.sshPrivateKey?.trim()) {
            errors.sshPrivateKey = "SSH private key is required";
          }
          const keyError = isValidPemKey(formData.sshPrivateKey);
          if (keyError && formData.sshPrivateKey?.trim()) {
            errors.sshPrivateKey = keyError;
          }
        }
      }
      break;

    default:
      break;
  }

  return errors;
};

/**
 * Check if a step has any validation errors
 */
export const isStepValid = (step, formData, isEditMode = false) => {
  const errors = validateStep(step, formData, isEditMode);
  return Object.keys(errors).length === 0;
};

/**
 * Get all validation errors across all steps
 */
export const validateAll = (formData, isEditMode = false) => {
  const allErrors = {};

  for (let step = 1; step <= 5; step++) {
    const stepErrors = validateStep(step, formData, isEditMode);
    Object.assign(allErrors, stepErrors);
  }

  return allErrors;
};
