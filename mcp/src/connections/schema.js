"use strict";

/**
 * JSON Schema (Draft 2020-12) for the `deepsql connections add --from-file`
 * input. Mirrors the backend's ConnectionRequest DTO field-for-field so an
 * AI agent can use this as the canonical contract.
 *
 * Field definitions are kept in one place so it's hard for the JSON Schema
 * and the CLI's interactive prompts to drift apart. We also use this for
 * `connections show` masking and for `connections add` validation.
 */

const SECRET_FIELDS = [
  "password",
  "sshPassword",
  "sshPrivateKey",
  "sshPassphrase",
  "sslCaCertificate",
  "sslClientCertificate",
  "sslClientKey",
  "sslClientKeyPassphrase",
];

const SCHEMA = {
  $schema: "https://json-schema.org/draft/2020-12/schema",
  $id: "https://deepsql.ai/schemas/connection-request.json",
  title: "DeepSQL Connection",
  description:
    "Input for `deepsql connections add --from-file`. Mirrors the backend's ConnectionRequest DTO. Secret fields support $VAR_NAME (env var) and @file:<path> (file ref) — see `deepsql connections schema` for details.",
  type: "object",
  required: ["connectionName", "dbType", "host", "port", "database", "username"],
  additionalProperties: false,
  properties: {
    connectionName: {
      type: "string",
      minLength: 1,
      description: "Display name. Pass this to `--connection` everywhere else.",
    },
    dbType: {
      type: "string",
      enum: ["postgres", "mysql"],
      description: "Database engine. Aliases (postgresql, aurora-postgres, etc.) are normalized server-side.",
    },
    host: { type: "string", minLength: 1, description: "Database hostname or IP. If sshEnabled=true, this is reached *through* the bastion." },
    port: { type: "integer", minimum: 1, maximum: 65535 },
    database: { type: "string", minLength: 1 },
    username: { type: "string", minLength: 1 },
    password: {
      type: "string",
      description: "DB password. Required on create; on update, omit to keep existing. Accepts $VAR / @file: refs.",
    },

    // Legacy SSL boolean (back-compat — prefer sslMode).
    ssl: { type: "boolean", description: "Legacy: use sslMode instead. true = server-only TLS." },

    // SSL fields
    sslMode: {
      type: "string",
      enum: ["none", "server-only", "server-client"],
      description: "TLS mode. server-client requires the three sslClient* fields.",
    },
    sslCaCertificate: { type: "string", description: "PEM-encoded CA cert. Accepts @file:." },
    sslClientCertificate: { type: "string", description: "PEM-encoded client cert (sslMode=server-client only). Accepts @file:." },
    sslClientKey: { type: "string", description: "PEM-encoded client key (sslMode=server-client only). Accepts @file:." },
    sslClientKeyPassphrase: { type: "string", description: "Passphrase for an encrypted client key. Accepts $VAR / @file:." },

    // SSH tunnel fields
    sshEnabled: { type: "boolean", default: false, description: "Connect via an SSH bastion." },
    sshAuthType: {
      type: "string",
      enum: ["PASSWORD", "PRIVATE_KEY"],
      default: "PASSWORD",
      description: "PASSWORD requires sshPassword; PRIVATE_KEY requires sshPrivateKey (and optionally sshPassphrase).",
    },
    sshHost: { type: "string", description: "Bastion hostname. Required when sshEnabled=true." },
    sshPort: { type: "integer", default: 22, minimum: 1, maximum: 65535 },
    sshUsername: { type: "string", description: "Bastion login. Required when sshEnabled=true." },
    sshPassword: { type: "string", description: "Bastion password (sshAuthType=PASSWORD). Accepts $VAR." },
    sshPrivateKey: { type: "string", description: "PEM-encoded SSH private key (sshAuthType=PRIVATE_KEY). Accepts @file:." },
    sshPassphrase: { type: "string", description: "Passphrase for an encrypted SSH key. Accepts $VAR / @file:." },

    // Cloud / instance metadata (informational; used for performance tuning calculations)
    cloudProvider: { type: "string", enum: ["aws", "azure", "gcp", "self-hosted"], description: "Used for feature gating + tuning hints." },
    managedService: { type: "string", enum: ["rds", "aurora", "cloud-sql", "azure-flexible", "azure-single"] },
    instanceClass: { type: "string", description: "e.g. db.r6g.xlarge, db-n1-standard-4." },
    instanceVcpus: { type: "integer", minimum: 1 },
    instanceMemoryGb: { type: "number", minimum: 0 },
    storageType: { type: "string", description: "e.g. gp3, io1, premium-ssd, pd-ssd." },
    storageMaxIops: { type: "integer", minimum: 0 },

    enableDataSampling: {
      type: "boolean",
      description: "Default true. Disables column-value sampling that DeepSQL uses for entity disambiguation.",
    },

    // Allow id on update flow (ignored on create).
    id: { type: "string", description: "Set by the server; ignored on create." },
  },
  // Conditional "if SSH enabled, host+username required" — JSON Schema 2020-12.
  allOf: [
    {
      if: { properties: { sshEnabled: { const: true } }, required: ["sshEnabled"] },
      then: { required: ["sshHost", "sshUsername"] },
    },
    {
      if: {
        properties: {
          sshEnabled: { const: true },
          sshAuthType: { const: "PASSWORD" },
        },
        required: ["sshEnabled", "sshAuthType"],
      },
      then: { required: ["sshPassword"] },
    },
    {
      if: {
        properties: {
          sshEnabled: { const: true },
          sshAuthType: { const: "PRIVATE_KEY" },
        },
        required: ["sshEnabled", "sshAuthType"],
      },
      then: { required: ["sshPrivateKey"] },
    },
    {
      if: { properties: { sslMode: { const: "server-client" } }, required: ["sslMode"] },
      then: { required: ["sslClientCertificate", "sslClientKey"] },
    },
  ],
};

