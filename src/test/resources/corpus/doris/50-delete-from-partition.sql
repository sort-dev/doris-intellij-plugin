DELETE FROM acme_events PARTITION (p20260701) WHERE event_ts < '2026-07-01 00:00:00';
DELETE FROM acme_events_daily PARTITIONS (p20260601, p20260602) WHERE event_id > 0;
DELETE FROM acme_orders WHERE order_status = 'CANCELLED';
