#!/usr/bin/env python3
"""
Generate comprehensive MySQL test data for DBA Agent testing.
This script generates SQL INSERT statements for loading test data.
"""

import random
from datetime import datetime, timedelta
import json

def generate_customers(count=10000):
    """Generate customer INSERT statements"""
    statements = []
    countries = ['US', 'UK', 'CA', 'AU', 'DE', 'FR', 'JP', 'IN', 'BR', 'MX']
    cities = ['New York', 'London', 'Toronto', 'Sydney', 'Berlin', 'Paris', 'Tokyo', 'Mumbai', 'São Paulo', 'Mexico City']
    statuses = ['active', 'active', 'inactive']  # Weighted towards active
    
    for i in range(1, count + 1):
        first_name = f"Customer{i}"
        last_name = f"Last{i}"
        email = f"customer{i}@example.com"
        phone = f"555-{random.randint(1000, 9999)}"
        dob = datetime.now() - timedelta(days=random.randint(18*365, 80*365))
        reg_date = datetime.now() - timedelta(days=random.randint(0, 365))
        last_login = datetime.now() - timedelta(days=random.randint(0, 30))
        status = random.choice(statuses)
        country = random.choice(countries)
        city = random.choice(cities)
        
        statements.append(
            f"INSERT INTO customers (first_name, last_name, email, phone, date_of_birth, "
            f"registration_date, last_login, status, country_code, city) VALUES "
            f"('{first_name}', '{last_name}', '{email}', '{phone}', '{dob.date()}', "
            f"'{reg_date}', '{last_login}', '{status}', '{country}', '{city}');"
        )
    
    return statements

def generate_products(count=5000):
    """Generate product INSERT statements"""
    statements = []
    prefixes = ['Premium', 'Standard', 'Basic', 'Deluxe', 'Pro', 'Lite', 'Ultra', 'Max', 'Plus', 'Elite']
    
    for i in range(1, count + 1):
        sku = f"SKU-{i:06d}"
        name = f"Product {i} - {random.choice(prefixes)}"
        category_id = random.randint(1, 12)
        brand_id = random.randint(1, 8)
        price = round(random.uniform(10, 2000), 2)
        cost = round(price * random.uniform(0.3, 0.7), 2)
        stock = random.randint(0, 1000)
        min_stock = random.randint(10, 50)
        is_active = random.random() > 0.1  # 90% active
        weight = round(random.uniform(0.1, 50), 2)
        
        statements.append(
            f"INSERT INTO products (sku, product_name, category_id, brand_id, price, cost, "
            f"stock_quantity, min_stock_level, description, is_active, weight_kg) VALUES "
            f"('{sku}', '{name}', {category_id}, {brand_id}, {price}, {cost}, "
            f"{stock}, {min_stock}, 'Description for product {i}', {is_active}, {weight});"
        )
    
    return statements

def generate_orders(count=50000):
    """Generate order INSERT statements"""
    statements = []
    statuses = ['pending', 'processing', 'shipped', 'delivered', 'cancelled']
    payment_statuses = ['pending', 'paid', 'paid', 'paid']  # Weighted towards paid
    start_date = datetime.now() - timedelta(days=730)
    
    for i in range(1, count + 1):
        customer_id = random.randint(1, 10000)
        order_number = f"ORD-{i:08d}"
        order_date = start_date + timedelta(days=random.randint(0, 730))
        status = random.choice(statuses)
        total = round(random.uniform(10, 2000), 2)
        shipping = round(random.uniform(5, 50), 2)
        tax = round(total * random.uniform(0.05, 0.15), 2)
        discount = round(random.uniform(0, 100), 2)
        payment_status = random.choice(payment_statuses)
        
        statements.append(
            f"INSERT INTO orders (customer_id, order_number, order_date, status, total_amount, "
            f"shipping_cost, tax_amount, discount_amount, payment_status) VALUES "
            f"({customer_id}, '{order_number}', '{order_date}', '{status}', {total}, "
            f"{shipping}, {tax}, {discount}, '{payment_status}');"
        )
    
    return statements

def generate_order_items(count=150000):
    """Generate order item INSERT statements"""
    statements = []
    
    for i in range(1, count + 1):
        order_id = random.randint(1, 50000)
        product_id = random.randint(1, 5000)
        quantity = random.randint(1, 5)
        unit_price = round(random.uniform(10, 500), 2)
        discount = round(random.uniform(0, 20), 2)
        total_price = round(unit_price * quantity * (1 - discount/100), 2)
        
        statements.append(
            f"INSERT INTO order_items (order_id, product_id, quantity, unit_price, "
            f"discount_percent, total_price) VALUES "
            f"({order_id}, {product_id}, {quantity}, {unit_price}, {discount}, {total_price});"
        )
    
    return statements

