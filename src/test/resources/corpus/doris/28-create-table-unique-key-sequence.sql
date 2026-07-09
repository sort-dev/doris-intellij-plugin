CREATE TABLE acme_orders (
    order_id BIGINT,
    order_status VARCHAR(16),
    updated_at DATETIME
)
UNIQUE KEY(order_id)
DISTRIBUTED BY HASH(order_id) BUCKETS 8
PROPERTIES (
    "replication_num" = "1",
    "function_column.sequence_col" = "updated_at",
    "enable_unique_key_merge_on_write" = "true"
);
