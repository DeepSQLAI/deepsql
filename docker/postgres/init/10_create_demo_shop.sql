-- Demo Shop Database
-- A realistic e-commerce database for demonstrating DeepSQL features
-- Includes intentionally suboptimal patterns that will trigger recommendations

-- Create the demo_shop database if it doesn't exist
SELECT 'Creating demo_shop database' AS status;

-- Drop and recreate for idempotency
DROP DATABASE IF EXISTS demo_shop;
CREATE DATABASE demo_shop;

\connect demo_shop

-- Enable extensions
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;

-- ============================================================================
-- SCHEMA: Core E-commerce Tables
-- ============================================================================

-- Categories table
CREATE TABLE categories (
    id SERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    description TEXT,
    parent_id INTEGER REFERENCES categories(id),
    slug VARCHAR(100) UNIQUE NOT NULL,
    is_active BOOLEAN DEFAULT true,
    display_order INTEGER DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Products table (intentionally missing composite index for common query patterns)
CREATE TABLE products (
    id SERIAL PRIMARY KEY,
    sku VARCHAR(50) UNIQUE NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    price DECIMAL(10, 2) NOT NULL,
    cost_price DECIMAL(10, 2),
    category_id INTEGER REFERENCES categories(id),
    brand VARCHAR(100),
    weight_kg DECIMAL(8, 3),
    is_active BOOLEAN DEFAULT true,
    stock_quantity INTEGER DEFAULT 0,
    low_stock_threshold INTEGER DEFAULT 10,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Single-column index (will be flagged as needing composite index)
CREATE INDEX idx_products_category ON products(category_id);
CREATE INDEX idx_products_is_active ON products(is_active);

-- Customers table
CREATE TABLE customers (
    id SERIAL PRIMARY KEY,
    email VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    first_name VARCHAR(100),
    last_name VARCHAR(100),
    phone VARCHAR(20),
    date_of_birth DATE,
    gender VARCHAR(10),
    loyalty_points INTEGER DEFAULT 0,
    tier VARCHAR(20) DEFAULT 'bronze', -- bronze, silver, gold, platinum
    is_verified BOOLEAN DEFAULT false,
    is_active BOOLEAN DEFAULT true,
    last_login_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_customers_email ON customers(email);
CREATE INDEX idx_customers_tier ON customers(tier);

-- Addresses table
CREATE TABLE addresses (
    id SERIAL PRIMARY KEY,
    customer_id INTEGER NOT NULL REFERENCES customers(id),
    address_type VARCHAR(20) DEFAULT 'shipping', -- shipping, billing
    street_address VARCHAR(255) NOT NULL,
    city VARCHAR(100) NOT NULL,
    state VARCHAR(100),
    postal_code VARCHAR(20) NOT NULL,
    country VARCHAR(100) NOT NULL DEFAULT 'United States',
    is_default BOOLEAN DEFAULT false,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_addresses_customer ON addresses(customer_id);

-- Orders table (will show as high-volume table needing optimization)
CREATE TABLE orders (
    id SERIAL PRIMARY KEY,
    order_number VARCHAR(50) UNIQUE NOT NULL,
    customer_id INTEGER REFERENCES customers(id),
    status VARCHAR(30) NOT NULL DEFAULT 'pending', -- pending, confirmed, processing, shipped, delivered, cancelled, refunded
    shipping_address_id INTEGER REFERENCES addresses(id),
    billing_address_id INTEGER REFERENCES addresses(id),
    subtotal DECIMAL(12, 2) NOT NULL,
    tax_amount DECIMAL(12, 2) DEFAULT 0,
    shipping_amount DECIMAL(12, 2) DEFAULT 0,
    discount_amount DECIMAL(12, 2) DEFAULT 0,
    total_amount DECIMAL(12, 2) NOT NULL,
    currency VARCHAR(3) DEFAULT 'USD',
    payment_method VARCHAR(50),
    payment_status VARCHAR(30) DEFAULT 'pending', -- pending, paid, failed, refunded
    notes TEXT,
    shipped_at TIMESTAMP,
    delivered_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Intentionally separate indexes (will be recommended to create composite)
CREATE INDEX idx_orders_customer ON orders(customer_id);
CREATE INDEX idx_orders_status ON orders(status);
CREATE INDEX idx_orders_created ON orders(created_at);
CREATE INDEX idx_orders_payment_status ON orders(payment_status);

-- Order items table
CREATE TABLE order_items (
    id SERIAL PRIMARY KEY,
    order_id INTEGER NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    product_id INTEGER NOT NULL REFERENCES products(id),
    quantity INTEGER NOT NULL,
    unit_price DECIMAL(10, 2) NOT NULL,
    subtotal DECIMAL(12, 2) NOT NULL,
    discount_amount DECIMAL(10, 2) DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_order_items_order ON order_items(order_id);
CREATE INDEX idx_order_items_product ON order_items(product_id);

-- Product reviews table
CREATE TABLE product_reviews (
    id SERIAL PRIMARY KEY,
    product_id INTEGER NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    customer_id INTEGER REFERENCES customers(id),
    rating INTEGER NOT NULL CHECK (rating >= 1 AND rating <= 5),
    title VARCHAR(255),
    review_text TEXT,
    is_verified_purchase BOOLEAN DEFAULT false,
    is_approved BOOLEAN DEFAULT false,
    helpful_votes INTEGER DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_reviews_product ON product_reviews(product_id);
CREATE INDEX idx_reviews_rating ON product_reviews(rating);

-- Inventory movements table (for tracking stock changes)
CREATE TABLE inventory_movements (
    id SERIAL PRIMARY KEY,
    product_id INTEGER NOT NULL REFERENCES products(id),
    movement_type VARCHAR(30) NOT NULL, -- purchase, sale, adjustment, return, transfer
    quantity INTEGER NOT NULL,
    reference_id VARCHAR(100), -- order_id, purchase_order_id, etc.
    notes TEXT,
    created_by VARCHAR(100),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_inventory_product ON inventory_movements(product_id);
CREATE INDEX idx_inventory_type ON inventory_movements(movement_type);
CREATE INDEX idx_inventory_created ON inventory_movements(created_at);

-- Coupons table
CREATE TABLE coupons (
    id SERIAL PRIMARY KEY,
    code VARCHAR(50) UNIQUE NOT NULL,
    description TEXT,
    discount_type VARCHAR(20) NOT NULL, -- percentage, fixed_amount
    discount_value DECIMAL(10, 2) NOT NULL,
    minimum_order_amount DECIMAL(10, 2),
    max_uses INTEGER,
    used_count INTEGER DEFAULT 0,
    starts_at TIMESTAMP,
    expires_at TIMESTAMP,
    is_active BOOLEAN DEFAULT true,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Order coupons junction table
CREATE TABLE order_coupons (
    order_id INTEGER NOT NULL REFERENCES orders(id),
    coupon_id INTEGER NOT NULL REFERENCES coupons(id),
    discount_applied DECIMAL(10, 2) NOT NULL,
    PRIMARY KEY (order_id, coupon_id)
);

-- Shopping cart table (for abandoned cart analysis)
CREATE TABLE shopping_carts (
    id SERIAL PRIMARY KEY,
    customer_id INTEGER REFERENCES customers(id),
    session_id VARCHAR(100),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP
);

CREATE TABLE cart_items (
    id SERIAL PRIMARY KEY,
    cart_id INTEGER NOT NULL REFERENCES shopping_carts(id) ON DELETE CASCADE,
    product_id INTEGER NOT NULL REFERENCES products(id),
    quantity INTEGER NOT NULL DEFAULT 1,
    added_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Audit log table (high-volume, will show growth concerns)
CREATE TABLE audit_log (
    id BIGSERIAL PRIMARY KEY,
    table_name VARCHAR(100) NOT NULL,
    record_id INTEGER NOT NULL,
    action VARCHAR(20) NOT NULL, -- INSERT, UPDATE, DELETE
    old_values JSONB,
    new_values JSONB,
    changed_by VARCHAR(100),
    changed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Missing index on frequently queried columns (intentional for recommendations)
-- No index on table_name + changed_at composite

-- ============================================================================
-- SEED DATA: Categories
-- ============================================================================

INSERT INTO categories (name, description, slug, display_order) VALUES
('Electronics', 'Electronic devices and accessories', 'electronics', 1),
('Clothing', 'Apparel and fashion items', 'clothing', 2),
('Home & Garden', 'Home improvement and garden supplies', 'home-garden', 3),
('Sports & Outdoors', 'Sports equipment and outdoor gear', 'sports-outdoors', 4),
('Books', 'Books, e-books, and audiobooks', 'books', 5),
('Health & Beauty', 'Health, beauty, and personal care', 'health-beauty', 6);

-- Subcategories
INSERT INTO categories (name, description, slug, parent_id, display_order) VALUES
('Smartphones', 'Mobile phones and accessories', 'smartphones', 1, 1),
('Laptops', 'Portable computers', 'laptops', 1, 2),
('Audio', 'Headphones, speakers, and audio equipment', 'audio', 1, 3),
('Men''s Clothing', 'Men''s apparel', 'mens-clothing', 2, 1),
('Women''s Clothing', 'Women''s apparel', 'womens-clothing', 2, 2),
('Furniture', 'Home furniture', 'furniture', 3, 1),
('Kitchen', 'Kitchen appliances and tools', 'kitchen', 3, 2);

-- ============================================================================
-- SEED DATA: Products (100 products across categories)
-- ============================================================================

INSERT INTO products (sku, name, description, price, cost_price, category_id, brand, stock_quantity, is_active) VALUES
-- Electronics - Smartphones
('ELEC-SP-001', 'ProPhone X15 Pro', '6.7" OLED display, 256GB storage, 5G capable', 1199.99, 750.00, 7, 'ProPhone', 150, true),
('ELEC-SP-002', 'ProPhone X15', '6.1" OLED display, 128GB storage, 5G capable', 999.99, 600.00, 7, 'ProPhone', 200, true),
('ELEC-SP-003', 'Galaxy Ultra S25', '6.8" Dynamic AMOLED, 512GB, S Pen included', 1299.99, 820.00, 7, 'Samsung', 100, true),
('ELEC-SP-004', 'Galaxy S25+', '6.6" Dynamic AMOLED, 256GB storage', 999.99, 620.00, 7, 'Samsung', 180, true),
('ELEC-SP-005', 'Pixel 9 Pro', '6.7" LTPO OLED, 256GB, Google AI features', 1099.99, 680.00, 7, 'Google', 120, true),
('ELEC-SP-006', 'OnePlus 13', '6.82" AMOLED, 256GB, Hasselblad camera', 899.99, 550.00, 7, 'OnePlus', 90, true),
('ELEC-SP-007', 'Budget Phone A5', '6.5" LCD, 64GB, Dual SIM', 199.99, 110.00, 7, 'BudgetTech', 500, true),
('ELEC-SP-008', 'ProPhone SE 4', '4.7" Retina, 128GB, compact design', 499.99, 300.00, 7, 'ProPhone', 250, true),

-- Electronics - Laptops
('ELEC-LP-001', 'MacBook Pro 16" M4', '16" Liquid Retina XDR, M4 Pro chip, 32GB RAM', 2999.99, 2100.00, 8, 'Apple', 50, true),
('ELEC-LP-002', 'MacBook Air 15" M4', '15.3" Liquid Retina, M4 chip, 16GB RAM', 1499.99, 950.00, 8, 'Apple', 80, true),
('ELEC-LP-003', 'ThinkPad X1 Carbon Gen 12', '14" 2.8K OLED, Intel Core Ultra 9', 2199.99, 1500.00, 8, 'Lenovo', 60, true),
('ELEC-LP-004', 'Dell XPS 15', '15.6" OLED 3.5K, Intel Core i9, 32GB RAM', 2499.99, 1700.00, 8, 'Dell', 45, true),
('ELEC-LP-005', 'Surface Laptop 6', '13.5" PixelSense, Snapdragon X Elite', 1599.99, 1000.00, 8, 'Microsoft', 70, true),
('ELEC-LP-006', 'HP Spectre x360', '14" 2.8K OLED, Intel Core Ultra 7', 1799.99, 1150.00, 8, 'HP', 55, true),
('ELEC-LP-007', 'ASUS ROG Zephyrus G16', '16" QHD+ 240Hz, RTX 4090', 3299.99, 2400.00, 8, 'ASUS', 30, true),
('ELEC-LP-008', 'Chromebook Plus', '14" FHD, MediaTek, 8GB RAM', 449.99, 280.00, 8, 'Acer', 200, true),

-- Electronics - Audio
('ELEC-AU-001', 'AirPods Pro 3', 'Active noise cancellation, spatial audio', 279.99, 150.00, 9, 'Apple', 300, true),
('ELEC-AU-002', 'Sony WH-1000XM6', 'Industry-leading ANC, 40hr battery', 399.99, 220.00, 9, 'Sony', 150, true),
('ELEC-AU-003', 'Bose QuietComfort Ultra', 'Premium ANC headphones', 449.99, 260.00, 9, 'Bose', 100, true),
('ELEC-AU-004', 'Galaxy Buds3 Pro', 'ANC earbuds with AI features', 249.99, 130.00, 9, 'Samsung', 200, true),
('ELEC-AU-005', 'JBL Charge 6', 'Portable Bluetooth speaker, waterproof', 179.99, 95.00, 9, 'JBL', 250, true),
('ELEC-AU-006', 'Sonos Era 300', 'Spatial audio smart speaker', 449.99, 280.00, 9, 'Sonos', 80, true),

-- Clothing - Men's
('CLTH-MN-001', 'Classic Fit Oxford Shirt', '100% cotton, button-down collar', 59.99, 22.00, 10, 'Brooks Brothers', 400, true),
('CLTH-MN-002', 'Slim Fit Chinos', 'Stretch cotton, multiple colors', 79.99, 28.00, 10, 'Dockers', 350, true),
('CLTH-MN-003', 'Merino Wool Sweater', 'Fine gauge, crew neck', 129.99, 45.00, 10, 'J.Crew', 200, true),
('CLTH-MN-004', 'Performance Polo', 'Moisture-wicking, UPF 50+', 69.99, 25.00, 10, 'Nike', 500, true),
('CLTH-MN-005', 'Denim Jacket', 'Classic trucker style, medium wash', 98.99, 38.00, 10, 'Levi''s', 150, true),
('CLTH-MN-006', 'Quilted Vest', 'Lightweight insulation, water-resistant', 149.99, 55.00, 10, 'Patagonia', 120, true),

-- Clothing - Women's
('CLTH-WN-001', 'Silk Blouse', '100% mulberry silk, relaxed fit', 189.99, 65.00, 11, 'Theory', 180, true),
('CLTH-WN-002', 'High-Rise Skinny Jeans', 'Premium stretch denim', 128.99, 42.00, 11, 'AG Jeans', 250, true),
('CLTH-WN-003', 'Cashmere Cardigan', 'Luxe knit, oversized fit', 298.99, 110.00, 11, 'Vince', 100, true),
('CLTH-WN-004', 'Midi Wrap Dress', 'Floral print, adjustable tie', 158.99, 52.00, 11, 'Diane von Furstenberg', 150, true),
('CLTH-WN-005', 'Leather Ankle Boots', 'Block heel, side zip', 249.99, 95.00, 11, 'Sam Edelman', 200, true),
('CLTH-WN-006', 'Puffer Jacket', 'Down-filled, packable', 199.99, 72.00, 11, 'The North Face', 180, true),

-- Home & Garden - Furniture
('HOME-FR-001', 'Mid-Century Modern Sofa', '3-seater, linen upholstery', 1299.99, 580.00, 12, 'West Elm', 40, true),
('HOME-FR-002', 'Ergonomic Office Chair', 'Mesh back, lumbar support', 549.99, 220.00, 12, 'Herman Miller', 75, true),
('HOME-FR-003', 'Solid Oak Dining Table', '6-seater, expandable', 999.99, 420.00, 12, 'Crate & Barrel', 30, true),
('HOME-FR-004', 'Platform Bed Frame', 'King size, walnut finish', 799.99, 340.00, 12, 'Article', 50, true),
('HOME-FR-005', 'Modular Bookshelf', '5-tier, adjustable shelves', 349.99, 140.00, 12, 'IKEA', 100, true),
('HOME-FR-006', 'Velvet Accent Chair', 'Channel tufted, brass legs', 449.99, 180.00, 12, 'CB2', 60, true),

-- Home & Garden - Kitchen
('HOME-KT-001', 'Stand Mixer Pro', '7-quart, 10 speeds, includes attachments', 449.99, 180.00, 13, 'KitchenAid', 120, true),
('HOME-KT-002', 'Dutch Oven 6Qt', 'Enameled cast iron, multiple colors', 379.99, 150.00, 13, 'Le Creuset', 90, true),
('HOME-KT-003', 'Chef''s Knife 8"', 'German steel, full tang', 149.99, 55.00, 13, 'Wüsthof', 200, true),
('HOME-KT-004', 'Espresso Machine', 'Semi-automatic, built-in grinder', 799.99, 350.00, 13, 'Breville', 60, true),
('HOME-KT-005', 'Air Fryer XL', '6-quart, digital controls', 129.99, 48.00, 13, 'Ninja', 300, true),
('HOME-KT-006', 'Non-Stick Pan Set', '10-piece, PFOA-free', 199.99, 75.00, 13, 'All-Clad', 150, true),

-- Sports & Outdoors
('SPRT-001', 'Running Shoes Pro', 'Carbon fiber plate, responsive foam', 249.99, 95.00, 4, 'Nike', 400, true),
('SPRT-002', 'Yoga Mat Premium', '6mm thick, eco-friendly material', 89.99, 32.00, 4, 'Manduka', 250, true),
('SPRT-003', 'Fitness Tracker Ultra', 'GPS, heart rate, sleep tracking', 349.99, 140.00, 4, 'Garmin', 180, true),
('SPRT-004', 'Camping Tent 4-Person', 'Weatherproof, easy setup', 299.99, 120.00, 4, 'REI', 80, true),
('SPRT-005', 'Mountain Bike', '29" wheels, hydraulic brakes', 1499.99, 700.00, 4, 'Trek', 40, true),
('SPRT-006', 'Adjustable Dumbbells', '5-52.5 lbs, quick-change', 449.99, 200.00, 4, 'Bowflex', 100, true),
('SPRT-007', 'Golf Driver', 'Adjustable loft, carbon crown', 549.99, 250.00, 4, 'TaylorMade', 60, true),
('SPRT-008', 'Tennis Racket Pro', '100 sq in head, graphite frame', 229.99, 90.00, 4, 'Wilson', 90, true),

-- Books
('BOOK-001', 'The Midnight Library', 'Bestselling fiction novel', 16.99, 7.00, 5, 'Penguin', 500, true),
('BOOK-002', 'Atomic Habits', 'Self-improvement bestseller', 18.99, 8.00, 5, 'Avery', 600, true),
('BOOK-003', 'Project Hail Mary', 'Science fiction adventure', 17.99, 7.50, 5, 'Ballantine', 400, true),
('BOOK-004', 'The Psychology of Money', 'Personal finance wisdom', 19.99, 8.50, 5, 'Harriman House', 450, true),
('BOOK-005', 'A Court of Thorns and Roses', 'Fantasy romance', 15.99, 6.50, 5, 'Bloomsbury', 550, true),
('BOOK-006', 'Dune', 'Classic science fiction', 18.99, 8.00, 5, 'Ace', 350, true),

-- Health & Beauty
('HLTH-001', 'Retinol Serum', 'Anti-aging, 1% retinol', 68.00, 22.00, 6, 'The Ordinary', 300, true),
('HLTH-002', 'Vitamin C Moisturizer', 'Brightening, SPF 30', 54.00, 18.00, 6, 'CeraVe', 400, true),
('HLTH-003', 'Electric Toothbrush', 'Sonic, smart timer, travel case', 199.99, 80.00, 6, 'Philips', 200, true),
('HLTH-004', 'Hair Dryer Pro', 'Ionic technology, multiple speeds', 149.99, 55.00, 6, 'Dyson', 150, true),
('HLTH-005', 'Massage Gun', 'Percussion therapy, 6 attachments', 299.99, 120.00, 6, 'Theragun', 100, true),
('HLTH-006', 'Multivitamin Gummies', '90-day supply, mixed berry', 24.99, 9.00, 6, 'Olly', 600, true);

-- Add more products to reach 100
INSERT INTO products (sku, name, description, price, cost_price, category_id, brand, stock_quantity, is_active)
SELECT 
    'PROD-' || LPAD(seq::text, 3, '0'),
    'Product ' || seq,
    'Description for product ' || seq,
    ROUND((RANDOM() * 500 + 20)::numeric, 2),
    ROUND((RANDOM() * 200 + 10)::numeric, 2),
    (RANDOM() * 6 + 1)::integer,
    CASE (RANDOM() * 5)::integer
        WHEN 0 THEN 'BrandA'
        WHEN 1 THEN 'BrandB'
        WHEN 2 THEN 'BrandC'
        WHEN 3 THEN 'BrandD'
        ELSE 'BrandE'
    END,
    (RANDOM() * 500 + 10)::integer,
    true
FROM generate_series(67, 100) AS seq;

-- ============================================================================
-- SEED DATA: Customers (500 customers)
-- ============================================================================

INSERT INTO customers (email, password_hash, first_name, last_name, phone, tier, loyalty_points, is_verified, is_active, created_at)
SELECT 
    'customer' || seq || '@example.com',
    '$2a$10$dummyhashforseeding' || seq,
    CASE (seq % 20)
        WHEN 0 THEN 'James' WHEN 1 THEN 'Mary' WHEN 2 THEN 'John' WHEN 3 THEN 'Patricia'
        WHEN 4 THEN 'Robert' WHEN 5 THEN 'Jennifer' WHEN 6 THEN 'Michael' WHEN 7 THEN 'Linda'
        WHEN 8 THEN 'William' WHEN 9 THEN 'Elizabeth' WHEN 10 THEN 'David' WHEN 11 THEN 'Barbara'
        WHEN 12 THEN 'Richard' WHEN 13 THEN 'Susan' WHEN 14 THEN 'Joseph' WHEN 15 THEN 'Jessica'
        WHEN 16 THEN 'Thomas' WHEN 17 THEN 'Sarah' WHEN 18 THEN 'Charles' ELSE 'Karen'
    END,
    CASE (seq % 15)
        WHEN 0 THEN 'Smith' WHEN 1 THEN 'Johnson' WHEN 2 THEN 'Williams' WHEN 3 THEN 'Brown'
        WHEN 4 THEN 'Jones' WHEN 5 THEN 'Garcia' WHEN 6 THEN 'Miller' WHEN 7 THEN 'Davis'
        WHEN 8 THEN 'Rodriguez' WHEN 9 THEN 'Martinez' WHEN 10 THEN 'Hernandez' WHEN 11 THEN 'Lopez'
        WHEN 12 THEN 'Gonzalez' WHEN 13 THEN 'Wilson' ELSE 'Anderson'
    END,
    '+1-555-' || LPAD((seq * 17 % 10000)::text, 4, '0'),
    CASE 
        WHEN seq % 50 = 0 THEN 'platinum'
        WHEN seq % 20 = 0 THEN 'gold'
        WHEN seq % 5 = 0 THEN 'silver'
        ELSE 'bronze'
    END,
    (RANDOM() * 10000)::integer,
    true,
    true,
    CURRENT_TIMESTAMP - (RANDOM() * 730 || ' days')::interval
FROM generate_series(1, 500) AS seq;

-- ============================================================================
-- SEED DATA: Addresses
-- ============================================================================

INSERT INTO addresses (customer_id, address_type, street_address, city, state, postal_code, country, is_default)
SELECT 
    c.id,
    'shipping',
    (100 + (c.id * 7) % 9900)::text || ' ' || 
    CASE (c.id % 10) WHEN 0 THEN 'Main' WHEN 1 THEN 'Oak' WHEN 2 THEN 'Maple' WHEN 3 THEN 'Cedar'
        WHEN 4 THEN 'Pine' WHEN 5 THEN 'Elm' WHEN 6 THEN 'Park' WHEN 7 THEN 'Lake'
        WHEN 8 THEN 'River' ELSE 'Hill' END || ' ' ||
    CASE (c.id % 5) WHEN 0 THEN 'Street' WHEN 1 THEN 'Avenue' WHEN 2 THEN 'Boulevard' WHEN 3 THEN 'Drive' ELSE 'Lane' END,
    CASE (c.id % 12)
        WHEN 0 THEN 'New York' WHEN 1 THEN 'Los Angeles' WHEN 2 THEN 'Chicago' WHEN 3 THEN 'Houston'
        WHEN 4 THEN 'Phoenix' WHEN 5 THEN 'Philadelphia' WHEN 6 THEN 'San Antonio' WHEN 7 THEN 'San Diego'
        WHEN 8 THEN 'Dallas' WHEN 9 THEN 'San Jose' WHEN 10 THEN 'Austin' ELSE 'Jacksonville'
    END,
    CASE (c.id % 12)
        WHEN 0 THEN 'NY' WHEN 1 THEN 'CA' WHEN 2 THEN 'IL' WHEN 3 THEN 'TX'
        WHEN 4 THEN 'AZ' WHEN 5 THEN 'PA' WHEN 6 THEN 'TX' WHEN 7 THEN 'CA'
        WHEN 8 THEN 'TX' WHEN 9 THEN 'CA' WHEN 10 THEN 'TX' ELSE 'FL'
    END,
    LPAD((10000 + (c.id * 13) % 89999)::text, 5, '0'),
    'United States',
    true
FROM customers c;

-- ============================================================================
-- SEED DATA: Orders (5000 orders over the last 2 years)
-- ============================================================================

INSERT INTO orders (order_number, customer_id, status, shipping_address_id, subtotal, tax_amount, shipping_amount, total_amount, payment_method, payment_status, created_at)
SELECT 
    'ORD-' || TO_CHAR(CURRENT_DATE - (seq / 7 || ' days')::interval, 'YYYYMMDD') || '-' || LPAD(seq::text, 5, '0'),
    (RANDOM() * 499 + 1)::integer,
    CASE (seq % 10)
        WHEN 0 THEN 'pending'
        WHEN 1 THEN 'confirmed'
        WHEN 2 THEN 'processing'
        WHEN 3 THEN 'shipped'
        WHEN 4 THEN 'shipped'
        WHEN 5 THEN 'delivered'
        WHEN 6 THEN 'delivered'
        WHEN 7 THEN 'delivered'
        WHEN 8 THEN 'delivered'
        ELSE 'cancelled'
    END,
    (RANDOM() * 499 + 1)::integer,
    ROUND((RANDOM() * 500 + 50)::numeric, 2),
    ROUND((RANDOM() * 50 + 5)::numeric, 2),
    CASE WHEN RANDOM() > 0.3 THEN 0 ELSE ROUND((RANDOM() * 20 + 5)::numeric, 2) END,
    0, -- Will be calculated
    CASE (seq % 4)
        WHEN 0 THEN 'credit_card'
        WHEN 1 THEN 'paypal'
        WHEN 2 THEN 'apple_pay'
        ELSE 'credit_card'
    END,
    CASE 
        WHEN seq % 10 = 0 THEN 'pending'
        WHEN seq % 10 = 9 THEN 'refunded'
        ELSE 'paid'
    END,
    CURRENT_TIMESTAMP - (seq / 7 || ' days')::interval - (RANDOM() * 6 || ' hours')::interval
FROM generate_series(1, 5000) AS seq;

-- Update total_amount
UPDATE orders SET total_amount = subtotal + tax_amount + shipping_amount - discount_amount;

-- ============================================================================
-- SEED DATA: Order Items
-- ============================================================================

INSERT INTO order_items (order_id, product_id, quantity, unit_price, subtotal)
SELECT 
    o.id,
    p.id,
    (RANDOM() * 3 + 1)::integer,
    p.price,
    p.price * (RANDOM() * 3 + 1)::integer
FROM orders o
CROSS JOIN LATERAL (
    SELECT id, price FROM products 
    WHERE is_active = true 
    ORDER BY RANDOM() 
    LIMIT (RANDOM() * 4 + 1)::integer
) p;

-- ============================================================================
-- SEED DATA: Product Reviews
-- ============================================================================

INSERT INTO product_reviews (product_id, customer_id, rating, title, review_text, is_verified_purchase, is_approved, helpful_votes, created_at)
SELECT 
    p.id,
    (seq % 500) + 1,
    (RANDOM() * 4 + 1)::integer,
    CASE (seq % 10)
        WHEN 0 THEN 'Great product!'
        WHEN 1 THEN 'Exceeded expectations'
        WHEN 2 THEN 'Good value for money'
        WHEN 3 THEN 'Works as advertised'
        WHEN 4 THEN 'Highly recommend'
        WHEN 5 THEN 'Not what I expected'
        WHEN 6 THEN 'Decent quality'
        WHEN 7 THEN 'Fast shipping'
        WHEN 8 THEN 'Perfect fit'
        ELSE 'Would buy again'
    END,
    'This is a review for product. ' || 
    CASE (seq % 5)
        WHEN 0 THEN 'I love it and would definitely recommend to friends and family.'
        WHEN 1 THEN 'It arrived quickly and was exactly as described.'
        WHEN 2 THEN 'Good quality for the price point.'
        WHEN 3 THEN 'Some minor issues but overall satisfied.'
        ELSE 'Excellent customer service when I had questions.'
    END,
    RANDOM() > 0.3,
    RANDOM() > 0.1,
    (RANDOM() * 50)::integer,
    CURRENT_TIMESTAMP - (RANDOM() * 365 || ' days')::interval
FROM generate_series(1, 1500) AS seq
CROSS JOIN LATERAL (SELECT id FROM products ORDER BY RANDOM() LIMIT 1) p;

-- ============================================================================
-- SEED DATA: Inventory Movements
-- ============================================================================

INSERT INTO inventory_movements (product_id, movement_type, quantity, reference_id, notes, created_by, created_at)
SELECT 
    p.id,
    CASE (seq % 5)
        WHEN 0 THEN 'purchase'
        WHEN 1 THEN 'sale'
        WHEN 2 THEN 'sale'
        WHEN 3 THEN 'sale'
        ELSE 'adjustment'
    END,
    CASE 
        WHEN seq % 5 = 0 THEN (RANDOM() * 100 + 10)::integer
        ELSE -(RANDOM() * 5 + 1)::integer
    END,
    CASE WHEN seq % 5 IN (1,2,3) THEN 'ORD-' || (RANDOM() * 5000 + 1)::integer ELSE NULL END,
    CASE WHEN seq % 5 = 4 THEN 'Inventory count adjustment' ELSE NULL END,
    'system',
    CURRENT_TIMESTAMP - (RANDOM() * 180 || ' days')::interval
FROM generate_series(1, 10000) AS seq
CROSS JOIN LATERAL (SELECT id FROM products ORDER BY RANDOM() LIMIT 1) p;

-- ============================================================================
-- SEED DATA: Coupons
-- ============================================================================

INSERT INTO coupons (code, description, discount_type, discount_value, minimum_order_amount, max_uses, starts_at, expires_at, is_active) VALUES
('WELCOME10', 'New customer 10% off', 'percentage', 10, 50, 1000, CURRENT_TIMESTAMP - INTERVAL '30 days', CURRENT_TIMESTAMP + INTERVAL '365 days', true),
('SUMMER20', 'Summer sale 20% off', 'percentage', 20, 100, 500, CURRENT_TIMESTAMP - INTERVAL '15 days', CURRENT_TIMESTAMP + INTERVAL '45 days', true),
('FLAT25', '$25 off orders over $150', 'fixed_amount', 25, 150, 200, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '30 days', true),
('VIP15', 'VIP member discount', 'percentage', 15, NULL, NULL, CURRENT_TIMESTAMP - INTERVAL '90 days', CURRENT_TIMESTAMP + INTERVAL '275 days', true),
('FREESHIP', 'Free shipping on orders $75+', 'fixed_amount', 10, 75, 1000, CURRENT_TIMESTAMP - INTERVAL '7 days', CURRENT_TIMESTAMP + INTERVAL '23 days', true);

-- ============================================================================
-- SEED DATA: Audit Log (for growth pattern analysis)
-- ============================================================================

INSERT INTO audit_log (table_name, record_id, action, new_values, changed_by, changed_at)
SELECT 
    CASE (seq % 5)
        WHEN 0 THEN 'orders'
        WHEN 1 THEN 'customers'
        WHEN 2 THEN 'products'
        WHEN 3 THEN 'order_items'
        ELSE 'inventory_movements'
    END,
    (RANDOM() * 5000 + 1)::integer,
    CASE (seq % 3) WHEN 0 THEN 'INSERT' WHEN 1 THEN 'UPDATE' ELSE 'INSERT' END,
    '{"status": "updated"}'::jsonb,
    'system',
    CURRENT_TIMESTAMP - (seq / 100 || ' hours')::interval
FROM generate_series(1, 50000) AS seq;

-- ============================================================================
-- VIEWS: Useful aggregations (candidates for materialized views)
-- ============================================================================

-- Daily revenue view (good candidate for MV recommendation)
CREATE VIEW v_daily_revenue AS
SELECT 
    DATE(created_at) as order_date,
    COUNT(*) as total_orders,
    SUM(total_amount) as total_revenue,
    AVG(total_amount) as avg_order_value,
    COUNT(DISTINCT customer_id) as unique_customers
FROM orders
WHERE status NOT IN ('cancelled', 'refunded')
GROUP BY DATE(created_at);

-- Product performance view
CREATE VIEW v_product_performance AS
SELECT 
    p.id,
    p.name,
    p.category_id,
    COUNT(oi.id) as times_ordered,
    SUM(oi.quantity) as units_sold,
    SUM(oi.subtotal) as total_revenue,
    AVG(r.rating) as avg_rating,
    COUNT(r.id) as review_count
FROM products p
LEFT JOIN order_items oi ON p.id = oi.product_id
LEFT JOIN product_reviews r ON p.id = r.product_id
GROUP BY p.id, p.name, p.category_id;

-- Customer lifetime value view
CREATE VIEW v_customer_ltv AS
SELECT 
    c.id,
    c.email,
    c.tier,
    COUNT(o.id) as total_orders,
    SUM(o.total_amount) as lifetime_value,
    AVG(o.total_amount) as avg_order_value,
    MAX(o.created_at) as last_order_date,
    MIN(o.created_at) as first_order_date
FROM customers c
LEFT JOIN orders o ON c.id = o.customer_id AND o.status NOT IN ('cancelled', 'refunded')
GROUP BY c.id, c.email, c.tier;

-- ============================================================================
-- ANALYZE tables for statistics
-- ============================================================================

ANALYZE categories;
ANALYZE products;
ANALYZE customers;
ANALYZE addresses;
ANALYZE orders;
ANALYZE order_items;
ANALYZE product_reviews;
ANALYZE inventory_movements;
ANALYZE coupons;
ANALYZE audit_log;

-- ============================================================================
-- Grant permissions to postgres user
-- ============================================================================

GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO postgres;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO postgres;

SELECT 'Demo shop database created successfully!' AS status;
SELECT 'Tables: ' || COUNT(*)::text FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE';
SELECT 'Products: ' || COUNT(*)::text FROM products;
SELECT 'Customers: ' || COUNT(*)::text FROM customers;
SELECT 'Orders: ' || COUNT(*)::text FROM orders;
