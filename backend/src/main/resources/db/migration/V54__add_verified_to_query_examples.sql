-- Add verified flag to query_examples for auto-training poisoning prevention.
-- New auto-trained examples default to unverified; thumbs-up feedback promotes them.
ALTER TABLE query_examples ADD COLUMN verified BOOLEAN DEFAULT FALSE;
ALTER TABLE query_examples ADD COLUMN verified_at TIMESTAMP;

-- Index for efficient retrieval of verified-only training examples.
CREATE INDEX idx_query_examples_verified ON query_examples (connection_id, successful, verified);
