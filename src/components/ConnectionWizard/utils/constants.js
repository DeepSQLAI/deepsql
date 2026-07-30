export const DATABASE_TYPES = [
  {
    id: "postgres",
    name: "PostgreSQL",
    icon: "/postgres-icon.svg",
    description: "Advanced open-source relational database",
    defaultPort: 5432,
  },
  {
    id: "mysql",
    name: "MySQL",
    icon: "/mysql-icon.svg",
    description: "Popular open-source relational database",
    defaultPort: 3306,
  },
];

// Cloud providers
export const CLOUD_PROVIDERS = [
  {
    id: "aws",
    name: "AWS",
    icon: "aws",
  },
  {
    id: "azure",
    name: "Azure",
    icon: "azure",
  },
  {
    id: "gcp",
    name: "GCP",
    icon: "gcp",
  },
  {
    id: "self-hosted",
    name: "Self-Hosted",
    icon: "server",
  },
];

// Managed services per provider
export const MANAGED_SERVICES = {
  aws: [
    { id: "rds", name: "RDS" },
    { id: "aurora", name: "Aurora" },
    { id: "ec2", name: "EC2 (Self-managed)" },
  ],
  azure: [
    { id: "azure-flexible", name: "Flexible Server" },
    { id: "azure-single", name: "Single Server" },
    { id: "azure-vm", name: "VM (Self-managed)" },
  ],
  gcp: [
    { id: "cloud-sql", name: "Cloud SQL" },
    { id: "alloydb", name: "AlloyDB" },
    { id: "gce", name: "GCE (Self-managed)" },
  ],
  "self-hosted": [],
};

// SSL modes
export const SSL_MODES = [
  {
    id: "none",
    name: "No SSL",
    description: "Connection is not encrypted. Use only in trusted networks.",
  },
  {
    id: "server-only",
    name: "Server-Only SSL",
    description: "Verify server certificate. Server authenticates to client.",
  },
  {
    id: "server-client",
    name: "Mutual TLS (mTLS)",
    description: "Both server and client authenticate with certificates.",
  },
];

// Connectivity methods
export const CONNECTIVITY_METHODS = [
  {
    id: "direct",
    name: "IP Allowlisting",
    description: "Connect directly by adding our IP to your allowlist",
    icon: "globe",
  },
  {
    id: "ssh-tunnel",
    name: "SSH Tunnel",
    description: "Connect via SSH bastion/jump host",
    icon: "lock",
  },
];

// Static IPs for allowlisting
export const STATIC_IPS = [{ ip: "104.43.252.169", region: "NAT Gateway" }];

// Storage types
export const STORAGE_TYPES = [
  { id: "gp2", name: "GP2 (General Purpose SSD)", provider: "aws" },
  { id: "gp3", name: "GP3 (General Purpose SSD)", provider: "aws" },
  { id: "io1", name: "IO1 (Provisioned IOPS)", provider: "aws" },
  { id: "io2", name: "IO2 (Provisioned IOPS)", provider: "aws" },
  { id: "premium-ssd", name: "Premium SSD", provider: "azure" },
  { id: "standard-ssd", name: "Standard SSD", provider: "azure" },
  { id: "pd-ssd", name: "SSD Persistent Disk", provider: "gcp" },
  { id: "pd-balanced", name: "Balanced Persistent Disk", provider: "gcp" },
  { id: "local-ssd", name: "Local SSD", provider: "self-hosted" },
  { id: "local-hdd", name: "Local HDD", provider: "self-hosted" },
  {
    id: "network-storage",
    name: "Network Storage (NFS/SAN)",
    provider: "self-hosted",
  },
  { id: "unknown", name: "Unknown / Other", provider: "all" },
];

// Required database privileges
export const REQUIRED_PRIVILEGES = {
  postgres: [
    { name: "SELECT", tables: "All tables", reason: "Read data for analysis" },
    {
      name: "pg_stat_statements",
      tables: "Extension",
      reason: "Slow query analysis",
    },
    {
      name: "pg_stat_user_tables",
      tables: "System view",
      reason: "Table statistics",
    },
    {
      name: "information_schema",
      tables: "System schema",
      reason: "Schema introspection",
    },
  ],
  mysql: [
    { name: "SELECT", tables: "All tables", reason: "Read data for analysis" },
    { name: "PROCESS", tables: "Global", reason: "View running queries" },
    { name: "SHOW DATABASES", tables: "Global", reason: "List databases" },
    {
      name: "performance_schema",
      tables: "System schema",
      reason: "Slow query analysis",
    },
  ],
};

