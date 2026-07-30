# Growth Monitoring Test Setup

This setup creates a test table that grows by **~1MB per hour** automatically, allowing you to test and demonstrate the Growth Monitoring features.

## 🛡️ Safety Features

**Built-in 1GB Limit**: The test script automatically stops adding data when the table reaches **1GB** to prevent accidental disk exhaustion. This limit is enforced in the stored procedure itself.

## 🚀 Quick Start

### 1. Setup (First Time Only)

```bash
cd /Users/geekypunk/sasank/stayflexi/dba-agent
./growth_test_control.sh setup
```

This will:
- Create table `GROWTH_TEST_TABLE`
- Create stored procedure `add_1mb_growth()`
- Create hourly event `hourly_growth_event`
- Enable MySQL event scheduler
- Insert initial 1MB of data

### 2. Verify It's Working

```bash
./growth_test_control.sh status
```

You should see:
```
📊 Table: GROWTH_TEST_TABLE
📏 Size: ~1.2 MB
📈 Rows: 1,000
🔢 Batches: 1
🕐 First Insert: 2026-01-11 16:30:00
🕐 Last Insert: 2026-01-11 16:30:00
```

### 3. Wait for Automatic Growth

The table will automatically grow by ~1MB **every hour** (at :00 of each hour).

OR

### 3. Simulate Faster Growth (For Testing)

Instead of waiting for hourly growth, simulate it immediately:

```bash
# Add 10MB of data over 5 minutes (10 batches × 30 seconds apart)
./growth_test_control.sh simulate 10 30

# Add 5MB quickly (5 batches × 5 seconds apart) - DEFAULT
./growth_test_control.sh simulate

# Add 20MB slowly (20 batches × 60 seconds apart)
./growth_test_control.sh simulate 20 60
```

## 📊 Monitoring

### Check Status Once

```bash
./growth_test_control.sh status
```

### Live Monitor (Auto-refresh every 10s)

```bash
./growth_test_control.sh monitor
```

Press `Ctrl+C` to stop monitoring.

## 🎮 Control Commands

### Manual Growth

Add 1MB immediately (without waiting for hourly event):

```bash
./growth_test_control.sh add
```

### Enable/Disable Automatic Growth

```bash
# Pause automatic growth
./growth_test_control.sh disable

# Resume automatic growth
./growth_test_control.sh enable
```

## 🧪 Testing Growth Monitoring in UI

### Step 1: Open Growth Monitoring Tab

1. Navigate to **Growth Monitoring** tab in the UI
2. Select your connection: `local`
3. Wait for tables to load (should see `GROWTH_TEST_TABLE` in the list)

### Step 2: Check Initial State

- **Row Count**: ~1,000 rows per batch
- **Total Size**: Will increase by ~1MB per batch
- **Growth Rate**: Should show **N/A** initially (needs 2+ snapshots)
- **Bloat %**: Will show after bloat is calculated

### Step 3: Trigger Growth and Snapshots

#### Option A: Wait for Hourly Snapshots (Realistic Testing)

Your connection is configured for **HOUR** cadence. Snapshots will be captured every hour at :00.

1. Wait for next hour mark (e.g., 5:00 PM, 6:00 PM)
2. Growth Monitoring backend will automatically capture snapshot
3. Table size increases by ~1MB each hour
4. After 2+ hours, Growth Rate column will show percentage growth

#### Option B: Simulate Rapid Growth (Fast Testing)

```bash
# Add 10MB of test data right now
./growth_test_control.sh simulate 10 30

# Manually trigger snapshot capture in UI
# Click "Force Refresh" button in Growth Monitoring tab
```

After the refresh, you should see:
- **Total Size**: Increased by ~10MB
- **Growth Rate**: Shows percentage growth (if multiple snapshots exist)
- **Growth Bytes**: Shows absolute growth in bytes

### Step 4: View Growth History

1. Click on `GROWTH_TEST_TABLE` row to select it
2. View the **Table Details** panel showing:
   - Historical size trend chart
   - Growth rate over time
   - Row count progression

## 📈 Expected Growth Pattern

### After 1 Hour
- **Size**: ~2 MB (1MB initial + 1MB growth)
- **Rows**: ~2,000
- **Growth Rate**: ~100% (doubled)

### After 6 Hours
- **Size**: ~7 MB
- **Rows**: ~7,000
- **Growth Rate**: ~16.7% per hour average

### After 24 Hours
- **Size**: ~25 MB
- **Rows**: ~25,000
- **Growth Rate**: ~4.3% per hour average

## 🔍 Verifying in Database

### Check Table Size

```sql
SELECT
    TABLE_NAME,
    ROUND((DATA_LENGTH + INDEX_LENGTH) / 1024 / 1024, 2) AS size_mb,
    TABLE_ROWS AS row_count,
    ROUND(DATA_LENGTH / 1024 / 1024, 2) AS data_mb,
    ROUND(INDEX_LENGTH / 1024 / 1024, 2) AS index_mb
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = 'idb_database'
  AND TABLE_NAME = 'GROWTH_TEST_TABLE';
```

