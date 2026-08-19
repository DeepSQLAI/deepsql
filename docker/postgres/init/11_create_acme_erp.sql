-- ACME ERP — multi-schema fixture for Brain, MCP, and chat-access-policy tests.
-- Schemas: crm, sales, finance, inventory, hr, marts (analytics mart).
-- Duplicate bare table names (customers, orders) across crm/sales on purpose.

SELECT 'Creating acme_erp multi-schema database' AS status;

DROP DATABASE IF EXISTS acme_erp;
CREATE DATABASE acme_erp;

\connect acme_erp

CREATE EXTENSION IF NOT EXISTS pg_stat_statements;

CREATE SCHEMA crm;
CREATE SCHEMA sales;
CREATE SCHEMA finance;
CREATE SCHEMA inventory;
CREATE SCHEMA hr;
CREATE SCHEMA marts;

-- CRM
CREATE TABLE crm.customers (
    id SERIAL PRIMARY KEY,
    name TEXT NOT NULL,
    email TEXT,
    amount NUMERIC(12, 2) DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE crm.accounts (
    id SERIAL PRIMARY KEY,
    customer_id INT REFERENCES crm.customers(id),
    balance NUMERIC(14, 2) NOT NULL DEFAULT 0
);

-- Sales (same bare names as crm)
CREATE TABLE sales.customers (
    id SERIAL PRIMARY KEY,
    name TEXT NOT NULL,
    email TEXT,
    revenue NUMERIC(12, 2) DEFAULT 0
);

CREATE TABLE sales.orders (
    id SERIAL PRIMARY KEY,
    customer_id INT REFERENCES sales.customers(id),
    amount NUMERIC(12, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    status TEXT NOT NULL DEFAULT 'open',
    ordered_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Finance
CREATE TABLE finance.ledger (
    id SERIAL PRIMARY KEY,
    account_code TEXT NOT NULL,
    amount NUMERIC(14, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    posted_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Inventory
CREATE TABLE inventory.products (
    id SERIAL PRIMARY KEY,
    sku TEXT UNIQUE NOT NULL,
    name TEXT NOT NULL,
    stock_qty INT NOT NULL DEFAULT 0,
    unit_cost NUMERIC(10, 2)
);

-- HR
CREATE TABLE hr.employees (
    id SERIAL PRIMARY KEY,
    full_name TEXT NOT NULL,
    email TEXT,
    salary NUMERIC(12, 2),
    department TEXT
);

-- Marts — policy-test schema (numeric amount + string currency, like production marts)
CREATE TABLE marts.fct_enrollment (
    id SERIAL PRIMARY KEY,
    program_code TEXT NOT NULL,
    amount NUMERIC(12, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    enrolled_at DATE NOT NULL
);

CREATE TABLE marts.dim_ott_subscription (
    id SERIAL PRIMARY KEY,
    subscriber_id TEXT NOT NULL,
    amount NUMERIC(10, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    plan_name TEXT
);

CREATE TABLE marts.rpt_revenue_monthly (
    month DATE NOT NULL,
    region TEXT NOT NULL,
    amount NUMERIC(14, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    PRIMARY KEY (month, region)
);

-- Seed rows
INSERT INTO crm.customers (name, email, amount) VALUES
    ('Acme Corp', 'acme@example.com', 1200.50),
    ('Globex', 'globex@example.com', 800.00);

INSERT INTO crm.accounts (customer_id, balance) VALUES (1, 500.00), (2, 250.75);

INSERT INTO sales.customers (name, email, revenue) VALUES
    ('Retail One', 'r1@example.com', 4200.00),
    ('Retail Two', 'r2@example.com', 3100.25);

INSERT INTO sales.orders (customer_id, amount, currency, status) VALUES
    (1, 199.99, 'USD', 'shipped'),
    (2, 89.50, 'EUR', 'open');

INSERT INTO finance.ledger (account_code, amount, currency) VALUES
    ('CASH', 10000.00, 'USD'),
    ('AR', 2500.00, 'USD');

INSERT INTO inventory.products (sku, name, stock_qty, unit_cost) VALUES
    ('SKU-001', 'Widget', 120, 4.50),
    ('SKU-002', 'Gadget', 45, 12.00);

INSERT INTO hr.employees (full_name, email, salary, department) VALUES
    ('Jane Doe', 'jane@acme.com', 95000.00, 'Engineering'),
    ('John Smith', 'john@acme.com', 82000.00, 'Sales');

INSERT INTO marts.fct_enrollment (program_code, amount, currency, enrolled_at) VALUES
    ('IEO', 150.00, 'USD', CURRENT_DATE - 30),
    ('OTT', 9.99, 'INR', CURRENT_DATE - 7);

INSERT INTO marts.dim_ott_subscription (subscriber_id, amount, currency, plan_name) VALUES
    ('sub-100', 12.99, 'USD', 'Premium'),
    ('sub-200', 499.00, 'INR', 'Annual');

INSERT INTO marts.rpt_revenue_monthly (month, region, amount, currency) VALUES
    (DATE_TRUNC('month', CURRENT_DATE)::date, 'NA', 125000.00, 'USD'),
    (DATE_TRUNC('month', CURRENT_DATE)::date, 'APAC', 98000.00, 'INR');
