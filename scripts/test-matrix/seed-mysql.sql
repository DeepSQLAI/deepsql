-- Mirror of seed-postgres.sql in MySQL syntax: same tables, same fixed row counts
-- (300 / 50 / 500 / 800 / 250), so cross-engine assertions compare like with like.
-- Row generation uses a recursive CTE (MySQL 8.0+); max depth 800 stays under the
-- default cte_max_recursion_depth of 1000.

CREATE TABLE customers (
    id           INT AUTO_INCREMENT PRIMARY KEY,
    full_name    VARCHAR(120) NOT NULL,
    email        VARCHAR(160) NOT NULL UNIQUE,
    country_code CHAR(2)      NOT NULL,
    created_at   DATETIME     NOT NULL
);

CREATE TABLE products (
    id         INT AUTO_INCREMENT PRIMARY KEY,
    name       VARCHAR(120)   NOT NULL,
    unit_price DECIMAL(10, 2) NOT NULL
);

CREATE TABLE orders (
    id           INT AUTO_INCREMENT PRIMARY KEY,
    customer_id  INT            NOT NULL,
    status       VARCHAR(20)    NOT NULL,
    placed_at    DATETIME       NOT NULL,
    total_amount DECIMAL(12, 2) NOT NULL,
    CONSTRAINT fk_orders_customer FOREIGN KEY (customer_id) REFERENCES customers (id)
);

CREATE TABLE order_items (
    id         INT AUTO_INCREMENT PRIMARY KEY,
    order_id   INT            NOT NULL,
    product_id INT            NOT NULL,
    quantity   INT            NOT NULL,
    line_total DECIMAL(12, 2) NOT NULL,
    CONSTRAINT fk_items_order   FOREIGN KEY (order_id)   REFERENCES orders (id),
    CONSTRAINT fk_items_product FOREIGN KEY (product_id) REFERENCES products (id)
);

CREATE TABLE payments (
    id       INT AUTO_INCREMENT PRIMARY KEY,
    order_id INT            NOT NULL,
    amount   DECIMAL(12, 2) NOT NULL,
    paid_at  DATETIME       NOT NULL,
    method   VARCHAR(20)    NOT NULL,
    CONSTRAINT fk_payments_order FOREIGN KEY (order_id) REFERENCES orders (id)
);

CREATE INDEX idx_orders_customer_id ON orders (customer_id);
CREATE INDEX idx_orders_placed_at   ON orders (placed_at);
CREATE INDEX idx_order_items_order  ON order_items (order_id);
CREATE INDEX idx_payments_order     ON payments (order_id);

INSERT INTO customers (full_name, email, country_code, created_at)
WITH RECURSIVE g (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM g WHERE n < 300)
SELECT CONCAT('Customer ', n), CONCAT('user', n, '@example.com'),
       ELT(1 + (n % 5), 'US', 'GB', 'DE', 'IN', 'SG'),
       NOW() - INTERVAL (n % 365) DAY
FROM g;

INSERT INTO products (name, unit_price)
WITH RECURSIVE g (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM g WHERE n < 50)
SELECT CONCAT('Product ', n), ROUND(10 + (n * 7) % 500, 2) FROM g;

INSERT INTO orders (customer_id, status, placed_at, total_amount)
WITH RECURSIVE g (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM g WHERE n < 500)
SELECT 1 + (n % 300),
       ELT(1 + (n % 4), 'PLACED', 'SHIPPED', 'DELIVERED', 'CANCELLED'),
       NOW() - INTERVAL (n % 180) DAY,
       ROUND(50 + (n * 13) % 2000, 2)
FROM g;

INSERT INTO order_items (order_id, product_id, quantity, line_total)
WITH RECURSIVE g (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM g WHERE n < 800)
SELECT 1 + (n % 500), 1 + (n % 50), 1 + (n % 5),
       ROUND(20 + (n * 11) % 900, 2)
FROM g;

INSERT INTO payments (order_id, amount, paid_at, method)
WITH RECURSIVE g (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM g WHERE n < 250)
SELECT 1 + (n % 500), ROUND(30 + (n * 17) % 1500, 2),
       NOW() - INTERVAL (n % 90) DAY,
       ELT(1 + (n % 3), 'CARD', 'BANK', 'WALLET')
FROM g;

ANALYZE TABLE customers, products, orders, order_items, payments;
