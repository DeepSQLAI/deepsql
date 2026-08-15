CREATE TABLE dashboard_versions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    dashboard_id UUID NOT NULL REFERENCES saved_dashboards(id) ON DELETE CASCADE,
    dashboard_config TEXT NOT NULL,
    name VARCHAR(255),
    trigger VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_dashboard_versions_dashboard_id ON dashboard_versions(dashboard_id, created_at DESC);
