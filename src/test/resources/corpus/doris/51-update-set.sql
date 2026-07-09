UPDATE acme_orders SET order_status = 'SHIPPED', updated_at = NOW() WHERE order_id = 42;
UPDATE acme_orders SET order_status = 'STALE' WHERE updated_at < '2026-01-01 00:00:00';
