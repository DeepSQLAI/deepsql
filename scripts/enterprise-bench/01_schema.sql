-- ACME ERP — intentionally ambiguous multi-schema enterprise model.
-- Designed to stress DeepSQL: duplicate business concepts, overloaded column
-- names (status/id/name/amount), soft-delete vs status filters, missing FKs,
-- and selective index gaps for workload recommendations.
--
-- Schemas: crm, sales, finance, inventory, hr

CREATE EXTENSION IF NOT EXISTS pg_stat_statements;

CREATE SCHEMA IF NOT EXISTS crm;
CREATE SCHEMA IF NOT EXISTS sales;
CREATE SCHEMA IF NOT EXISTS finance;
CREATE SCHEMA IF NOT EXISTS inventory;
CREATE SCHEMA IF NOT EXISTS hr;

-- ── CRM: "accounts" are customers in sales-speak ───────────────────────────
CREATE TABLE crm.accounts (
    id              BIGSERIAL PRIMARY KEY,
    account_number  VARCHAR(32)  NOT NULL UNIQUE,
    name            VARCHAR(200) NOT NULL,
    status          VARCHAR(20)  NOT NULL,          -- PROSPECT | ACTIVE | CHURNED
    tier            VARCHAR(20)  NOT NULL,          -- SMB | MID | ENTERPRISE
    email           VARCHAR(200),
    phone           VARCHAR(40),
    country_code    CHAR(2)      NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    is_deleted      BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE TABLE crm.contacts (
    id           BIGSERIAL PRIMARY KEY,
    account_id   BIGINT       NOT NULL,             -- intentional: no FK declared
    name         VARCHAR(200) NOT NULL,
    email        VARCHAR(200),
    role_title   VARCHAR(120),
    status       VARCHAR(20)  NOT NULL,             -- ACTIVE | INACTIVE
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- ── Sales: parallel "customers" concept (same people as crm.accounts) ─────
CREATE TABLE sales.customers (
    id              BIGSERIAL PRIMARY KEY,
    crm_account_id  BIGINT,                         -- soft link, often null historically
    customer_code   VARCHAR(32)  NOT NULL UNIQUE,
    name            VARCHAR(200) NOT NULL,
    status          VARCHAR(20)  NOT NULL,          -- ACTIVE | INACTIVE | BLOCKED
    email           VARCHAR(200),
    ssn_last4       CHAR(4),                        -- PII
    country_code    CHAR(2)      NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE sales.products (
    id           BIGSERIAL PRIMARY KEY,
    sku          VARCHAR(40)   NOT NULL UNIQUE,
    name         VARCHAR(200)  NOT NULL,
    status       VARCHAR(20)   NOT NULL,            -- ACTIVE | DISCONTINUED
    unit_price   NUMERIC(12,2) NOT NULL,
    category     VARCHAR(80)   NOT NULL,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE TABLE sales.orders (
    id            BIGSERIAL PRIMARY KEY,
    customer_id   BIGINT         NOT NULL,
    order_number  VARCHAR(40)    NOT NULL UNIQUE,
    status        VARCHAR(20)    NOT NULL,          -- PLACED | SHIPPED | DELIVERED | CANCELLED | RETURNED
    channel       VARCHAR(20)    NOT NULL,          -- WEB | STORE | PARTNER
    placed_at     TIMESTAMPTZ    NOT NULL,
    shipped_at    TIMESTAMPTZ,
    total_amount  NUMERIC(14,2)  NOT NULL,
    currency      CHAR(3)        NOT NULL DEFAULT 'USD',
    is_test       BOOLEAN        NOT NULL DEFAULT FALSE
);

CREATE TABLE sales.order_lines (
    id            BIGSERIAL PRIMARY KEY,
    order_id      BIGINT         NOT NULL,
    product_id    BIGINT         NOT NULL,
    quantity      INT            NOT NULL,
    unit_price    NUMERIC(12,2)  NOT NULL,
    line_amount   NUMERIC(14,2)  NOT NULL,
    status        VARCHAR(20)    NOT NULL           -- OPEN | FULFILLED | CANCELLED
);

CREATE TABLE sales.order_header (                    -- LEGACY alias table — same grain as orders
    id            BIGSERIAL PRIMARY KEY,
    cust_id       BIGINT         NOT NULL,
    hdr_status    VARCHAR(20)    NOT NULL,
    order_dt      DATE           NOT NULL,
    amount        NUMERIC(14,2)  NOT NULL
);

-- ── Finance ────────────────────────────────────────────────────────────────
CREATE TABLE finance.invoices (
    id             BIGSERIAL PRIMARY KEY,
    invoice_number VARCHAR(40)   NOT NULL UNIQUE,
    customer_id    BIGINT        NOT NULL,          -- sales.customers.id (undeclared)
    order_id       BIGINT,
    status         VARCHAR(20)   NOT NULL,          -- DRAFT | OPEN | PAID | VOID | DISPUTED
    amount         NUMERIC(14,2) NOT NULL,
    tax_amount     NUMERIC(14,2) NOT NULL DEFAULT 0,
    issued_at      TIMESTAMPTZ   NOT NULL,
    due_at         TIMESTAMPTZ   NOT NULL,
    paid_at        TIMESTAMPTZ
);

CREATE TABLE finance.payments (
    id             BIGSERIAL PRIMARY KEY,
    payment_number VARCHAR(40)   NOT NULL UNIQUE,
    invoice_id     BIGINT        NOT NULL,
    amount         NUMERIC(14,2) NOT NULL,
    status         VARCHAR(20)   NOT NULL,          -- PENDING | CLEARED | FAILED | REVERSED
    method         VARCHAR(20)   NOT NULL,          -- CARD | ACH | WIRE | CHECK
    paid_at        TIMESTAMPTZ   NOT NULL
);

CREATE TABLE finance.payment_orders (               -- ambiguous with sales.orders
    id             BIGSERIAL PRIMARY KEY,
    vendor_name    VARCHAR(200)  NOT NULL,
    status         VARCHAR(20)   NOT NULL,          -- SCHEDULED | SENT | COMPLETED | CANCELLED
    amount         NUMERIC(14,2) NOT NULL,
    scheduled_at   TIMESTAMPTZ   NOT NULL,
    completed_at   TIMESTAMPTZ
);

CREATE TABLE finance.gl_entries (
    id             BIGSERIAL PRIMARY KEY,
    account_code   VARCHAR(32)   NOT NULL,
    name           VARCHAR(200)  NOT NULL,
    status         VARCHAR(20)   NOT NULL,          -- POSTED | PENDING | REVERSED
    amount         NUMERIC(16,2) NOT NULL,
    posted_at      TIMESTAMPTZ   NOT NULL,
    cost_center    VARCHAR(40)
);

-- ── Inventory (product masters overlap sales.products) ─────────────────────
CREATE TABLE inventory.items (
    id             BIGSERIAL PRIMARY KEY,
    item_code      VARCHAR(40)   NOT NULL UNIQUE,
    name           VARCHAR(200)  NOT NULL,
    status         VARCHAR(20)   NOT NULL,          -- ACTIVE | HOLD | OBSOLETE
    sku_ref        VARCHAR(40),                     -- often matches sales.products.sku
    unit_cost      NUMERIC(12,2) NOT NULL,
    reorder_point  INT           NOT NULL DEFAULT 10,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE TABLE inventory.warehouses (
    id             BIGSERIAL PRIMARY KEY,
    code           VARCHAR(20)   NOT NULL UNIQUE,
    name           VARCHAR(120)  NOT NULL,
    status         VARCHAR(20)   NOT NULL,
    region         VARCHAR(40)   NOT NULL
);

CREATE TABLE inventory.stock_moves (
    id             BIGSERIAL PRIMARY KEY,
    item_id        BIGINT        NOT NULL,
    warehouse_id   BIGINT        NOT NULL,
    status         VARCHAR(20)   NOT NULL,          -- PENDING | COMPLETE | CANCELLED
    quantity       INT           NOT NULL,
    move_type      VARCHAR(20)   NOT NULL,          -- IN | OUT | ADJUST
    moved_at       TIMESTAMPTZ   NOT NULL,
    ref_order_id   BIGINT                                -- may point at sales.orders
);

CREATE TABLE inventory.product_master (             -- another product synonym
    id             BIGSERIAL PRIMARY KEY,
    name           VARCHAR(200)  NOT NULL,
    status         VARCHAR(20)   NOT NULL,
    amount         NUMERIC(12,2) NOT NULL,          -- "list price" overloaded as amount
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

-- ── HR (sensitive) ─────────────────────────────────────────────────────────
CREATE TABLE hr.employees (
    id             BIGSERIAL PRIMARY KEY,
    employee_number VARCHAR(32)  NOT NULL UNIQUE,
    name           VARCHAR(200)  NOT NULL,
    email          VARCHAR(200)  NOT NULL,
    status         VARCHAR(20)   NOT NULL,          -- ACTIVE | LEAVE | TERMINATED
    department     VARCHAR(80)   NOT NULL,
    title          VARCHAR(120)  NOT NULL,
    manager_id     BIGINT,
    hire_date      DATE          NOT NULL,
    salary         NUMERIC(12,2) NOT NULL,          -- highly sensitive
    ssn            VARCHAR(11),                     -- PII
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE TABLE hr.payroll_runs (
    id             BIGSERIAL PRIMARY KEY,
    employee_id    BIGINT        NOT NULL,
    status         VARCHAR(20)   NOT NULL,          -- DRAFT | APPROVED | PAID | VOID
    amount         NUMERIC(12,2) NOT NULL,
    period_start   DATE          NOT NULL,
    period_end     DATE          NOT NULL,
    paid_at        TIMESTAMPTZ
);

CREATE TABLE hr.departments (
    id             BIGSERIAL PRIMARY KEY,
    name           VARCHAR(120)  NOT NULL UNIQUE,
    status         VARCHAR(20)   NOT NULL,
    cost_center    VARCHAR(40)
);

-- Sparse helpful indexes; deliberate gaps on hot filters (status, placed_at range,
-- product_id, invoice customer_id) so the advisor has something to recommend.
CREATE INDEX idx_crm_accounts_status ON crm.accounts (status);
CREATE INDEX idx_sales_customers_code ON sales.customers (customer_code);
CREATE INDEX idx_sales_orders_customer ON sales.orders (customer_id);
CREATE INDEX idx_sales_orders_number ON sales.orders (order_number);
CREATE INDEX idx_finance_invoices_number ON finance.invoices (invoice_number);
CREATE INDEX idx_inventory_items_code ON inventory.items (item_code);
CREATE INDEX idx_hr_employees_number ON hr.employees (employee_number);

-- Legacy / redundant index noise (unused-looking after workload)
CREATE INDEX idx_sales_orders_currency ON sales.orders (currency);
CREATE INDEX idx_sales_products_created ON sales.products (created_at);
