# Live Demo: Enhanced Key Columns Analysis

## Step-by-Step Demo Guide

### Step 1: Access the Application
```
URL: http://localhost:3000
Navigate to: Brain Tab → Key Columns Analysis
```

### Step 2: Create Test Scenario

Let me create a realistic test scenario that demonstrates all 6 enhancements:

**Scenario:** E-commerce database with 3 tables
- `users` (10,000 rows)
- `orders` (50,000 rows)
- `products` (5,000 rows)

**Query Patterns Over 30 Days:**
- JOIN-heavy queries (user-order relationships)
- WHERE-heavy queries (product searches)
- GROUP BY queries (analytics)
- Recent vs old query patterns
- High vs low cardinality columns

### Step 3: Expected Results Walkthrough

#### Column #1: users.id (HIGH PRIORITY)
```yaml
Column: users.id
Table: users

Base Metrics:
  importanceScore: 85.0
  joinCount: 25
  whereCount: 10
  groupByCount: 0
  orderByCount: 2

Enhanced Metrics:
  enhancedImportanceScore: 127.8  # ⬆️ +50% boost from selectivity
  mlPredictionScore: 88.0         # 🎯 High performance impact

Selectivity Analysis (Oracle-style):
  selectivity: 1.0 (100%)          # ✨ Perfect for indexing
  distinctCount: 10,000
  totalRows: 10,000
  cardinalityRatio: 1.0

Frequency & Recency:
  frequencyScore: 35.0
  usesPerDay: 3.5                  # 📊 Frequently used
  recencyScore: 92.3               # 🕐 Recently active
  lastUsedAt: 2 hours ago

Index Status:
  isIndexed: true                  # ✅ Already indexed
  indexName: "users_pkey"
  indexScanCount: 1,234            # 📈 Heavily used
  hasUnusedIndex: false

Anti-Patterns:
  hasAntiPatterns: false           # ✅ No issues

Recommendation:
  "Excellent candidate - already optimized"
  "Primary key with perfect selectivity"
```

#### Column #2: orders.user_id (CRITICAL)
```yaml
Column: orders.user_id
Table: orders

Base Metrics:
  importanceScore: 82.0
  joinCount: 20                    # 🔗 Heavy JOIN usage
  whereCount: 8
  groupByCount: 0
  orderByCount: 0

Enhanced Metrics:
  enhancedImportanceScore: 106.6  # ⬆️ +30% boost from selectivity
  mlPredictionScore: 75.0          # 🎯 High impact

Selectivity Analysis:
  selectivity: 0.8 (80%)           # ✨ High cardinality
  distinctCount: 40,000
  totalRows: 50,000
  cardinalityRatio: 0.8

Frequency & Recency:
  frequencyScore: 28.0
  usesPerDay: 2.8
  recencyScore: 80.5

Index Status:
  isIndexed: false                 # ❌ NOT INDEXED!
  indexName: null
  indexScanCount: 0
  hasUnusedIndex: false

Anti-Patterns:
  hasAntiPatterns: true            # ⚠️ CRITICAL ISSUE
  antiPatterns:
    - type: "UNINDEXED_JOIN"
      severity: "HIGH"
      title: "Column frequently used in JOINs"
      description: "Column 'orders.user_id' used in 20 JOIN operations but not indexed"
      recommendation: "CREATE INDEX idx_orders_user_id ON orders(user_id);"
      affectedQueriesCount: 20

Composite Index Opportunity:
  suggestedIndex: "CREATE INDEX idx_orders_user_id_created_at ON orders(user_id, created_at);"
  coOccurringColumns: ["user_id", "created_at", "status"]
  estimatedBenefit: 75.0

Recommendation:
  "🔴 CRITICAL: Create index immediately"
  "Expected performance gain: 3-5x on JOIN queries"
  "Impact: High (affects 20 queries)"
```

