-- =====================================================
-- DBA Agent Test Database Setup Script
-- This script creates a realistic database with:
-- - Performance issues (missing indexes, inefficient queries)
-- - Security vulnerabilities (SQL injection risks, weak passwords)
-- - Design violations (poor normalization, missing constraints)
-- - Data integrity issues (orphaned records, missing FKs)
-- =====================================================

USE dba_test_db;

-- =====================================================
-- 1. USERS TABLE - Missing indexes, weak password storage
-- =====================================================
CREATE TABLE IF NOT EXISTS users (
    user_id INT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL,
    email VARCHAR(100),
    password VARCHAR(50) NOT NULL,  -- VULNERABILITY: Plain text passwords
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_login TIMESTAMP NULL,
    status ENUM('active', 'inactive', 'suspended') DEFAULT 'active',
    INDEX idx_email (email)  -- Only email indexed, username not indexed
);

-- =====================================================
-- 2. ORDERS TABLE - Missing foreign key, no indexes on FK columns
-- =====================================================
CREATE TABLE IF NOT EXISTS orders (
    order_id INT AUTO_INCREMENT PRIMARY KEY,
    user_id INT NOT NULL,  -- VIOLATION: No foreign key constraint
    order_date DATETIME NOT NULL,
    total_amount DECIMAL(10,2) NOT NULL,
    status VARCHAR(20) DEFAULT 'pending',
    shipping_address TEXT,
    notes TEXT,
    INDEX idx_order_date (order_date)  -- Missing index on user_id
);

-- =====================================================
-- 3. ORDER_ITEMS TABLE - Redundant data, missing composite index
-- =====================================================
CREATE TABLE IF NOT EXISTS order_items (
    item_id INT AUTO_INCREMENT PRIMARY KEY,
    order_id INT NOT NULL,
    product_id INT NOT NULL,
    product_name VARCHAR(200) NOT NULL,  -- VIOLATION: Redundant data (should reference products)
    quantity INT NOT NULL,
    unit_price DECIMAL(10,2) NOT NULL,
    total_price DECIMAL(10,2) NOT NULL,  -- VIOLATION: Calculated field stored
    FOREIGN KEY (order_id) REFERENCES orders(order_id) ON DELETE CASCADE,
    INDEX idx_order_id (order_id)  -- Missing composite index on (order_id, product_id)
);

-- =====================================================
-- 4. PRODUCTS TABLE - Missing indexes on frequently queried columns
-- =====================================================
CREATE TABLE IF NOT EXISTS products (
    product_id INT AUTO_INCREMENT PRIMARY KEY,
    product_name VARCHAR(200) NOT NULL,
    category_id INT,
    price DECIMAL(10,2) NOT NULL,
    stock_quantity INT DEFAULT 0,
    description TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_active BOOLEAN DEFAULT TRUE,
    INDEX idx_category_id (category_id)  -- Missing index on price, stock_quantity
);

-- =====================================================
-- 5. CATEGORIES TABLE - Self-referencing without proper index
-- =====================================================
CREATE TABLE IF NOT EXISTS categories (
    category_id INT AUTO_INCREMENT PRIMARY KEY,
    category_name VARCHAR(100) NOT NULL,
    parent_category_id INT NULL,
    description TEXT,
    FOREIGN KEY (parent_category_id) REFERENCES categories(category_id),
    INDEX idx_parent (parent_category_id)  -- Good, but missing index on category_name
);

-- =====================================================
-- 6. PAYMENTS TABLE - Poor data type choices, missing constraints
-- =====================================================
CREATE TABLE IF NOT EXISTS payments (
    payment_id INT AUTO_INCREMENT PRIMARY KEY,
    order_id INT NOT NULL,
    payment_method VARCHAR(50),  -- VIOLATION: Should be ENUM
    amount DECIMAL(10,2) NOT NULL,
    transaction_id VARCHAR(100),  -- VIOLATION: No UNIQUE constraint
    payment_date DATETIME NOT NULL,
    status VARCHAR(20),  -- VIOLATION: Should be ENUM with CHECK constraint
    card_last_four VARCHAR(4),  -- SECURITY: Storing card data (even partial)
    FOREIGN KEY (order_id) REFERENCES orders(order_id),
    INDEX idx_payment_date (payment_date)  -- Missing index on status, transaction_id
);

