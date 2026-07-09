CREATE TABLE acme_metrics (
    dt DATE,
    shard_id INT,
    metric_value DOUBLE
)
DUPLICATE KEY(dt, shard_id)
PARTITION BY RANGE(dt, shard_id) (
    PARTITION p202601_a VALUES [("2026-01-01", "0"), ("2026-02-01", "100")),
    PARTITION p202602_a VALUES LESS THAN ("2026-03-01", "200")
)
DISTRIBUTED BY HASH(shard_id) BUCKETS 8
PROPERTIES ("replication_num" = "1");