#### Column #3: products.category (MEDIUM PRIORITY)
```yaml
Column: products.category
Table: products

Base Metrics:
  importanceScore: 45.0
  joinCount: 0
  whereCount: 12                   # 🔍 Filtering column
  groupByCount: 5                  # 📊 Analytics column
  orderByCount: 0

Enhanced Metrics:
  enhancedImportanceScore: 48.6   # ⬆️ Small boost
  mlPredictionScore: 52.0          # 🎯 Medium impact

Selectivity Analysis:
  selectivity: 0.005 (0.5%)        # ⚠️ Low cardinality
  distinctCount: 25
  totalRows: 5,000
  cardinalityRatio: 0.005

Frequency & Recency:
  frequencyScore: 18.0
  usesPerDay: 1.8
  recencyScore: 43.2

Index Status:
  isIndexed: false
  indexName: null

Anti-Patterns:
  hasAntiPatterns: true
  antiPatterns:
    - type: "LOW_CARDINALITY_FILTER"
      severity: "MEDIUM"
      title: "Low selectivity column in WHERE"
      description: "Only 25 distinct values in 5,000 rows (0.5%)"
      recommendation: "Consider bitmap index or summary tables"

Recommendation:
  "⚠️ MEDIUM: Traditional index may not help"
  "Alternative: Use covering index with more selective columns"
  "Or: Create summary table grouped by category"
```

#### Column #4: orders.status (LOW PRIORITY - PENALTY)
```yaml
Column: orders.status
Table: orders

Base Metrics:
  importanceScore: 40.0
  joinCount: 0
  whereCount: 12
  groupByCount: 3                  # 📊 Used in GROUP BY
  orderByCount: 0

Enhanced Metrics:
  enhancedImportanceScore: 32.0   # ⬇️ -20% PENALTY (low selectivity + GROUP BY)
  mlPredictionScore: 45.0

Selectivity Analysis:
  selectivity: 0.0001 (0.01%)      # 🔴 Very low cardinality
  distinctCount: 5                 # Only 5 statuses!
  totalRows: 50,000
  cardinalityRatio: 0.0001

Frequency & Recency:
  frequencyScore: 15.0
  usesPerDay: 1.5
  recencyScore: 38.5

Anti-Patterns:
  hasAntiPatterns: true
  antiPatterns:
    - type: "LOW_CARDINALITY_GROUP_BY"
      severity: "MEDIUM"
      title: "Low cardinality column in GROUP BY"
      description: "Only 5 distinct values - poor GROUP BY candidate"
      recommendation: "Consider materialized view or summary table"

Recommendation:
  "⬇️ Index NOT recommended"
  "Better: Cache query results or use summary table"
  "GROUP BY on 5 values is inefficient"
```

#### Column #5: users.last_login (RECENCY DEMO)
```yaml
Column: users.last_login
Table: users

Base Metrics:
  importanceScore: 55.0
  joinCount: 0
  whereCount: 6
  groupByCount: 0
  orderByCount: 8                  # 🔽 Sorting column

Enhanced Metrics:
  enhancedImportanceScore: 71.5   # ⬆️ Boosted by recency
  mlPredictionScore: 60.0

Selectivity Analysis:
  selectivity: 0.85 (85%)          # ✨ High cardinality
  distinctCount: 8,500
  totalRows: 10,000

Frequency & Recency:
  frequencyScore: 22.0
  usesPerDay: 2.2
  recencyScore: 53.5               # 🕐 Used recently
  lastUsedAt: 1 hour ago           # 🔥 HOT column

Recommendation:
  "🔥 TRENDING: Recently active queries"
  "Consider index for ORDER BY optimization"
```

### Step 4: Visual Comparison

#### Before Enhancement:
```
┌──────────────┬──────────┬───────┬────────┐
│ Column       │ Score    │ JOINs │ WHEREs │
├──────────────┼──────────┼───────┼────────┤
│ users.id     │ 85.0     │ 25    │ 10     │
│ orders.uid   │ 82.0     │ 20    │ 8      │
│ products.cat │ 45.0     │ 0     │ 12     │
└──────────────┴──────────┴───────┴────────┘

Insights: "user_id is important"
Action: Manual investigation needed
```