def generate_sales_transactions(count=200000):
    """Generate sales transaction INSERT statements"""
    statements = []
    payment_methods = ['credit_card', 'debit_card', 'paypal', 'bank_transfer']
    regions = ['North', 'South', 'East', 'West', 'Central', 'Northeast', 'Northwest', 'Southeast', 'Southwest', 'International']
    start_date = datetime.now() - timedelta(days=730)
    
    for i in range(1, count + 1):
        order_id = random.randint(1, 50000)
        customer_id = random.randint(1, 10000)
        product_id = random.randint(1, 5000)
        transaction_date = start_date + timedelta(days=random.randint(0, 730))
        quantity = random.randint(1, 5)
        unit_price = round(random.uniform(10, 500), 2)
        total_amount = round(unit_price * quantity, 2)
        payment_method = random.choice(payment_methods)
        region = random.choice(regions)
        sales_rep_id = random.randint(1, 100)
        
        statements.append(
            f"INSERT INTO sales_transactions (order_id, customer_id, product_id, transaction_date, "
            f"quantity, unit_price, total_amount, payment_method, region, sales_rep_id) VALUES "
            f"({order_id}, {customer_id}, {product_id}, '{transaction_date}', {quantity}, "
            f"{unit_price}, {total_amount}, '{payment_method}', '{region}', {sales_rep_id});"
        )
    
    return statements

def generate_application_logs(count=500000):
    """Generate application log INSERT statements"""
    statements = []
    action_types = ['LOGIN', 'LOGOUT', 'VIEW_PRODUCT', 'ADD_TO_CART', 'CHECKOUT', 'SEARCH', 'UPDATE_PROFILE', 'VIEW_ORDER', 'CANCEL_ORDER', 'REVIEW']
    endpoints = ['/api/products', '/api/orders', '/api/cart', '/api/auth/login', '/api/search', '/api/profile', '/api/reviews', '/api/checkout', '/api/payment', '/api/shipping']
    http_methods = ['GET', 'POST', 'PUT', 'DELETE']
    status_codes = [200, 200, 200, 200, 200, 404, 500, 401, 403, 400]  # Weighted towards 200
    log_levels = ['DEBUG', 'INFO', 'INFO', 'WARN', 'ERROR']
    start_date = datetime.now() - timedelta(days=90)
    
    for i in range(1, count + 1):
        user_id = random.randint(1, 10000) if random.random() > 0.3 else None
        session_id = f"sess_{random.randint(100000, 999999)}"
        action_type = random.choice(action_types)
        endpoint = random.choice(endpoints)
        http_method = random.choice(http_methods)
        status_code = random.choice(status_codes)
        response_time = random.randint(10, 5000)
        ip = f"{random.randint(1, 255)}.{random.randint(1, 255)}.{random.randint(1, 255)}.{random.randint(1, 255)}"
        log_level = random.choice(log_levels)
        created_at = start_date + timedelta(seconds=random.randint(0, 7776000))
        
        user_id_str = f"{user_id}" if user_id else "NULL"
        statements.append(
            f"INSERT INTO application_logs (user_id, session_id, action_type, endpoint, http_method, "
            f"status_code, response_time_ms, ip_address, log_level, created_at) VALUES "
            f"({user_id_str}, '{session_id}', '{action_type}', '{endpoint}', '{http_method}', "
            f"{status_code}, {response_time}, '{ip}', '{log_level}', '{created_at}');"
        )
    
    return statements

def generate_audit_trail(count=100000):
    """Generate audit trail INSERT statements"""
    statements = []
    table_names = ['customers', 'products', 'orders', 'order_items', 'categories', 'brands', 'reviews', 'inventory', 'payments', 'sessions']
    action_types = ['INSERT', 'UPDATE', 'DELETE']
    start_date = datetime.now() - timedelta(days=365)
    
    for i in range(1, count + 1):
        table_name = random.choice(table_names)
        record_id = random.randint(1, 100000)
        action_type = random.choice(action_types)
        user_id = random.randint(1, 1000)
        old_value = json.dumps({'field1': f'old_value_{i}'})
        new_value = json.dumps({'field1': f'new_value_{i}'})
        ip = f"{random.randint(1, 255)}.{random.randint(1, 255)}.{random.randint(1, 255)}.{random.randint(1, 255)}"
        created_at = start_date + timedelta(seconds=random.randint(0, 31536000))
        
        statements.append(
            f"INSERT INTO audit_trail (table_name, record_id, action_type, user_id, old_values, "
            f"new_values, ip_address, created_at) VALUES "
            f"('{table_name}', '{record_id}', '{action_type}', {user_id}, '{old_value}', "
            f"'{new_value}', '{ip}', '{created_at}');"
        )
    
    return statements

