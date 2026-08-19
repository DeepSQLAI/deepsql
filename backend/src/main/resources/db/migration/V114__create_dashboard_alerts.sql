CREATE TABLE dashboard_alerts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    dashboard_id UUID NOT NULL REFERENCES saved_dashboards(id) ON DELETE CASCADE,
    connection_id VARCHAR(255) NOT NULL,
    created_by_username VARCHAR(255) NOT NULL,
    condition_text TEXT NOT NULL,
    channels VARCHAR(255) NOT NULL DEFAULT 'in-app',
    email_recipients VARCHAR(1000),
    webhook_url VARCHAR(1000),
    is_enabled BOOLEAN NOT NULL DEFAULT true,
    check_interval_minutes INT NOT NULL DEFAULT 15,
    cooldown_minutes INT NOT NULL DEFAULT 60,
    last_checked_at TIMESTAMP,
    last_fired_at TIMESTAMP,
    last_verdict VARCHAR(16),
    last_reason TEXT,
    last_error TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_dashboard_alerts_dashboard_id ON dashboard_alerts(dashboard_id);
CREATE INDEX idx_dashboard_alerts_due ON dashboard_alerts(is_enabled, last_checked_at);
