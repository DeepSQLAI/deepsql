# 🚀 Quick Start - Growth Test Table

**Safety Feature**: Built-in 1GB limit to prevent disk exhaustion - growth stops automatically at 1GB.

## Step 1: Update Database Password

Edit the script with your MySQL password:

```bash
nano growth_test_control.sh
```

Find this line near the top and update `DB_PASS`:
```bash
DB_PASS="root"    # Change "root" to your actual MySQL password
```

Or set it as an environment variable:
```bash
export MYSQL_PASSWORD="your_password_here"
```

## Step 2: Run Setup

```bash
cd /Users/geekypunk/sasank/stayflexi/dba-agent
./growth_test_control.sh setup
```

## Step 3: Simulate Growth for Testing

Instead of waiting hours, add 10MB immediately:

```bash
./growth_test_control.sh simulate 10 30
```

This adds 10 batches (~10MB) with 30 seconds between each.

## Step 4: Trigger Snapshot in UI

1. Go to **Growth Monitoring** tab
2. Click **"Force Refresh"** button to clear cache
3. Wait for tables to reload
4. Look for `GROWTH_TEST_TABLE` in the list
5. You should see the growth data!

## Alternative: Manual SQL Setup

If the script doesn't work, run the SQL file directly:

```bash
mysql -hlocalhost -uroot -p idb_database < growth_test_setup.sql
```

Then manually trigger growth:
```bash
mysql -hlocalhost -uroot -p idb_database -e "CALL add_1mb_growth();"
```

## Check Status

```bash
./growth_test_control.sh status
```

Or in MySQL:
```sql
SELECT
    TABLE_NAME,
    ROUND((DATA_LENGTH + INDEX_LENGTH) / 1024 / 1024, 2) AS size_mb,
    TABLE_ROWS
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = 'idb_database'
  AND TABLE_NAME = 'GROWTH_TEST_TABLE';
```

## Full Documentation

See `GROWTH_TEST_README.md` for complete documentation.
