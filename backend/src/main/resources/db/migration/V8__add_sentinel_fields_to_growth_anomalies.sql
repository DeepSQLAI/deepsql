-- =====================================================
-- V8: Add Sentinel-DBA Analytics Fields to GrowthAnomaly
-- Purpose: Enhance anomaly detection with confidence scoring and event attribution
-- Date: 2025-12-26
-- =====================================================

ALTER TABLE growth_anomalies
ADD COLUMN confidence_score INTEGER,
ADD COLUMN attributed_event_id VARCHAR(36),
ADD COLUMN attributed_event_description TEXT,
ADD COLUMN growth_pattern VARCHAR(50),
ADD COLUMN forecast_id VARCHAR(36);

-- Add indexes for new fields
CREATE INDEX idx_anomaly_confidence ON growth_anomalies(confidence_score);
CREATE INDEX idx_anomaly_event ON growth_anomalies(attributed_event_id);
CREATE INDEX idx_anomaly_forecast ON growth_anomalies(forecast_id);
CREATE INDEX idx_anomaly_pattern ON growth_anomalies(growth_pattern);

-- Add foreign key constraint to link to capacity_forecasts (if needed)
-- Note: Using soft reference (no FK constraint) to avoid circular dependencies

COMMENT ON COLUMN growth_anomalies.confidence_score IS 'Sentinel confidence score (1-10) in this anomaly detection';
COMMENT ON COLUMN growth_anomalies.attributed_event_id IS 'Links to database_events table for root cause attribution';
COMMENT ON COLUMN growth_anomalies.growth_pattern IS 'Growth pattern classification: LINEAR, EXPONENTIAL, STEP_FUNCTION, etc.';
COMMENT ON COLUMN growth_anomalies.forecast_id IS 'Links to capacity_forecasts table';
