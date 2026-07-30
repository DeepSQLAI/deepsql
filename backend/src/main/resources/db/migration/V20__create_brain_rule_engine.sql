-- V20: Create Brain Rule Engine for User-Defined Hints
-- Implements BRAIN-design.md Section 3.4: "User-Defined Rules & Hints"

-- Create brain_rule table for domain knowledge and hints
CREATE TABLE IF NOT EXISTS brain_rule (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    connection_id VARCHAR(255) NOT NULL,
    rule_type VARCHAR(100) NOT NULL,  -- EXCLUDE_FROM_ANALYSIS, SOFT_FOREIGN_KEY, HIGH_PRIORITY, etc.
    table_name VARCHAR(255),
    column_name VARCHAR(255),
    rule_text TEXT NOT NULL,
    confidence INT DEFAULT 50 CHECK (confidence >= 0 AND confidence <= 100),
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(255),
    updated_at TIMESTAMP,
    updated_by VARCHAR(255)
);

-- Indexes for efficient querying
CREATE INDEX IF NOT EXISTS idx_brain_rule_connection ON brain_rule(connection_id);
CREATE INDEX IF NOT EXISTS idx_brain_rule_table ON brain_rule(connection_id, table_name);
CREATE INDEX IF NOT EXISTS idx_brain_rule_column ON brain_rule(connection_id, table_name, column_name);
CREATE INDEX IF NOT EXISTS idx_brain_rule_type ON brain_rule(connection_id, rule_type);
CREATE INDEX IF NOT EXISTS idx_brain_rule_active ON brain_rule(connection_id, is_active) WHERE is_active = true;

-- Comments
COMMENT ON TABLE brain_rule IS 'Stores user-defined rules and domain knowledge hints for Brain intelligence';
COMMENT ON COLUMN brain_rule.rule_type IS 'Type of rule: EXCLUDE_FROM_ANALYSIS, SOFT_FOREIGN_KEY, HIGH_PRIORITY, IMMUTABLE_TABLE, etc.';
COMMENT ON COLUMN brain_rule.rule_text IS 'Human-readable explanation of the rule';
COMMENT ON COLUMN brain_rule.confidence IS 'Confidence level 0-100 for rule application';
COMMENT ON COLUMN brain_rule.is_active IS 'Whether rule is currently active';

-- Sample rules for documentation
COMMENT ON TABLE brain_rule IS E'Example rules:\n'
    '- EXCLUDE_FROM_ANALYSIS: "Do not recommend indexes on this archive table"\n'
    '- SOFT_FOREIGN_KEY: "This email column is an implicit FK to users.email"\n'
    '- HIGH_PRIORITY: "This is a critical business dimension column"\n'
    '- IMMUTABLE_TABLE: "This is an append-only fact table"';
