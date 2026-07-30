ALTER TABLE growth_anomalies
    DROP CONSTRAINT IF EXISTS growth_anomalies_anomaly_type_check;

ALTER TABLE growth_anomalies
    ADD CONSTRAINT growth_anomalies_anomaly_type_check
    CHECK (
        anomaly_type IN (
            'PERCENTAGE_GROWTH',
            'ABSOLUTE_GROWTH',
            'STATISTICAL_ANOMALY',
            'ROW_SPIKE',
            'NEW_TABLE'
        )
    );
