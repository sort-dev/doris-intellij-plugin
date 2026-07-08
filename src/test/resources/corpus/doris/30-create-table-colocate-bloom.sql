CREATE TABLE acme_users (
    user_id BIGINT,
    username VARCHAR(64),
    city VARCHAR(32)
)
UNIQUE KEY(user_id)
DISTRIBUTED BY HASH(user_id) BUCKETS 16
PROPERTIES (
    "colocate_with" = "acme_group",
    "bloom_filter_columns" = "username,city",
    "replication_num" = "1"
);