-- =====================================================
-- 7. LOGS TABLE - Unbounded growth, no partitioning, missing indexes
-- =====================================================
CREATE TABLE IF NOT EXISTS application_logs (
    log_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id INT,
    action_type VARCHAR(50) NOT NULL,
    action_details TEXT,  -- VULNERABILITY: Could contain SQL injection attempts
    ip_address VARCHAR(45),
    user_agent TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_created_at (created_at),  -- Missing index on user_id, action_type
    INDEX idx_user_action (user_id, action_type)  -- Composite but user_id can be NULL
);

-- =====================================================
-- 8. REVIEWS TABLE - Missing validation, potential for abuse
-- =====================================================
CREATE TABLE IF NOT EXISTS product_reviews (
    review_id INT AUTO_INCREMENT PRIMARY KEY,
    product_id INT NOT NULL,
    user_id INT NOT NULL,
    rating INT,  -- VIOLATION: No CHECK constraint (should be 1-5)
    review_text TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (product_id) REFERENCES products(product_id),
    INDEX idx_product_id (product_id)  -- Missing unique constraint on (product_id, user_id)
);

-- =====================================================
-- 9. INVENTORY_TRANSACTIONS - Audit table without proper indexing
-- =====================================================
CREATE TABLE IF NOT EXISTS inventory_transactions (
    transaction_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_id INT NOT NULL,
    transaction_type ENUM('purchase', 'sale', 'return', 'adjustment') NOT NULL,
    quantity_change INT NOT NULL,
    previous_quantity INT,
    new_quantity INT,
    transaction_date DATETIME NOT NULL,
    user_id INT,
    notes TEXT,
    FOREIGN KEY (product_id) REFERENCES products(product_id),
    INDEX idx_transaction_date (transaction_date)  -- Missing index on product_id, transaction_type
);

-- =====================================================
-- 10. USER_SESSIONS - Security issue: No expiration cleanup
-- =====================================================
CREATE TABLE IF NOT EXISTS user_sessions (
    session_id VARCHAR(128) PRIMARY KEY,
    user_id INT NOT NULL,
    ip_address VARCHAR(45),
    user_agent TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_activity TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NULL,  -- VIOLATION: No index on expires_at for cleanup
    FOREIGN KEY (user_id) REFERENCES users(user_id),
    INDEX idx_user_id (user_id)  -- Missing index on expires_at
);

-- =====================================================
-- 11. CONFIGURATION TABLE - No versioning, potential for conflicts
-- =====================================================
CREATE TABLE IF NOT EXISTS system_config (
    config_key VARCHAR(100) PRIMARY KEY,
    config_value TEXT NOT NULL,
    description TEXT,
    updated_by INT,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    -- VIOLATION: No foreign key on updated_by, no versioning
    INDEX idx_updated_at (updated_at)
);

-- =====================================================
-- 12. LARGE TABLE FOR PERFORMANCE TESTING - Missing critical indexes
-- =====================================================
CREATE TABLE IF NOT EXISTS order_history_archive (
    archive_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id INT NOT NULL,
    user_id INT NOT NULL,
    order_data JSON,  -- VIOLATION: Storing JSON without proper indexing
    archived_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_archived_at (archived_at)  -- Missing indexes on order_id, user_id
);

-- =====================================================
-- INSERT SAMPLE DATA
-- =====================================================

-- Insert Users (with weak passwords - security issue)
INSERT INTO users (username, email, password, status) VALUES
('admin', 'admin@example.com', 'admin123', 'active'),  -- VULNERABILITY: Weak password
('john_doe', 'john@example.com', 'password', 'active'),  -- VULNERABILITY: Common password
('jane_smith', 'jane@example.com', '123456', 'active'),  -- VULNERABILITY: Weak password
('bob_wilson', 'bob@example.com', 'qwerty', 'active'),
('alice_brown', 'alice@example.com', 'letmein', 'active'),
('charlie_davis', 'charlie@example.com', 'welcome123', 'active'),
('diana_miller', 'diana@example.com', 'password123', 'active'),
('edward_taylor', 'edward@example.com', 'admin', 'inactive'),
('fiona_anderson', 'fiona@example.com', 'root', 'suspended'),
('george_martin', 'george@example.com', 'test123', 'active');

