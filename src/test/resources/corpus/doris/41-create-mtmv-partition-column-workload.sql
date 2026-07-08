CREATE MATERIALIZED VIEW acme_part_col_mv
BUILD IMMEDIATE
REFRESH AUTO ON MANUAL
PARTITION BY (event_date)
DISTRIBUTED BY HASH(event_date) BUCKETS 8
PROPERTIES ("workload_group" = "etl", "replication_num" = "1")
AS SELECT event_date, event_id FROM acme_events_daily;