#### After Enhancement:
```
┌──────────────┬──────┬─────┬────────┬───────┬──────────┬────────────────────┐
│ Column       │ Enh  │ ML  │ Select │ JOIN  │ Freq/Day │ Recommendation     │
├──────────────┼──────┼─────┼────────┼───────┼──────────┼────────────────────┤
│ users.id     │ 127↑ │ 88  │ 100%✓  │ 25    │ 3.5      │ ✅ Optimized      │
│ orders.uid   │ 106↑ │ 75  │ 80%✓   │ 20    │ 2.8      │ 🔴 CREATE INDEX!  │
│ products.cat │ 48   │ 52  │ 0.5%⚠  │ 0     │ 1.8      │ ⚠️ Bitmap index   │
│ orders.stat  │ 32↓  │ 45  │ 0.01%✗ │ 0     │ 1.5      │ ⬇️ NO INDEX       │
│ users.login  │ 71↑  │ 60  │ 85%✓   │ 0     │ 2.2🔥    │ 🔥 HOT - Index    │
└──────────────┴──────┴─────┴────────┴───────┴──────────┴────────────────────┘

Insights:
  ✅ 1 column optimized
  🔴 1 critical issue (orders.user_id)
  ⚠️ 1 needs special handling (products.category)
  ⬇️ 1 NOT recommended for index (orders.status)
  🔥 1 trending (users.last_login)

Actions:
  1. CREATE INDEX idx_orders_user_id ON orders(user_id);
  2. CREATE INDEX idx_orders_user_id_created_at ON orders(user_id, created_at);
  3. CREATE INDEX idx_users_last_login ON users(last_login);
  4. Consider bitmap index for products.category

Expected Impact:
  - Query performance: 3-5x improvement on JOINs
  - Index scans instead of full table scans
  - Reduced CPU and I/O load
```

### Step 5: Expandable Row Details

When you click on a row, you see:

```
┌─────────────────────────────────────────────────────────────────┐
│ 🔍 Detailed Analysis: orders.user_id                           │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│ 📊 Enhanced Metrics                                            │
│ ├─ Frequency: 28.0 (2.8 uses/day)                             │
│ ├─ Recency Score: 80.5                                         │
│ ├─ Selectivity: 80.00% (40,000 / 50,000)                      │
│ └─ Index: None (0 scans) ❌                                    │
│                                                                 │
│ ⚠️ Anti-Pattern: UNINDEXED_JOIN                                │
│ ├─ Severity: HIGH                                              │
│ ├─ Description: Column used in 20 JOIN operations but not     │
│ │   indexed. This causes full table scans on every join.      │
│ ├─ Affected Queries: 20                                        │
│ └─ Recommendation:                                             │
│     CREATE INDEX idx_orders_user_id ON orders(user_id);       │
│                                                                 │
│ 💡 Composite Index Opportunity                                 │
│     CREATE INDEX idx_orders_user_id_created_at                │
│       ON orders(user_id, created_at);                          │
│     Reason: These columns appear together in 15 queries       │
│     Estimated benefit: 75% performance improvement             │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### Step 6: Summary Statistics

At the top of the panel:

```
┌──────────────────────────────────────────────────────────────┐
│ Key Columns Analysis Summary                                 │
├──────────────────────────────────────────────────────────────┤
│ Columns Analyzed: 15                                         │
│ Anti-patterns Detected: 8                                    │
│ Queries Analyzed: 256                                        │
│ Lookback: 90 days                                            │
│ Last Analysis: 2 minutes ago                                 │
├──────────────────────────────────────────────────────────────┤
│ Quick Actions:                                               │
│ [Analyze Now] [Export CSV] [View Composite Recommendations] │
└──────────────────────────────────────────────────────────────┘
```

### Step 7: Color Coding Legend

```
Enhanced Score:
  🟢 Green (≥70):  High priority - excellent indexing candidate
  🟡 Yellow (40-69): Medium priority - evaluate case by case
  🔴 Red (<40):     Low priority or not recommended

ML Prediction Score:
  🎯 70-100:  High performance impact expected
  🎯 40-69:   Medium performance impact
  🎯 0-39:    Low performance impact

Selectivity:
  ✨ Green (>50%):   High cardinality - excellent for indexing
  ⚠️ Yellow (10-50%): Medium cardinality - may help
  🔴 Red (<10%):     Low cardinality - index may not help

Recency:
  🔥 Used in last hour:   HOT
  🕐 Used in last day:    Recent
  📅 Used in last week:   Active
  ❄️ Used >30 days ago:  Cold
```