-- Insert Categories
INSERT INTO categories (category_name, parent_category_id, description) VALUES
('Electronics', NULL, 'Electronic devices and gadgets'),
('Computers', 1, 'Desktop and laptop computers'),
('Smartphones', 1, 'Mobile phones and accessories'),
('Clothing', NULL, 'Apparel and fashion items'),
('Men''s Clothing', 4, 'Clothing for men'),
('Women''s Clothing', 4, 'Clothing for women'),
('Home & Garden', NULL, 'Home improvement and garden supplies'),
('Furniture', 7, 'Home and office furniture'),
('Books', NULL, 'Books and publications'),
('Fiction', 9, 'Fiction books');

-- Insert Products
INSERT INTO products (product_name, category_id, price, stock_quantity, description, is_active) VALUES
('Laptop Pro 15"', 2, 1299.99, 25, 'High-performance laptop with 16GB RAM', TRUE),
('Smartphone X', 3, 899.99, 50, 'Latest smartphone with advanced camera', TRUE),
('Wireless Mouse', 2, 29.99, 200, 'Ergonomic wireless mouse', TRUE),
('T-Shirt Classic', 5, 19.99, 150, 'Comfortable cotton t-shirt', TRUE),
('Jeans Slim Fit', 5, 49.99, 75, 'Slim fit denim jeans', TRUE),
('Dress Summer', 6, 79.99, 40, 'Lightweight summer dress', TRUE),
('Office Chair', 8, 299.99, 30, 'Ergonomic office chair', TRUE),
('Coffee Table', 8, 199.99, 20, 'Modern coffee table', TRUE),
('Mystery Novel', 10, 14.99, 100, 'Bestselling mystery novel', TRUE),
('Sci-Fi Book', 10, 16.99, 80, 'Science fiction adventure', TRUE),
('Gaming Laptop', 2, 1899.99, 15, 'High-end gaming laptop', TRUE),
('Tablet 10"', 3, 399.99, 60, '10-inch tablet with stylus', TRUE),
('Headphones Pro', 1, 199.99, 90, 'Noise-cancelling headphones', TRUE),
('Running Shoes', 5, 89.99, 120, 'Comfortable running shoes', TRUE),
('Winter Jacket', 5, 149.99, 35, 'Warm winter jacket', TRUE);

-- Insert Orders (some with orphaned user_ids - data integrity issue)
INSERT INTO orders (user_id, order_date, total_amount, status, shipping_address) VALUES
(1, '2024-01-15 10:30:00', 1299.99, 'completed', '123 Main St, City, State 12345'),
(2, '2024-01-16 14:20:00', 929.98, 'completed', '456 Oak Ave, City, State 12346'),
(3, '2024-01-17 09:15:00', 49.98, 'pending', '789 Pine Rd, City, State 12347'),
(1, '2024-01-18 11:45:00', 299.99, 'completed', '123 Main St, City, State 12345'),
(4, '2024-01-19 16:30:00', 79.99, 'shipped', '321 Elm St, City, State 12348'),
(5, '2024-01-20 08:00:00', 199.99, 'pending', '654 Maple Dr, City, State 12349'),
(2, '2024-01-21 13:20:00', 31.98, 'completed', '456 Oak Ave, City, State 12346'),
(6, '2024-01-22 10:10:00', 1899.99, 'processing', '987 Cedar Ln, City, State 12350'),
(1, '2024-01-23 15:30:00', 399.99, 'completed', '123 Main St, City, State 12345'),
(3, '2024-01-24 12:00:00', 199.99, 'completed', '789 Pine Rd, City, State 12347'),
(7, '2024-01-25 09:45:00', 89.99, 'shipped', '147 Birch Way, City, State 12351'),
(2, '2024-01-26 14:15:00', 149.99, 'pending', '456 Oak Ave, City, State 12346'),
(99, '2024-01-27 11:00:00', 500.00, 'pending', '999 Invalid St, City, State 99999');  -- VIOLATION: Orphaned user_id

