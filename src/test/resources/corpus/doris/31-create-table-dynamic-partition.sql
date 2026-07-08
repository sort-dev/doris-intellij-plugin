CREATE TABLE acme_events_daily (
    event_date DATE,
    event_id BIGINT,
    payload STRING
)
DUPLICATE KEY(event_date, event_id)
PARTITION BY RANGE(event_date) ()
DISTRIBUTED BY HASH(event_id) BUCKETS 32
PROPERTIES (
    "dynamic_partition.enable" = "true",
    "dynamic_partition.time_unit" = "DAY",
    "dynamic_partition.start" = "-30",
    "dynamic_partition.end" = "3",
    "dynamic_partition.prefix" = "p",
    "dynamic_partition.buckets" = "32",
    "replication_num" = "1"
);
