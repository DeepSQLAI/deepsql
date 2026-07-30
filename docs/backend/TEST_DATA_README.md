# Comprehensive MySQL Test Data for DBA Agent

This directory contains scripts to generate comprehensive test data for testing:
1. **Index Recommendations** - Tables with missing indexes
2. **Slow Query Detection** - Queries that will be slow due to missing indexes
3. **Partition Recommendations** - Large time-series tables suitable for partitioning
4. **Performance Analysis** - Various performance scenarios

## Files

- `comprehensive_test_data_mysql.sql` - SQL schema and data generation (uses MySQL stored procedures)
- `generate_test_data.py` - Python script to generate INSERT statements
- `test_data_setup.sql` - Original smaller test dataset

## Quick Start

### Option 1: Using Python Script (Recommended)

1. **Install Python dependencies** (if needed):
   ```bash
   # No external dependencies required - uses only standard library
   ```

2. **Generate the SQL file**:
   ```bash
   cd backend
   python3 generate_test_data.py > test_data_inserts.sql
   ```

3. **Load the schema first**:
   ```bash
   mysql -u root -p your_database < comprehensive_test_data_mysql.sql
   ```
   (This will create tables but may fail on the INSERT loops - that's expected)

4. **Load the generated data**:
   ```bash
   mysql -u root -p your_database < test_data_inserts.sql
   ```

### Option 2: Using MySQL Stored Procedures

1. **Load the SQL file directly**:
   ```bash
   mysql -u root -p your_database < comprehensive_test_data_mysql.sql
   ```
   
   Note: MySQL stored procedures may need adjustment based on your MySQL version.

### Option 3: Manual Loading (For Smaller Datasets)

You can modify the counts in `generate_test_data.py` to generate smaller datasets:

```python
# In generate_test_data.py, change:
generate_customers(10000)  # Change to 1000 for smaller dataset
generate_products(5000)    # Change to 500
# etc.
```

## Test Data Summary

| Table | Records | Purpose |
|-------|---------|---------|
| customers | 10,000 | Missing indexes on email, phone, country_code |
| products | 5,000 | Missing indexes on category_id, price, stock_quantity |
| orders | 50,000 | Missing indexes on customer_id, status, order_date |
| order_items | 150,000 | Missing composite index on (order_id, product_id) |
| sales_transactions | 200,000 | **Partition candidate** - time-series data |
| application_logs | 500,000 | **Partition candidate** - large log table |
| audit_trail | 100,000 | **Partition candidate** - audit data |
| daily_sales_summary | ~73,000 | Missing indexes on product_id, category_id |
| customer_activity | 200,000 | Missing indexes on customer_id, activity_type |
| product_reviews | 100,000 | Missing indexes on customer_id, rating |
| inventory_movements | 150,000 | Missing indexes on product_id, movement_type |

**Total: ~1.5 million+ records**

## Missing Indexes (For Index Recommendations)

### customers
- `email` - Frequently queried for login
- `phone` - Used for customer lookup
- `country_code` - Used for filtering by country
- `status` - Used for filtering active/inactive customers

### products
- `category_id` - Used in JOINs and WHERE clauses
- `brand_id` - Used for filtering by brand
- `price` - Used for price range queries
- `stock_quantity` - Used for low stock alerts
- `is_active` - Used to filter active products

### orders
- `customer_id` - Used in JOINs (no FK constraint)
- `order_date` - Used for date range queries
- `status` - Used to filter by order status
- `payment_status` - Used to filter by payment status

### order_items
- `product_id` - Used in JOINs
- Composite index on `(order_id, product_id)` - Used in JOINs

### sales_transactions (Partition Candidate)
- `customer_id` - Used in JOINs
- `product_id` - Used in JOINs
- `region` - Used for regional analysis
- `sales_rep_id` - Used for sales rep reports
- **Partition by**: `transaction_date` (monthly or yearly)

### application_logs (Partition Candidate)
- `user_id` - Used to filter by user
- `action_type` - Used to filter by action
- `endpoint` - Used for endpoint analysis
- `status_code` - Used to find errors
- `log_level` - Used to filter by severity
- **Partition by**: `created_at` (daily or monthly)

### audit_trail (Partition Candidate)
- `table_name` - Used to filter by table
- `action_type` - Used to filter by action
- `user_id` - Used to filter by user
- **Partition by**: `created_at` (monthly)

## Slow Query Examples

These queries will be slow due to missing indexes:

```sql
-- 1. Missing index on customer_id
SELECT * FROM orders WHERE customer_id = 1234;

-- 2. Missing index on status
SELECT * FROM orders WHERE status = 'pending';

-- 3. Missing indexes on category_id and price
SELECT * FROM products WHERE category_id = 5 AND price BETWEEN 100 AND 500;

-- 4. Missing index on email
SELECT * FROM customers WHERE email = 'customer1234@example.com';

-- 5. Missing index on stock_quantity
SELECT * FROM products WHERE stock_quantity < min_stock_level;

-- 6. Join without proper indexes
SELECT o.*, c.email 
FROM orders o 
JOIN customers c ON o.customer_id = c.customer_id 
WHERE c.country_code = 'US';

-- 7. Aggregate on non-indexed column
SELECT product_id, SUM(total_amount) 
FROM sales_transactions 
WHERE region = 'North' 
GROUP BY product_id;

-- 8. Date range on large unpartitioned table
SELECT * FROM application_logs 
WHERE created_at BETWEEN '2024-01-01' AND '2024-01-31' 
AND log_level = 'ERROR';

-- 9. Full table scan on reviews
SELECT * FROM product_reviews 
WHERE rating = 5 AND is_verified_purchase = TRUE;

-- 10. Complex join without indexes
SELECT p.product_name, COUNT(oi.item_id) as order_count 
FROM products p 
LEFT JOIN order_items oi ON p.product_id = oi.product_id 
WHERE p.category_id = 3 AND p.is_active = TRUE 
GROUP BY p.product_id 
ORDER BY order_count DESC;
```

## Partition Recommendations

### sales_transactions
- **Current size**: 200,000 rows
- **Partition key**: `transaction_date`
- **Recommended**: Monthly partitions
- **Benefit**: Faster queries on date ranges, easier archival

### application_logs
- **Current size**: 500,000 rows
- **Partition key**: `created_at`
- **Recommended**: Daily or monthly partitions
- **Benefit**: Faster queries, easier log rotation

### audit_trail
- **Current size**: 100,000 rows
- **Partition key**: `created_at`
- **Recommended**: Monthly partitions
- **Benefit**: Faster queries, easier archival of old audit data

## Testing Scenarios

### 1. Index Recommendations
- Run schema analysis on the database
- DBA Agent should recommend indexes on:
  - `customers.email`, `customers.phone`, `customers.country_code`
  - `products.category_id`, `products.price`, `products.stock_quantity`
  - `orders.customer_id`, `orders.status`, `orders.order_date`
  - And many more...

### 2. Slow Query Detection
- Execute the slow query examples above
- DBA Agent should detect these as slow queries
- Should recommend appropriate indexes

### 3. Partition Recommendations
- Analyze `sales_transactions`, `application_logs`, `audit_trail`
- DBA Agent should recommend partitioning by date columns
- Should suggest partition strategy (monthly/daily)

### 4. Performance Analysis
- Run EXPLAIN on the slow queries
- Check query execution plans
- Verify index usage

## Notes

- The data is generated with realistic patterns but is synthetic
- Dates are spread over the past 1-2 years
- Foreign key relationships are maintained where possible
- Some tables intentionally lack foreign keys to test FK recommendations
- Data volumes can be adjusted in `generate_test_data.py`

## Troubleshooting

### MySQL Error: "Unknown column"
- Make sure you've loaded the schema first (`comprehensive_test_data_mysql.sql`)

### Out of Memory
- Reduce the counts in `generate_test_data.py`
- Load data in smaller batches
- Use `mysql` command with `--max_allowed_packet=1G`

### Slow Data Loading
- Disable indexes temporarily: `ALTER TABLE table_name DISABLE KEYS;`
- Load data
- Re-enable indexes: `ALTER TABLE table_name ENABLE KEYS;`

### Stored Procedure Errors
- Use the Python script instead (`generate_test_data.py`)
- It generates standard INSERT statements without stored procedures