-- Insert Order Items
INSERT INTO order_items (order_id, product_id, product_name, quantity, unit_price, total_price) VALUES
(1, 1, 'Laptop Pro 15"', 1, 1299.99, 1299.99),
(2, 2, 'Smartphone X', 1, 899.99, 899.99),
(2, 3, 'Wireless Mouse', 1, 29.99, 29.99),
(3, 4, 'T-Shirt Classic', 2, 19.99, 39.98),
(3, 5, 'Jeans Slim Fit', 1, 49.99, 49.99),
(4, 7, 'Office Chair', 1, 299.99, 299.99),
(5, 6, 'Dress Summer', 1, 79.99, 79.99),
(6, 8, 'Coffee Table', 1, 199.99, 199.99),
(7, 4, 'T-Shirt Classic', 1, 19.99, 19.99),
(7, 3, 'Wireless Mouse', 1, 11.99, 11.99),  -- VIOLATION: Price mismatch with products table
(8, 11, 'Gaming Laptop', 1, 1899.99, 1899.99),
(9, 12, 'Tablet 10"', 1, 399.99, 399.99),
(10, 13, 'Headphones Pro', 1, 199.99, 199.99),
(11, 14, 'Running Shoes', 1, 89.99, 89.99),
(12, 15, 'Winter Jacket', 1, 149.99, 149.99);

-- Insert Payments (some with duplicate transaction_ids - violation)
INSERT INTO payments (order_id, payment_method, amount, transaction_id, payment_date, status, card_last_four) VALUES
(1, 'credit_card', 1299.99, 'TXN001', '2024-01-15 10:35:00', 'completed', '1234'),
(2, 'credit_card', 929.98, 'TXN002', '2024-01-16 14:25:00', 'completed', '5678'),
(3, 'paypal', 49.98, 'TXN003', '2024-01-17 09:20:00', 'pending', NULL),
(4, 'credit_card', 299.99, 'TXN004', '2024-01-18 11:50:00', 'completed', '9012'),
(5, 'debit_card', 79.99, 'TXN005', '2024-01-19 16:35:00', 'completed', '3456'),
(6, 'credit_card', 199.99, 'TXN006', '2024-01-20 08:05:00', 'pending', '7890'),
(7, 'paypal', 31.98, 'TXN002', '2024-01-21 13:25:00', 'completed', NULL),  -- VIOLATION: Duplicate transaction_id
(8, 'credit_card', 1899.99, 'TXN008', '2024-01-22 10:15:00', 'processing', '2468'),
(9, 'credit_card', 399.99, 'TXN009', '2024-01-23 15:35:00', 'completed', '1357'),
(10, 'debit_card', 199.99, 'TXN010', '2024-01-24 12:05:00', 'completed', '9753'),
(11, 'credit_card', 89.99, 'TXN011', '2024-01-25 09:50:00', 'completed', '8642'),
(12, 'credit_card', 149.99, 'TXN012', '2024-01-26 14:20:00', 'pending', '7410');

-- Insert Application Logs (with potential SQL injection attempts)
INSERT INTO application_logs (user_id, action_type, action_details, ip_address, user_agent) VALUES
(1, 'LOGIN', 'User logged in successfully', '192.168.1.100', 'Mozilla/5.0'),
(2, 'LOGIN', 'User logged in successfully', '192.168.1.101', 'Chrome/120.0'),
(1, 'VIEW_PRODUCT', 'Viewed product ID: 1', '192.168.1.100', 'Mozilla/5.0'),
(3, 'LOGIN', 'User logged in successfully', '192.168.1.102', 'Safari/17.0'),
(2, 'ADD_TO_CART', 'Added product to cart', '192.168.1.101', 'Chrome/120.0'),
(1, 'CHECKOUT', 'Initiated checkout process', '192.168.1.100', 'Mozilla/5.0'),
(4, 'LOGIN', 'User logged in successfully', '192.168.1.103', 'Firefox/121.0'),
(1, 'SEARCH', 'Searched for: laptop', '192.168.1.100', 'Mozilla/5.0'),
(5, 'LOGIN', 'User logged in successfully', '192.168.1.104', 'Edge/120.0'),
(1, 'LOGOUT', 'User logged out', '192.168.1.100', 'Mozilla/5.0'),
(NULL, 'ATTACK', '1'' OR ''1''=''1', '10.0.0.1', 'SQL Injection Tool'),  -- VULNERABILITY: SQL injection attempt
(NULL, 'ATTACK', 'DROP TABLE users;', '10.0.0.2', 'Malicious Bot'),  -- VULNERABILITY: SQL injection attempt
(2, 'VIEW_PRODUCT', 'Viewed product ID: 2', '192.168.1.101', 'Chrome/120.0'),
(3, 'ADD_TO_CART', 'Added product to cart', '192.168.1.102', 'Safari/17.0'),
(1, 'LOGIN', 'User logged in successfully', '192.168.1.100', 'Mozilla/5.0');

