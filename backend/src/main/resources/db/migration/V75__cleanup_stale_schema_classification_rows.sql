WITH latest_schema_classification AS (
    SELECT connection_id, id
    FROM (
        SELECT
            connection_id,
            id,
            ROW_NUMBER() OVER (
                PARTITION BY connection_id
                ORDER BY classified_at DESC NULLS LAST, created_at DESC NULLS LAST, id DESC
            ) AS rn
        FROM schema_classification
    ) ranked
    WHERE rn = 1
)
DELETE FROM table_classification tc
USING schema_classification sc, latest_schema_classification latest
WHERE tc.schema_classification_id = sc.id
  AND sc.connection_id = latest.connection_id
  AND sc.id <> latest.id;

WITH latest_schema_classification AS (
    SELECT connection_id, id
    FROM (
        SELECT
            connection_id,
            id,
            ROW_NUMBER() OVER (
                PARTITION BY connection_id
                ORDER BY classified_at DESC NULLS LAST, created_at DESC NULLS LAST, id DESC
            ) AS rn
        FROM schema_classification
    ) ranked
    WHERE rn = 1
)
DELETE FROM table_relationship_classification trc
USING schema_classification sc, latest_schema_classification latest
WHERE trc.schema_classification_id = sc.id
  AND sc.connection_id = latest.connection_id
  AND sc.id <> latest.id;