/**
 * Lightweight validator. We don't pull in ajv to keep the package small —
 * this checks the things we care about: required fields (incl. conditional),
 * enum values, simple types, and minLength on strings.
 *
 * Returns { ok: true } or { ok: false, errors: [{ path, message }] }.
 */
function validate(input) {
  if (input == null || typeof input !== "object" || Array.isArray(input)) {
    return { ok: false, errors: [{ path: "$", message: "Input must be a JSON object." }] };
  }
  const errors = [];

  for (const [key, schema] of Object.entries(SCHEMA.properties)) {
    const value = input[key];
    if (value === undefined) continue;
    const typeError = checkType(key, value, schema);
    if (typeError) errors.push(typeError);
  }

  for (const required of SCHEMA.required) {
    if (input[required] === undefined || input[required] === null || input[required] === "") {
      errors.push({ path: required, message: `is required` });
    }
  }

  // Conditional rules
  if (input.sshEnabled === true) {
    if (!input.sshHost) errors.push({ path: "sshHost", message: "is required when sshEnabled=true" });
    if (!input.sshUsername) errors.push({ path: "sshUsername", message: "is required when sshEnabled=true" });
    const auth = input.sshAuthType || "PASSWORD";
    if (auth === "PASSWORD" && !input.sshPassword) {
      errors.push({ path: "sshPassword", message: "is required when sshEnabled=true and sshAuthType=PASSWORD" });
    }
    if (auth === "PRIVATE_KEY" && !input.sshPrivateKey) {
      errors.push({ path: "sshPrivateKey", message: "is required when sshEnabled=true and sshAuthType=PRIVATE_KEY" });
    }
  }
  if (input.sslMode === "server-client") {
    if (!input.sslClientCertificate) errors.push({ path: "sslClientCertificate", message: "is required when sslMode=server-client" });
    if (!input.sslClientKey) errors.push({ path: "sslClientKey", message: "is required when sslMode=server-client" });
  }

  // Forbid unknown fields (additionalProperties: false in the schema).
  for (const key of Object.keys(input)) {
    if (!(key in SCHEMA.properties)) {
      errors.push({ path: key, message: "is not a recognized field" });
    }
  }

  return errors.length === 0 ? { ok: true } : { ok: false, errors };
}

function checkType(path, value, schema) {
  if (schema.enum && !schema.enum.includes(value)) {
    return { path, message: `must be one of: ${schema.enum.join(", ")}` };
  }
  if (schema.type === "string" && typeof value !== "string") {
    return { path, message: `must be a string` };
  }
  if (schema.type === "integer" && (!Number.isInteger(value))) {
    return { path, message: `must be an integer` };
  }
  if (schema.type === "number" && typeof value !== "number") {
    return { path, message: `must be a number` };
  }
  if (schema.type === "boolean" && typeof value !== "boolean") {
    return { path, message: `must be true or false` };
  }
  if (schema.minLength != null && typeof value === "string" && value.length < schema.minLength) {
    return { path, message: `must be at least ${schema.minLength} character(s)` };
  }
  if (schema.minimum != null && typeof value === "number" && value < schema.minimum) {
    return { path, message: `must be ≥ ${schema.minimum}` };
  }
  if (schema.maximum != null && typeof value === "number" && value > schema.maximum) {
    return { path, message: `must be ≤ ${schema.maximum}` };
  }
  return null;
}

module.exports = { SCHEMA, SECRET_FIELDS, validate };
