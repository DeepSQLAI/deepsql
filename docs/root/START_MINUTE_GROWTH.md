# 🚀 Start Growth Monitoring (Every 5 Minutes)

## ✅ Configuration Complete!

Your growth test is now configured to add **1MB every 5 minutes**.

### Current Configuration

**MySQL Event (Data Growth):**
- Schedule: Every 5 MINUTES
- Status: ENABLED
- Starts: 2026-01-11 16:56:47
- Action: Calls `add_1mb_growth()` procedure

**Snapshot Collection:**
- Cadence: MINUTE (every minute)
- Status: ENABLED
- Backend captures snapshots every minute
- **Note**: Snapshots captured every minute, but table only grows every 5 minutes

**Safety:**
- Maximum size: 1GB (automatic stop)
- Current protection: Active

## 📊 What Happens Now

### Every Minute (at :00 seconds):
1. Backend captures snapshot → Records size, rows, bloat

### Every 5 Minutes (at :00, :05, :10, :15, etc.):
1. MySQL event triggers → Adds ~1MB to table
2. Next snapshot (at next minute) will show the growth

### Growth Rate:
- **1 MB per 5 minutes** = 12 MB/hour
- **After 15 minutes**: ~3 MB added (3 batches)
- **After 1 hour**: ~12 MB added (12 batches)
- **After ~5 hours**: Approaches 1GB limit (stops automatically)

## 🎯 Testing Growth Monitoring

### Option A: Wait and Watch (Automatic)

Just wait a few minutes and refresh the UI:

1. Wait **2-3 minutes** (to accumulate snapshots)
2. Go to **Growth Monitoring** tab
3. Click **"Force Refresh"** to clear cache
4. Look for `GROWTH_TEST_TABLE`
5. **Growth Rate** column will show percentage growth

### Option B: Accelerate Testing (Manual)

Add several batches immediately, then watch minute-by-minute growth:

```bash
# Add 5MB right now
./growth_test_control.sh simulate 5 1

# Wait for next minute mark (automatic snapshot)
# Then refresh UI to see the growth
```

## 📈 Expected Timeline

### Minute 0 (16:56)
- Table size: ~14 MB (from earlier testing)
- Event enabled, waiting for next 5-minute mark

### Minutes 1-4 (16:57-17:00)
- Snapshots captured every minute
- No growth (event hasn't triggered yet)
- Size stays at ~14 MB

### Minute 5 (17:01)
- Event triggers: +1 MB
- Next snapshot (17:02) shows: ~15 MB
- **Growth rate**: Shows growth from previous measurement

### Minutes 10, 15, 20... (Every 5 minutes)
- Event triggers: +1 MB each time
- Snapshots show growth every 5 minutes
- Growth rate stabilizes around 6-7% per 5-minute period

### Hour 5+ (~21:56+)
- Table approaches or reaches 1GB limit
- Event continues but insertions stop
- Growth rate: 0% (limit reached)

## 🔍 Monitor Progress

### Real-time Monitoring

```bash
# Live status (refreshes every 10 seconds)
./growth_test_control.sh monitor
```

### Check Size Now

```bash
./growth_test_control.sh status
```

### Backend Logs

Watch for snapshot collection:

```bash
tail -f /Users/geekypunk/sasank/stayflexi/dba-agent/backend/backend-output.log | grep -i "MINUTE"
```

You should see:
```
Starting MINUTE snapshot collection cycle...
Processing 1 connections with MINUTE cadence
✓ MINUTE collection cycle complete
```

## ⚠️ Important Notes

### Moderate Database Load
- **1MB per 5 minutes = 12MB/hour** is reasonable for testing
- Lower impact than every-minute growth
- Suitable for extended testing periods

### Disk Space
- Will consume up to **1GB** maximum (safety limit)
- Currently using: ~14 MB
- Remaining: ~1010 MB until limit
- **Time to limit**: ~5 hours (if continuing from current size)

### Stopping the Growth

```bash
# Pause automatic growth
./growth_test_control.sh disable

# Resume automatic growth
./growth_test_control.sh enable
```

## 🧹 When You're Done Testing

### Option 1: Just Disable (Keep Data)
```bash
./growth_test_control.sh disable
```

### Option 2: Change to Different Frequency

**Change to every 1 minute:**
```bash
mysql -hlocalhost -uroot -p'Welcome123$' idb_database -e "ALTER EVENT hourly_growth_event ON SCHEDULE EVERY 1 MINUTE;"
```

**Change to every hour:**
```bash
mysql -hlocalhost -uroot -p'Welcome123$' idb_database -e "ALTER EVENT hourly_growth_event ON SCHEDULE EVERY 1 HOUR;"

# Update snapshot cadence to match
curl -X POST http://localhost:8080/api/resource-limits -H "Content-Type: application/json" -d '{"connectionId":"6be6ae30-ba7e-4887-b72e-1a95da01f926","collectionCadence":"HOUR","isEnabled":true,"storageWarningPercent":75.0,"storageCriticalPercent":90.0}'
```

### Option 3: Complete Cleanup
```bash
./growth_test_control.sh cleanup
```

## 🎓 What You'll See in the UI

After 2-3 minutes, the Growth Monitoring UI will show:

**`GROWTH_TEST_TABLE` row:**
- **Total Size**: Increasing every 5 minutes
- **Growth Rate**: ~6-7% per 5-minute period
- **Growth Bytes**: ~1 MB per 5 minutes
- **Bloat %**: Will show if InnoDB has overhead
- **Row Count**: +1,000 rows every 5 minutes

**History Panel (if you select the table):**
- Line chart showing size increasing every 5 minutes
- Snapshots captured every minute (some with no change, some with +1MB)
- Clear upward trend in 5-minute intervals
- Growth rate visualization

## ✅ Verification

Check that everything is working:

```bash
# 1. Verify event is running every minute
mysql -hlocalhost -uroot -p'Welcome123$' idb_database -e "SHOW EVENTS WHERE Name = 'hourly_growth_event'\G" | grep -E "(Interval|Status)"

# 2. Check backend logs for minute snapshots
tail -20 /Users/geekypunk/sasank/stayflexi/dba-agent/backend/backend-output.log | grep "MINUTE"

# 3. Watch size increase
watch -n 10 './growth_test_control.sh status'
```

All set! Your table is now growing at **1MB every 5 minutes** and snapshots are being captured every minute for Growth Monitoring analysis.