def generate_daily_sales_summary():
    """Generate daily sales summary INSERT statements"""
    statements = []
    start_date = datetime.now() - timedelta(days=730)
    current_date = start_date
    
    while current_date <= datetime.now():
        for product_id in range(1, 101):  # Top 100 products
            category_id = random.randint(1, 12)
            total_quantity = random.randint(0, 100)
            total_revenue = round(random.uniform(100, 10000), 2)
            total_orders = random.randint(1, 50)
            avg_order_value = round(random.uniform(20, 500), 2)
            
            statements.append(
                f"INSERT INTO daily_sales_summary (summary_date, product_id, category_id, "
                f"total_quantity, total_revenue, total_orders, avg_order_value) VALUES "
                f"('{current_date.date()}', {product_id}, {category_id}, {total_quantity}, "
                f"{total_revenue}, {total_orders}, {avg_order_value});"
            )
        current_date += timedelta(days=1)
    
    return statements

def generate_customer_activity(count=200000):
    """Generate customer activity INSERT statements"""
    statements = []
    activity_types = ['VIEW', 'SEARCH', 'CART_ADD', 'CART_REMOVE', 'WISHLIST_ADD', 'PURCHASE', 'REVIEW', 'SHARE']
    start_date = datetime.now() - timedelta(days=180)
    
    for i in range(1, count + 1):
        customer_id = random.randint(1, 10000)
        activity_type = random.choice(activity_types)
        activity_date = start_date + timedelta(seconds=random.randint(0, 15552000))
        product_id = random.randint(1, 5000) if random.random() > 0.3 else None
        order_id = random.randint(1, 50000) if random.random() > 0.7 else None
        
        product_id_str = f"{product_id}" if product_id else "NULL"
        order_id_str = f"{order_id}" if order_id else "NULL"
        
        statements.append(
            f"INSERT INTO customer_activity (customer_id, activity_type, activity_date, product_id, order_id) "
            f"VALUES ({customer_id}, '{activity_type}', '{activity_date}', {product_id_str}, {order_id_str});"
        )
    
    return statements

def generate_product_reviews(count=100000):
    """Generate product review INSERT statements"""
    statements = []
    review_titles = ['Great product!', 'Good value.', 'Average quality.', 'Not recommended.', 'Excellent!']
    
    for i in range(1, count + 1):
        product_id = random.randint(1, 5000)
        customer_id = random.randint(1, 10000)
        rating = random.randint(1, 5)
        review_title = random.choice(review_titles)
        review_text = f"This is a review for product. {review_title}"
        is_verified = random.random() > 0.5
        helpful_count = random.randint(0, 100)
        
        statements.append(
            f"INSERT INTO product_reviews (product_id, customer_id, rating, review_title, "
            f"review_text, is_verified_purchase, helpful_count) VALUES "
            f"({product_id}, {customer_id}, {rating}, '{review_title}', '{review_text}', "
            f"{is_verified}, {helpful_count});"
        )
    
    return statements

def generate_inventory_movements(count=150000):
    """Generate inventory movement INSERT statements"""
    statements = []
    movement_types = ['IN', 'OUT', 'ADJUSTMENT', 'TRANSFER']
    start_date = datetime.now() - timedelta(days=365)
    
    for i in range(1, count + 1):
        product_id = random.randint(1, 5000)
        warehouse_id = random.randint(1, 10)
        movement_type = random.choice(movement_types)
        quantity = random.randint(-100, 100)
        reference_number = f"REF-{i:08d}"
        movement_date = start_date + timedelta(seconds=random.randint(0, 31536000))
        user_id = random.randint(1, 100)
        
        statements.append(
            f"INSERT INTO inventory_movements (product_id, warehouse_id, movement_type, quantity, "
            f"reference_number, movement_date, user_id) VALUES "
            f"({product_id}, {warehouse_id}, '{movement_type}', {quantity}, '{reference_number}', "
            f"'{movement_date}', {user_id});"
        )
    
    return statements

