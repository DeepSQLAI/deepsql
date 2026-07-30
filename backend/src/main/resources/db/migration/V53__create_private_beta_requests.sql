CREATE TABLE IF NOT EXISTS private_beta_requests (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    title VARCHAR(255) NOT NULL,
    company_name VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_private_beta_requests_email
    ON private_beta_requests(email);

CREATE INDEX IF NOT EXISTS idx_private_beta_requests_created_at
    ON private_beta_requests(created_at DESC);
