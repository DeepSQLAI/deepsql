-- Add bloat tracking columns to table_stats_history
-- Bloat represents the difference between allocated disk space and actual data size

ALTER TABLE table_stats_history
ADD COLUMN allocated_bytes BIGINT COMMENT 'Total allocated disk space (extents)',
ADD COLUMN bloat_bytes BIGINT COMMENT 'Difference between allocated and logical size (wasted space)',
ADD COLUMN bloat_percent DOUBLE COMMENT 'Bloat as percentage of allocated space',
ADD INDEX idx_bloat_percent (bloat_percent DESC);
