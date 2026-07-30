CREATE TABLE slack_digest_log (
    id                BIGSERIAL PRIMARY KEY,
    connection_id     VARCHAR(255),
    connection_name   VARCHAR(500),
    channel_id        VARCHAR(255),
    content           TEXT NOT NULL,
    headline          VARCHAR(500),
    sent_at           TIMESTAMP NOT NULL DEFAULT NOW(),
    status            VARCHAR(50) NOT NULL DEFAULT 'SENT',
    error_message     TEXT
);

CREATE INDEX idx_slack_digest_log_sent_at      ON slack_digest_log(sent_at DESC);
CREATE INDEX idx_slack_digest_log_connection   ON slack_digest_log(connection_id, sent_at DESC);

CREATE TABLE slack_digest_config (
    id              BIGINT PRIMARY KEY DEFAULT 1,
    cron_expression VARCHAR(100) NOT NULL DEFAULT '0 0 9 * * *',
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

INSERT INTO slack_digest_config (id, cron_expression, updated_at)
VALUES (1, '0 0 9 * * *', NOW())
ON CONFLICT (id) DO NOTHING;