### Check Growth History Snapshots

```sql
SELECT
    snapshotTimestamp,
    ROUND(sizeBytes / 1024 / 1024, 2) AS size_mb,
    rowCount,
    sizeGrowthPercent AS growth_pct,
    ROUND(sizeGrowthBytes / 1024 / 1024, 2) AS growth_mb
FROM table_stats_history
WHERE connectionId = '6be6ae30-ba7e-4887-b72e-1a95da01f926'
  AND tableName = 'GROWTH_TEST_TABLE'
ORDER BY snapshotTimestamp DESC
LIMIT 10;
```

### Check Event Status

```sql
SHOW EVENTS WHERE Name = 'hourly_growth_event';
```

## 🧹 Cleanup

When you're done testing and want to remove the test table:

```bash
./growth_test_control.sh cleanup
```

This will remove:
- Table: `GROWTH_TEST_TABLE`
- Procedure: `add_1mb_growth()`
- Event: `hourly_growth_event`

## ⚙️ How It Works

### Table Structure

- **5 VARCHAR(200) columns** = ~1,000 bytes per row
- **1,000 rows** = ~1 MB of data
- **InnoDB engine** with indexes for realistic overhead
- **AUTO_INCREMENT** primary key for easy tracking

### Stored Procedure

`add_1mb_growth()` inserts 1,000 rows with random data:
- Uses UUID and timestamp for uniqueness
- Includes batch tracking for easy monitoring
- Each call adds exactly ~1MB

### MySQL Event

`hourly_growth_event` runs automatically:
- **Schedule**: Every 1 hour
- **Action**: Calls `add_1mb_growth()`
- **Requires**: `event_scheduler = ON` (set automatically by setup)

## 🐛 Troubleshooting

### Event Not Running?

Check event scheduler status:
```sql
SHOW VARIABLES LIKE 'event_scheduler';
```

If it shows `OFF`, enable it:
```sql
SET GLOBAL event_scheduler = ON;
```

### No Growth Showing in UI?

1. **Check ResourceLimits**: Ensure connection has `collectionCadence` set (HOUR, MINUTE, or DAY)
2. **Check Snapshots**: Verify snapshots are being captured (check backend logs)
3. **Refresh Cache**: Click "Force Refresh" in Growth Monitoring tab
4. **Wait for Snapshots**: Need at least 2 snapshots to calculate growth

### Manual Snapshot Capture

Trigger a snapshot immediately:
```bash
curl -X POST http://localhost:8080/api/growth-monitoring/capture/6be6ae30-ba7e-4887-b72e-1a95da01f926
```

## 📝 Customization

### Change Size Limit

Edit `growth_test_setup.sql` and modify the stored procedure:
```sql
DECLARE max_size_mb DECIMAL(10,2) DEFAULT 1024.0;  -- Change 1024.0 to your desired limit in MB
```

For example:
- `512.0` = 512MB limit
- `2048.0` = 2GB limit
- `100.0` = 100MB limit (good for quick testing)

### Change Growth Rate

Edit `growth_test_setup.sql` and modify:
- `rows_to_insert`: Change from 1000 to increase/decrease size per batch
- Event schedule: Change from `EVERY 1 HOUR` to `EVERY 30 MINUTE`, etc.

### Change Data Pattern

Modify the `INSERT` statement in the procedure to use different data patterns or column sizes.

### Reset Table (Keep Structure)

To reset the table back to 0MB without recreating everything:

```bash
# Option 1: Via control script (safer - drops and recreates)
./growth_test_control.sh cleanup
./growth_test_control.sh setup

# Option 2: Via SQL (faster - just truncates data)
mysql -hlocalhost -uroot -p idb_database -e "TRUNCATE TABLE GROWTH_TEST_TABLE;"
```

## 🎯 Testing Scenarios

### Scenario 1: Steady Growth (Realistic)
```bash
# Setup with hourly automatic growth
./growth_test_control.sh setup
./growth_test_control.sh enable
# Wait 6-12 hours, observe linear growth trend
```

### Scenario 2: Rapid Growth Spike (Anomaly Testing)
```bash
# Normal growth for a while
./growth_test_control.sh setup
./growth_test_control.sh enable

# After a few hours, simulate a spike
./growth_test_control.sh simulate 50 10
# Should trigger anomaly detection (growth spike)
```

### Scenario 3: Controlled Growth (Demo)
```bash
# Setup but disable automatic
./growth_test_control.sh setup
./growth_test_control.sh disable

# Manually control growth for demo
./growth_test_control.sh add  # Add 1MB
# Trigger snapshot in UI
# Show growth
./growth_test_control.sh add  # Add another 1MB
# Trigger snapshot again
# Show updated growth
```

## 📞 Connection ID

Your connection ID for API calls:
```
6be6ae30-ba7e-4887-b72e-1a95da01f926
```

Use this in manual API calls or when configuring ResourceLimits.
