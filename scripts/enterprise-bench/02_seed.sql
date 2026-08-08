-- Seed volumes sized for a meaningful enterprise-ish workload without multi-hour load.
-- Counts: accounts/customers 15k, products 2k, orders 80k, lines ~240k, invoices 60k,
-- payments 45k, stock moves 40k, employees 1.5k, payroll 8k.

-- CRM accounts
INSERT INTO crm.accounts (account_number, name, status, tier, email, phone, country_code, created_at, is_deleted)
SELECT
    'ACC-' || lpad(g::text, 6, '0'),
    'Account ' || g,
    (ARRAY['PROSPECT','ACTIVE','ACTIVE','ACTIVE','CHURNED'])[1 + (g % 5)],
    (ARRAY['SMB','SMB','MID','ENTERPRISE'])[1 + (g % 4)],
    'account' || g || '@example.com',
    '+1-555-' || lpad((g % 10000)::text, 4, '0'),
    (ARRAY['US','GB','DE','IN','SG','CA','AU'])[1 + (g % 7)],
    NOW() - ((g % 1200) || ' days')::INTERVAL,
    (g % 47 = 0)
FROM generate_series(1, 15000) g;

INSERT INTO crm.contacts (account_id, name, email, role_title, status, created_at)
SELECT
    1 + (g % 15000),
    'Contact ' || g,
    'contact' || g || '@example.com',
    (ARRAY['Buyer','CFO','Ops','Engineer','Owner'])[1 + (g % 5)],
    (ARRAY['ACTIVE','ACTIVE','INACTIVE'])[1 + (g % 3)],
    NOW() - ((g % 800) || ' days')::INTERVAL
FROM generate_series(1, 30000) g;

-- Sales customers — mostly mirrored from CRM, with intentional drift
INSERT INTO sales.customers (crm_account_id, customer_code, name, status, email, ssn_last4, country_code, created_at)
SELECT
    CASE WHEN g % 11 = 0 THEN NULL ELSE g END,
    'CUST-' || lpad(g::text, 6, '0'),
    'Customer ' || g,
    (ARRAY['ACTIVE','ACTIVE','ACTIVE','INACTIVE','BLOCKED'])[1 + (g % 5)],
    'customer' || g || '@example.com',
    lpad((g % 10000)::text, 4, '0'),
    (ARRAY['US','GB','DE','IN','SG','CA','AU'])[1 + (g % 7)],
    NOW() - ((g % 1100) || ' days')::INTERVAL
FROM generate_series(1, 15000) g;

INSERT INTO sales.products (sku, name, status, unit_price, category, created_at)
SELECT
    'SKU-' || lpad(g::text, 5, '0'),
    'Product ' || g,
    (ARRAY['ACTIVE','ACTIVE','ACTIVE','DISCONTINUED'])[1 + (g % 4)],
    ROUND((5 + (g * 17) % 2500)::NUMERIC, 2),
    (ARRAY['Hardware','Software','Services','Consumables','Spare'])[1 + (g % 5)],
    NOW() - ((g % 900) || ' days')::INTERVAL
FROM generate_series(1, 2000) g;

INSERT INTO sales.orders (customer_id, order_number, status, channel, placed_at, shipped_at, total_amount, currency, is_test)
SELECT
    1 + (g % 15000),
    'ORD-' || lpad(g::text, 7, '0'),
    (ARRAY['PLACED','SHIPPED','DELIVERED','DELIVERED','DELIVERED','CANCELLED','RETURNED'])[1 + (g % 7)],
    (ARRAY['WEB','WEB','STORE','PARTNER'])[1 + (g % 4)],
    NOW() - ((g % 540) || ' days')::INTERVAL - ((g % 24) || ' hours')::INTERVAL,
    CASE WHEN g % 7 IN (1,2,3,4) THEN NOW() - ((g % 500) || ' days')::INTERVAL ELSE NULL END,
    ROUND((40 + (g * 23) % 9000)::NUMERIC, 2),
    (ARRAY['USD','USD','USD','EUR','GBP'])[1 + (g % 5)],
    (g % 211 = 0)