-- Insert Product Reviews (with invalid ratings - violation)
INSERT INTO product_reviews (product_id, user_id, rating, review_text) VALUES
(1, 1, 5, 'Excellent laptop, very fast and reliable!'),
(1, 2, 4, 'Good performance but battery could be better.'),
(2, 3, 5, 'Amazing camera quality and smooth performance.'),
(3, 1, 3, 'Decent mouse but buttons feel cheap.'),
(4, 4, 5, 'Very comfortable and good quality fabric.'),
(5, 5, 4, 'Great fit and quality, highly recommend.'),
(6, 6, 5, 'Beautiful dress, perfect for summer!'),
(7, 1, 5, 'Very comfortable for long work sessions.'),
(8, 2, 4, 'Nice design but assembly was difficult.'),
(1, 3, 10, 'Best laptop ever!'),  -- VIOLATION: Rating > 5
(2, 4, -1, 'Terrible product'),  -- VIOLATION: Negative rating
(3, 5, 5, 'Great mouse, works perfectly.'),
(4, 6, 4, 'Good value for money.'),
(5, 7, 5, 'Perfect fit and comfortable.');

-- Insert Inventory Transactions
INSERT INTO inventory_transactions (product_id, transaction_type, quantity_change, previous_quantity, new_quantity, transaction_date, user_id, notes) VALUES
(1, 'purchase', 50, 0, 50, '2024-01-01 10:00:00', 1, 'Initial stock purchase'),
(1, 'sale', -25, 50, 25, '2024-01-15 10:30:00', NULL, 'Order #1'),
(2, 'purchase', 100, 0, 100, '2024-01-02 11:00:00', 1, 'Initial stock purchase'),
(2, 'sale', -50, 100, 50, '2024-01-16 14:20:00', NULL, 'Order #2'),
(3, 'purchase', 300, 0, 300, '2024-01-03 12:00:00', 1, 'Initial stock purchase'),
(3, 'sale', -100, 300, 200, '2024-01-16 14:20:00', NULL, 'Order #2'),
(4, 'purchase', 200, 0, 200, '2024-01-04 13:00:00', 1, 'Initial stock purchase'),
(4, 'sale', -50, 200, 150, '2024-01-17 09:15:00', NULL, 'Order #3'),
(5, 'purchase', 100, 0, 100, '2024-01-05 14:00:00', 1, 'Initial stock purchase'),
(5, 'sale', -25, 100, 75, '2024-01-17 09:15:00', NULL, 'Order #3'),
(6, 'purchase', 50, 0, 50, '2024-01-06 15:00:00', 1, 'Initial stock purchase'),
(6, 'sale', -10, 50, 40, '2024-01-19 16:30:00', NULL, 'Order #5'),
(7, 'purchase', 50, 0, 50, '2024-01-07 16:00:00', 1, 'Initial stock purchase'),
(7, 'sale', -20, 50, 30, '2024-01-18 11:45:00', NULL, 'Order #4'),
(8, 'purchase', 30, 0, 30, '2024-01-08 17:00:00', 1, 'Initial stock purchase'),
(8, 'sale', -10, 30, 20, '2024-01-20 08:00:00', NULL, 'Order #6'),
(1, 'adjustment', -5, 25, 20, '2024-01-28 10:00:00', 1, 'Damaged items removed');

