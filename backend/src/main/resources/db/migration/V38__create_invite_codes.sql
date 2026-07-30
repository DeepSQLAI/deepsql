-- ============================================================================
-- V38: Invite Code System for Private Beta Signup
-- ============================================================================
-- Purpose: Create invite_codes table and add invite tracking to users table
-- ============================================================================

-- Create invite_codes table
CREATE TABLE IF NOT EXISTS invite_codes (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(20) NOT NULL UNIQUE,
    max_uses INTEGER DEFAULT 1,
    current_uses INTEGER DEFAULT 0,
    created_by VARCHAR(100),
    note TEXT,
    active BOOLEAN DEFAULT TRUE,
    expires_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Create indexes for efficient lookups
CREATE INDEX IF NOT EXISTS idx_invite_codes_code ON invite_codes(code);
CREATE INDEX IF NOT EXISTS idx_invite_codes_active ON invite_codes(active);
CREATE INDEX IF NOT EXISTS idx_invite_codes_created_by ON invite_codes(created_by);

-- Add invite tracking columns to users table
ALTER TABLE users ADD COLUMN IF NOT EXISTS invite_code_id BIGINT REFERENCES invite_codes(id);
ALTER TABLE users ADD COLUMN IF NOT EXISTS invited_at TIMESTAMP;

-- Create index for invite code lookups on users
CREATE INDEX IF NOT EXISTS idx_users_invite_code ON users(invite_code_id);