def main():
    """Generate all test data SQL statements"""
    print("-- Comprehensive MySQL Test Data")
    print("-- Generated by generate_test_data.py")
    print("-- Total records: ~1.5 million+\n")
    
    print("-- Categories")
    print("INSERT INTO categories (category_name, parent_category_id, slug, description, display_order, is_active) VALUES")
    categories = [
        ("Electronics", None, "electronics", "Electronic devices", 1),
        ("Computers", 1, "computers", "Desktop and laptop computers", 1),
        ("Laptops", 2, "laptops", "Portable computers", 1),
        ("Desktops", 2, "desktops", "Desktop computers", 2),
        ("Smartphones", 1, "smartphones", "Mobile phones", 2),
        ("Tablets", 1, "tablets", "Tablet devices", 3),
        ("Clothing", None, "clothing", "Apparel", 2),
        ("Men", 7, "men", "Men's clothing", 1),
        ("Women", 7, "women", "Women's clothing", 2),
        ("Home & Garden", None, "home-garden", "Home improvement", 3),
        ("Furniture", 10, "furniture", "Home furniture", 1),
        ("Tools", 10, "tools", "Home improvement tools", 2),
    ]
    for cat in categories:
        parent = f"{cat[1]}" if cat[1] else "NULL"
        print(f"('{cat[0]}', {parent}, '{cat[2]}', '{cat[3]}', {cat[4]}, TRUE),")
    print(";\n")
    
    print("-- Brands")
    print("INSERT INTO brands (brand_name, country, website) VALUES")
    brands = [
        ("TechCorp", "USA", "https://techcorp.com"),
        ("GadgetPro", "USA", "https://gadgetpro.com"),
        ("StyleBrand", "UK", "https://stylebrand.co.uk"),
        ("HomeDesign", "Germany", "https://homedesign.de"),
        ("ElectroMax", "Japan", "https://electromax.jp"),
        ("FashionHub", "Italy", "https://fashionhub.it"),
        ("ToolMaster", "USA", "https://toolmaster.com"),
        ("SmartHome", "USA", "https://smarthome.com"),
    ]
    for brand in brands:
        print(f"('{brand[0]}', '{brand[1]}', '{brand[2]}'),")
    print(";\n")
    
    # Generate data in batches to avoid memory issues
    batch_size = 1000
    
    print(f"-- Customers ({10000} records)")
    customers = generate_customers(10000)
    for i in range(0, len(customers), batch_size):
        batch = customers[i:i+batch_size]
        print("\n".join(batch))
        print()
    
    print(f"-- Products ({5000} records)")
    products = generate_products(5000)
    for i in range(0, len(products), batch_size):
        batch = products[i:i+batch_size]
        print("\n".join(batch))
        print()
    
    print(f"-- Orders ({50000} records)")
    orders = generate_orders(50000)
    for i in range(0, len(orders), batch_size):
        batch = orders[i:i+batch_size]
        print("\n".join(batch))
        print()
    
    print(f"-- Order Items ({150000} records)")
    order_items = generate_order_items(150000)
    for i in range(0, len(order_items), batch_size):
        batch = order_items[i:i+batch_size]
        print("\n".join(batch))
        print()
    
    print(f"-- Sales Transactions ({200000} records)")
    transactions = generate_sales_transactions(200000)
    for i in range(0, len(transactions), batch_size):
        batch = transactions[i:i+batch_size]
        print("\n".join(batch))
        print()
    
    print(f"-- Application Logs ({500000} records)")
    logs = generate_application_logs(500000)
    for i in range(0, len(logs), batch_size):
        batch = logs[i:i+batch_size]
        print("\n".join(batch))
        print()
    
    print(f"-- Audit Trail ({100000} records)")
    audits = generate_audit_trail(100000)
    for i in range(0, len(audits), batch_size):
        batch = audits[i:i+batch_size]
        print("\n".join(batch))
        print()
    
    print("-- Daily Sales Summary (~73,000 records)")
    summaries = generate_daily_sales_summary()
    for i in range(0, len(summaries), batch_size):
        batch = summaries[i:i+batch_size]
        print("\n".join(batch))
        print()
    
    print(f"-- Customer Activity ({200000} records)")
    activities = generate_customer_activity(200000)
    for i in range(0, len(activities), batch_size):
        batch = activities[i:i+batch_size]
        print("\n".join(batch))
        print()
    
    print(f"-- Product Reviews ({100000} records)")
    reviews = generate_product_reviews(100000)
    for i in range(0, len(reviews), batch_size):
        batch = reviews[i:i+batch_size]
        print("\n".join(batch))
        print()
    
    print(f"-- Inventory Movements ({150000} records)")
    movements = generate_inventory_movements(150000)
    for i in range(0, len(movements), batch_size):
        batch = movements[i:i+batch_size]
        print("\n".join(batch))
        print()

if __name__ == "__main__":
    main()