-- Insert User Sessions (some expired - should be cleaned up)
INSERT INTO user_sessions (session_id, user_id, ip_address, user_agent, created_at, last_activity, expires_at) VALUES
('sess_abc123', 1, '192.168.1.100', 'Mozilla/5.0', '2024-01-25 10:00:00', '2024-01-25 15:30:00', '2024-01-26 10:00:00'),
('sess_def456', 2, '192.168.1.101', 'Chrome/120.0', '2024-01-26 09:00:00', '2024-01-26 14:20:00', '2024-01-27 09:00:00'),
('sess_ghi789', 3, '192.168.1.102', 'Safari/17.0', '2024-01-20 08:00:00', '2024-01-20 12:00:00', '2024-01-21 08:00:00'),  -- Expired
('sess_jkl012', 4, '192.168.1.103', 'Firefox/121.0', '2024-01-19 10:00:00', '2024-01-19 11:00:00', '2024-01-20 10:00:00'),  -- Expired
('sess_mno345', 5, '192.168.1.104', 'Edge/120.0', '2024-01-27 08:00:00', '2024-01-27 10:00:00', '2024-01-28 08:00:00'),
('sess_pqr678', 1, '192.168.1.100', 'Mozilla/5.0', '2024-01-15 10:00:00', '2024-01-15 11:00:00', '2024-01-16 10:00:00');  -- Expired

-- Insert System Config
INSERT INTO system_config (config_key, config_value, description, updated_by) VALUES
('site_name', 'DBA Test Store', 'Website name', 1),
('max_login_attempts', '5', 'Maximum login attempts before lockout', 1),
('session_timeout', '3600', 'Session timeout in seconds', 1),
('enable_registration', 'true', 'Allow new user registration', 1),
('maintenance_mode', 'false', 'Enable maintenance mode', 1),
('api_rate_limit', '1000', 'API rate limit per hour', 1);

-- Insert Order History Archive (large dataset for performance testing)
INSERT INTO order_history_archive (order_id, user_id, order_data, archived_at) VALUES
(1, 1, '{"order_total": 1299.99, "items": 1, "status": "completed"}', '2024-01-15 16:00:00'),
(2, 2, '{"order_total": 929.98, "items": 2, "status": "completed"}', '2024-01-16 16:00:00'),
(3, 3, '{"order_total": 49.98, "items": 2, "status": "pending"}', '2024-01-17 16:00:00'),
(4, 1, '{"order_total": 299.99, "items": 1, "status": "completed"}', '2024-01-18 16:00:00'),
(5, 4, '{"order_total": 79.99, "items": 1, "status": "shipped"}', '2024-01-19 16:00:00');

-- =====================================================
-- CREATE SOME PROBLEMATIC QUERIES FOR TESTING
-- =====================================================

-- This query will be slow (missing index on user_id in orders)
-- SELECT * FROM orders WHERE user_id = 1;

-- This query will be slow (missing index on status in payments)
-- SELECT * FROM payments WHERE status = 'pending';

-- This query will be slow (missing index on product_id in order_items)
-- SELECT oi.*, p.product_name FROM order_items oi JOIN products p ON oi.product_id = p.product_id WHERE p.product_id = 1;

-- This query will be slow (full table scan on application_logs)
-- SELECT * FROM application_logs WHERE action_type = 'LOGIN' AND user_id IS NOT NULL;

-- =====================================================
-- SUMMARY OF ISSUES CREATED:
-- =====================================================
-- 1. SECURITY VULNERABILITIES:
--    - Plain text passwords in users table
--    - Weak passwords (admin123, password, 123456)
--    - SQL injection attempts in application_logs
--    - Storing partial card data in payments table
--
-- 2. PERFORMANCE ISSUES:
--    - Missing indexes on frequently queried columns (user_id in orders, status in payments)
--    - Missing composite indexes (order_id, product_id in order_items)
--    - Large unbounded table (application_logs) without partitioning
--    - JSON column without proper indexing (order_history_archive)
--    - Missing index on expires_at in user_sessions (prevents efficient cleanup)
--
-- 3. DATA INTEGRITY VIOLATIONS:
--    - Missing foreign key constraint (user_id in orders)
--    - Orphaned records (order with user_id = 99)
--    - Duplicate transaction_ids in payments
--    - Invalid ratings (> 5 or < 1) in product_reviews
--    - Price mismatches between order_items and products
--
-- 4. DESIGN VIOLATIONS:
--    - Redundant data (product_name in order_items)
--    - Stored calculated fields (total_price in order_items)
--    - Poor data types (VARCHAR instead of ENUM for status fields)
--    - Missing CHECK constraints (rating should be 1-5)
--    - Missing UNIQUE constraints (transaction_id, product_id+user_id in reviews)
--    - No versioning in system_config
--
-- 5. MAINTENANCE ISSUES:
--    - Expired sessions not cleaned up
--    - No partitioning on large tables
--    - Missing indexes on audit/archive tables