// Database setup requirements for slow query analysis
export const SETUP_REQUIREMENTS = {
  postgres: {
    title: "PostgreSQL Setup for Slow Query Analysis",
    description: "Configure pg_stat_statements to enable slow query tracking",
    steps: [
      {
        title: "Enable the extension",
        sql: "CREATE EXTENSION IF NOT EXISTS pg_stat_statements;",
        note: "Run this in your target database",
      },
    ],
    serverParameters: [
      {
        name: "pg_stat_statements.track",
        value: "all",
        description: "Track all statements including those inside functions",
        required: true,
      },
      {
        name: "pg_stat_statements.track_planning",
        value: "on",
        description:
          "Track query planning time (helps identify expensive query plans)",
        required: false,
      },
    ],
    cloudInstructions: {
      azure: {
        title: "Azure Flexible Server",
        steps: [
          "Go to Azure Portal → Your PostgreSQL Server → Server parameters",
          "Set pg_stat_statements.track = all",
          "Set pg_stat_statements.track_planning = on (recommended)",
          "Save - changes apply without restart",
        ],
      },
      aws: {
        title: "AWS RDS / Aurora",
        steps: [
          "Go to AWS Console → RDS → Parameter Groups",
          "Modify your parameter group (or create a custom one)",
          "Set pg_stat_statements.track = all",
          "Set pg_stat_statements.track_planning = on (recommended)",
          "Apply changes (may require reboot for shared_preload_libraries)",
        ],
      },
      gcp: {
        title: "GCP Cloud SQL",
        steps: [
          "Go to Cloud Console → SQL → Your Instance → Configuration → Flags",
          "Add flag: cloudsql.enable_pg_stat_statements = on",
          "The extension is pre-loaded, just run CREATE EXTENSION",
        ],
      },
      "self-hosted": {
        title: "Self-Hosted PostgreSQL",
        steps: [
          "Add to postgresql.conf: shared_preload_libraries = 'pg_stat_statements'",
          "Add: pg_stat_statements.track = all",
          "Add: pg_stat_statements.track_planning = on",
          "Restart PostgreSQL for shared_preload_libraries to take effect",
        ],
      },
    },
    sslNote:
      'Azure PostgreSQL requires SSL. Select "Server-Only SSL" in the security step.',
  },
  mysql: {
    title: "MySQL Setup for Slow Query Analysis",
    description: "Configure Performance Schema for query tracking",
    steps: [
      {
        title: "Enable Performance Schema (usually enabled by default)",
        sql: "SHOW VARIABLES LIKE 'performance_schema';",
        note: "Should return ON",
      },
    ],
    serverParameters: [
      {
        name: "performance_schema",
        value: "ON",
        description: "Enable performance schema for query analysis",
        required: true,
      },
      {
        name: "slow_query_log",
        value: "ON",
        description: "Enable slow query logging",
        required: false,
      },
      {
        name: "long_query_time",
        value: "0.1",
        description: "Queries longer than this (in seconds) are logged as slow",
        required: false,
      },
    ],
    cloudInstructions: {
      aws: {
        title: "AWS RDS / Aurora MySQL",
        steps: [
          "Performance Schema is enabled by default on RDS",
          "For slow query log: modify parameter group",
          "Set slow_query_log = 1, long_query_time = 0.1",
        ],
      },
      azure: {
        title: "Azure MySQL Flexible Server",
        steps: [
          "Go to Server parameters",
          "Enable slow_query_log and set long_query_time",
          "Performance Schema is enabled by default",
        ],
      },
      gcp: {
        title: "GCP Cloud SQL MySQL",
        steps: [
          "Performance Schema is enabled by default",
          "Enable slow query log via database flags if needed",
        ],
      },
      "self-hosted": {
        title: "Self-Hosted MySQL",
        steps: [
          "Add to my.cnf: performance_schema = ON",
          "Add: slow_query_log = 1",
          "Add: long_query_time = 0.1",
          "Restart MySQL",
        ],
      },
    },
  },
};

// Connection categories
export const CONNECTION_CATEGORIES = ["database", "crm"];

// Default form data
export const DEFAULT_FORM_DATA = {
  // Step 0: Connection Category
  connectionCategory: "", // 'database' or 'crm'

  // Step 1: Database Type
  dbType: "",

  // Step 2: Cloud Provider
  cloudProvider: "",
  managedService: "",

  // Step 3: Connection Details
  connectionName: "",
  host: "",
  port: "",
  database: "",
  username: "",
  password: "",

  // Step 4: SSL Configuration
  sslMode: "none",
  sslCaCertificate: "",
  sslClientCertificate: "",
  sslClientKey: "",
  sslClientKeyPassphrase: "",

  // Step 5: Connectivity Method
  connectivityMethod: "direct",

  // SSH Tunnel (when connectivity is ssh-tunnel)
  sshEnabled: false,
  sshAuthType: "PASSWORD",
  sshHost: "",
  sshPort: 22,
  sshUsername: "",
  sshPassword: "",
  sshPrivateKey: "",
  sshPassphrase: "",

  // Instance specs (optional)
  instanceClass: "",
  instanceVcpus: "",
  instanceMemoryGb: "",
  storageType: "",
  storageMaxIops: "",

  // CRM-specific fields
  crmType: "", // e.g. 'HUBSPOT'
  crmConnectionName: "", // display name for the CRM connection
  crmCredentials: {}, // dynamic key/value from connector schema
  crmDatabaseConnectionId: "", // linked database connection ID
};

// Step definitions for database wizard (steps 1-5)
export const DB_WIZARD_STEPS = [
  { id: 1, title: "Database Type", description: "Select your database" },
  { id: 2, title: "Deployment", description: "Cloud provider (optional)" },
  { id: 3, title: "Connection", description: "Host and credentials" },
  { id: 4, title: "Security", description: "SSL configuration" },
  { id: 5, title: "Connectivity", description: "How to connect" },
];

// Step definitions for CRM wizard
export const CRM_WIZARD_STEPS = [
  { id: 1, title: "CRM Platform", description: "Select your CRM" },
  { id: 2, title: "Credentials", description: "Enter API credentials" },
];

// Category selection step (step 0 before branching)
export const CATEGORY_STEP = {
  id: 0,
  title: "Category",
  description: "Database or CRM",
};

// Legacy alias for backward compat in validators
export const WIZARD_STEPS = DB_WIZARD_STEPS;
