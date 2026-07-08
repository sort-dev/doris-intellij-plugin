INSERT INTO acme_events PARTITION (p20260708) (user_id, event_ts, tags) VALUES (1, '2026-07-08 00:00:00', 'a');
INSERT INTO acme_orders VALUES (1, 'NEW', DEFAULT);
INSERT INTO acme_orders (order_id, order_status) SELECT id, status FROM acme_stage;
