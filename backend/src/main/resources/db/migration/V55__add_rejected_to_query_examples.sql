-- Add rejected lifecycle fields to query_examples.
-- Rejected examples are explicitly downvoted and must not influence retrieval.

ALTER TABLE query_examples ADD COLUMN rejected BOOLEAN DEFAULT FALSE;
ALTER TABLE query_examples ADD COLUMN rejected_at TIMESTAMP;

UPDATE query_examples SET rejected = FALSE WHERE rejected IS NULL;

CREATE INDEX idx_query_examples_rejected ON query_examples (connection_id, successful, rejected, verified);
