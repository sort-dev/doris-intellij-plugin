CREATE TABLE acme_events (
    user_id BIGINT,
    event_ts DATETIME,
    tags STRING,
    INDEX idx_tags(tags) USING INVERTED,
    INDEX idx_user(user_id) USING INVERTED
)
DUPLICATE KEY (user_id, event_ts)
AUTO PARTITION BY RANGE(date_trunc(event_ts, 'day')) ()
DISTRIBUTED BY HASH(user_id, event_ts) BUCKETS 8
PROPERTIES ("replication_num" = "1");