FROM generate_series(1, 80000) g;

INSERT INTO sales.order_lines (order_id, product_id, quantity, unit_price, line_amount, status)
SELECT
    1 + (g % 80000),
    1 + (g % 2000),
    1 + (g % 8),
    ROUND((5 + (g * 13) % 900)::NUMERIC, 2),
    ROUND(((1 + (g % 8)) * (5 + (g * 13) % 900))::NUMERIC, 2),
    (ARRAY['OPEN','FULFILLED','FULFILLED','CANCELLED'])[1 + (g % 4)]
FROM generate_series(1, 240000) g;

-- Legacy header mirror (subset)
INSERT INTO sales.order_header (cust_id, hdr_status, order_dt, amount)
SELECT customer_id,
       CASE status WHEN 'DELIVERED' THEN 'C' WHEN 'CANCELLED' THEN 'X' ELSE 'O' END,
       placed_at::date,
       total_amount
FROM sales.orders
WHERE id % 3 = 0;

INSERT INTO finance.invoices (invoice_number, customer_id, order_id, status, amount, tax_amount, issued_at, due_at, paid_at)
SELECT
    'INV-' || lpad(g::text, 7, '0'),
    1 + (g % 15000),
    CASE WHEN g % 5 = 0 THEN NULL ELSE 1 + (g % 80000) END,
    (ARRAY['DRAFT','OPEN','OPEN','PAID','PAID','PAID','VOID','DISPUTED'])[1 + (g % 8)],
    ROUND((50 + (g * 29) % 12000)::NUMERIC, 2),
    ROUND((5 + (g * 3) % 900)::NUMERIC, 2),
    NOW() - ((g % 500) || ' days')::INTERVAL,
    NOW() - ((g % 500) || ' days')::INTERVAL + INTERVAL '30 days',
    CASE WHEN g % 8 IN (3,4,5) THEN NOW() - ((g % 400) || ' days')::INTERVAL ELSE NULL END
FROM generate_series(1, 60000) g;

INSERT INTO finance.payments (payment_number, invoice_id, amount, status, method, paid_at)
SELECT
    'PAY-' || lpad(g::text, 7, '0'),
    1 + (g % 60000),
    ROUND((30 + (g * 19) % 8000)::NUMERIC, 2),
    (ARRAY['PENDING','CLEARED','CLEARED','CLEARED','FAILED','REVERSED'])[1 + (g % 6)],
    (ARRAY['CARD','ACH','WIRE','CHECK'])[1 + (g % 4)],
    NOW() - ((g % 450) || ' days')::INTERVAL
FROM generate_series(1, 45000) g;

INSERT INTO finance.payment_orders (vendor_name, status, amount, scheduled_at, completed_at)
SELECT
    'Vendor ' || (1 + (g % 400)),
    (ARRAY['SCHEDULED','SENT','COMPLETED','COMPLETED','CANCELLED'])[1 + (g % 5)],
    ROUND((100 + (g * 41) % 50000)::NUMERIC, 2),
    NOW() - ((g % 200) || ' days')::INTERVAL,
    CASE WHEN g % 5 IN (2,3) THEN NOW() - ((g % 180) || ' days')::INTERVAL ELSE NULL END
FROM generate_series(1, 8000) g;

INSERT INTO finance.gl_entries (account_code, name, status, amount, posted_at, cost_center)
SELECT
    'GL-' || lpad((1 + (g % 200))::text, 4, '0'),
    'Entry ' || g,
    (ARRAY['POSTED','POSTED','PENDING','REVERSED'])[1 + (g % 4)],
    ROUND(((g % 2) * 2 - 1) * (10 + (g * 7) % 20000)::NUMERIC, 2),
    NOW() - ((g % 365) || ' days')::INTERVAL,
    (ARRAY['CC-100','CC-200','CC-300','CC-400'])[1 + (g % 4)]
FROM generate_series(1, 25000) g;

