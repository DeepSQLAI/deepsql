-- Synthetic retail schema, mirrored between PostgreSQL and MySQL so brain-init and
-- chat results are comparable across engines. No production data.
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;

CREATE TABLE customers (
    id           SERIAL PRIMARY KEY,
    full_name    VARCHAR(120) NOT NULL,
    email        VARCHAR(160) NOT NULL UNIQUE,
    country_code CHAR(2)      NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE products (
    id         SERIAL PRIMARY KEY,
    name       VARCHAR(120)   NOT NULL,
    unit_price NUMERIC(10, 2) NOT NULL
);

CREATE TABLE orders (
    id           SERIAL PRIMARY KEY,
    customer_id  INT            NOT NULL REFERENCES customers (id),
    status       VARCHAR(20)    NOT NULL,
    placed_at    TIMESTAMPTZ    NOT NULL,
    total_amount NUMERIC(12, 2) NOT NULL
);

CREATE TABLE order_items (
    id         SERIAL PRIMARY KEY,
    order_id   INT            NOT NULL REFERENCES orders (id),
    product_id INT            NOT NULL REFERENCES products (id),
    quantity   INT            NOT NULL,
    line_total NUMERIC(12, 2) NOT NULL
);

CREATE TABLE payments (
    id       SERIAL PRIMARY KEY,
    order_id INT            NOT NULL REFERENCES orders (id),
    amount   NUMERIC(12, 2) NOT NULL,
    paid_at  TIMESTAMPTZ    NOT NULL,
    method   VARCHAR(20)    NOT NULL
);

CREATE INDEX idx_orders_customer_id ON orders (customer_id);
CREATE INDEX idx_orders_placed_at   ON orders (placed_at);
CREATE INDEX idx_order_items_order  ON order_items (order_id);
CREATE INDEX idx_payments_order     ON payments (order_id);

-- Row counts are fixed so assertions are exact: 300 / 50 / 500 / 800 / 250.
INSERT INTO customers (full_name, email, country_code, created_at)
SELECT 'Customer ' || g, 'user' || g || '@example.com',
       (ARRAY['US','GB','DE','IN','SG'])[1 + (g % 5)],
       NOW() - ((g % 365) || ' days')::INTERVAL
FROM generate_series(1, 300) g;

INSERT INTO products (name, unit_price)
SELECT 'Product ' || g, ROUND((10 + (g * 7) % 500)::NUMERIC, 2)
FROM generate_series(1, 50) g;

INSERT INTO orders (customer_id, status, placed_at, total_amount)
SELECT 1 + (g % 300),
       (ARRAY['PLACED','SHIPPED','DELIVERED','CANCELLED'])[1 + (g % 4)],
       NOW() - ((g % 180) || ' days')::INTERVAL,
       ROUND((50 + (g * 13) % 2000)::NUMERIC, 2)
FROM generate_series(1, 500) g;

INSERT INTO order_items (order_id, product_id, quantity, line_total)
SELECT 1 + (g % 500), 1 + (g % 50), 1 + (g % 5),
       ROUND((20 + (g * 11) % 900)::NUMERIC, 2)
FROM generate_series(1, 800) g;

INSERT INTO payments (order_id, amount, paid_at, method)
SELECT 1 + (g % 500), ROUND((30 + (g * 17) % 1500)::NUMERIC, 2),
       NOW() - ((g % 90) || ' days')::INTERVAL,
       (ARRAY['CARD','BANK','WALLET'])[1 + (g % 3)]
FROM generate_series(1, 250) g;

ANALYZE;
