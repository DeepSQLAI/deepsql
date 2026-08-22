-- Generate representative OLTP/analytics traffic into pg_stat_statements.
-- Intentionally includes sequential-scan-prone filters (status, date ranges,
-- unpaid invoices) and joins across ambiguous customer/order concepts.

SELECT pg_stat_statements_reset();

-- Hot path: open orders by customer (missing composite index)
DO $$
DECLARE i int;
BEGIN
  FOR i IN 1..40 LOOP
    PERFORM o.id, o.status, o.total_amount
    FROM sales.orders o
    WHERE o.customer_id = 100 + i
      AND o.status IN ('PLACED','SHIPPED')
      AND o.is_test = FALSE;
  END LOOP;
END $$;

-- Revenue-ish query people ask in chat (should exclude cancelled — business rule)
DO $$
DECLARE i int;
BEGIN
  FOR i IN 1..25 LOOP
    PERFORM date_trunc('week', o.placed_at) AS wk, SUM(o.total_amount)
    FROM sales.orders o
    WHERE o.placed_at > NOW() - INTERVAL '180 days'
      AND o.status = 'DELIVERED'
      AND o.is_test = FALSE
    GROUP BY 1
    ORDER BY 1 DESC;
  END LOOP;
END $$;

-- Ambiguous "status" filter without table qualification style (lines)
DO $$
BEGIN
  FOR i IN 1..30 LOOP
    PERFORM ol.id, ol.line_amount
    FROM sales.order_lines ol
    WHERE ol.product_id = 50 + (i % 200)
      AND ol.status = 'FULFILLED';
  END LOOP;
END $$;

-- Finance AR aging (customer_id on invoices unindexed)
DO $$
BEGIN
  FOR i IN 1..30 LOOP
    PERFORM inv.customer_id, SUM(inv.amount)
    FROM finance.invoices inv
    WHERE inv.status IN ('OPEN','DISPUTED')
      AND inv.due_at < NOW()
    GROUP BY inv.customer_id
    ORDER BY SUM(inv.amount) DESC
    LIMIT 50;
  END LOOP;
END $$;

-- Payments join invoices
DO $$
BEGIN
  FOR i IN 1..20 LOOP
    PERFORM p.payment_number, p.amount, inv.invoice_number, inv.status
    FROM finance.payments p
    JOIN finance.invoices inv ON inv.id = p.invoice_id
    WHERE p.status = 'CLEARED'
      AND p.paid_at > NOW() - INTERVAL '90 days'
    LIMIT 200;
  END LOOP;
END $$;

-- Inventory reorder candidates
DO $$
BEGIN
  FOR i IN 1..20 LOOP
    PERFORM it.item_code, it.name, SUM(CASE WHEN sm.move_type='OUT' THEN sm.quantity ELSE 0 END) AS out_qty
    FROM inventory.items it
    JOIN inventory.stock_moves sm ON sm.item_id = it.id
    WHERE it.status = 'ACTIVE'
      AND sm.moved_at > NOW() - INTERVAL '60 days'
    GROUP BY it.item_code, it.name, it.reorder_point
    HAVING SUM(CASE WHEN sm.move_type='OUT' THEN sm.quantity ELSE 0 END) > it.reorder_point
    LIMIT 100;
  END LOOP;
END $$;

-- Legacy header scans (should be discouraged by business rule)
DO $$
BEGIN
  FOR i IN 1..15 LOOP
    PERFORM amount FROM sales.order_header WHERE order_dt > CURRENT_DATE - 30;
  END LOOP;
END $$;

-- HR payroll (sensitive path — access policy should block non-HR users)
DO $$
BEGIN
  FOR i IN 1..10 LOOP
    PERFORM e.name, e.department, pr.amount, pr.status
    FROM hr.employees e
    JOIN hr.payroll_runs pr ON pr.employee_id = e.id
    WHERE e.status = 'ACTIVE'
      AND pr.status = 'PAID'
    LIMIT 100;
  END LOOP;
END $$;

-- Cross-schema customer ambiguity path
DO $$
BEGIN
  FOR i IN 1..20 LOOP
    PERFORM c.name, a.tier, COUNT(o.id)
    FROM sales.customers c
    LEFT JOIN crm.accounts a ON a.id = c.crm_account_id AND a.is_deleted = FALSE
    LEFT JOIN sales.orders o ON o.customer_id = c.id AND o.status <> 'CANCELLED'
    WHERE c.status = 'ACTIVE'
      AND c.country_code = (ARRAY['US','GB','DE'])[1 + (i % 3)]
    GROUP BY c.name, a.tier
    ORDER BY COUNT(o.id) DESC
    LIMIT 25;
  END LOOP;
END $$;