INSERT INTO inventory.items (item_code, name, status, sku_ref, unit_cost, reorder_point, created_at)
SELECT
    'ITM-' || lpad(g::text, 5, '0'),
    'Item ' || g,
    (ARRAY['ACTIVE','ACTIVE','HOLD','OBSOLETE'])[1 + (g % 4)],
    CASE WHEN g <= 2000 THEN 'SKU-' || lpad(g::text, 5, '0') ELSE NULL END,
    ROUND((2 + (g * 11) % 800)::NUMERIC, 2),
    5 + (g % 40),
    NOW() - ((g % 700) || ' days')::INTERVAL
FROM generate_series(1, 4000) g;

INSERT INTO inventory.warehouses (code, name, status, region)
SELECT
    'WH-' || g,
    'Warehouse ' || g,
    'ACTIVE',
    (ARRAY['US-EAST','US-WEST','EU','APAC'])[1 + (g % 4)]
FROM generate_series(1, 12) g;

INSERT INTO inventory.stock_moves (item_id, warehouse_id, status, quantity, move_type, moved_at, ref_order_id)
SELECT
    1 + (g % 4000),
    1 + (g % 12),
    (ARRAY['PENDING','COMPLETE','COMPLETE','CANCELLED'])[1 + (g % 4)],
    1 + (g % 50),
    (ARRAY['IN','OUT','OUT','ADJUST'])[1 + (g % 4)],
    NOW() - ((g % 400) || ' days')::INTERVAL,
    CASE WHEN g % 4 = 0 THEN 1 + (g % 80000) ELSE NULL END
FROM generate_series(1, 40000) g;

INSERT INTO inventory.product_master (name, status, amount, created_at)
SELECT
    'Master Product ' || g,
    (ARRAY['ACTIVE','ACTIVE','INACTIVE'])[1 + (g % 3)],
    ROUND((10 + (g * 9) % 1500)::NUMERIC, 2),
    NOW() - ((g % 600) || ' days')::INTERVAL
FROM generate_series(1, 2500) g;

INSERT INTO hr.departments (name, status, cost_center)
VALUES
    ('Engineering','ACTIVE','CC-100'),
    ('Sales','ACTIVE','CC-200'),
    ('Finance','ACTIVE','CC-300'),
    ('HR','ACTIVE','CC-400'),
    ('Operations','ACTIVE','CC-200'),
    ('Support','ACTIVE','CC-100');

INSERT INTO hr.employees (employee_number, name, email, status, department, title, manager_id, hire_date, salary, ssn, created_at)
SELECT
    'EMP-' || lpad(g::text, 5, '0'),
    'Employee ' || g,
    'employee' || g || '@acme.example',
    (ARRAY['ACTIVE','ACTIVE','ACTIVE','LEAVE','TERMINATED'])[1 + (g % 5)],
    (ARRAY['Engineering','Sales','Finance','HR','Operations','Support'])[1 + (g % 6)],
    (ARRAY['IC','Senior','Manager','Director','VP'])[1 + (g % 5)],
    CASE WHEN g > 20 THEN 1 + (g % 20) ELSE NULL END,
    (CURRENT_DATE - ((g % 4000) || ' days')::INTERVAL)::date,
    ROUND((45000 + (g * 137) % 160000)::NUMERIC, 2),
    lpad((100 + (g % 900))::text, 3, '0') || '-' || lpad((10 + (g % 90))::text, 2, '0') || '-' || lpad((1000 + (g % 9000))::text, 4, '0'),
    NOW() - ((g % 2000) || ' days')::INTERVAL
FROM generate_series(1, 1500) g;

INSERT INTO hr.payroll_runs (employee_id, status, amount, period_start, period_end, paid_at)
SELECT
    1 + (g % 1500),
    (ARRAY['DRAFT','APPROVED','PAID','PAID','VOID'])[1 + (g % 5)],
    ROUND((2000 + (g * 53) % 12000)::NUMERIC, 2),
    DATE '2024-01-01' + ((g % 24) * 14),
    DATE '2024-01-01' + ((g % 24) * 14) + 13,
    CASE WHEN g % 5 IN (2,3) THEN NOW() - ((g % 300) || ' days')::INTERVAL ELSE NULL END
FROM generate_series(1, 8000) g;

ANALYZE;
