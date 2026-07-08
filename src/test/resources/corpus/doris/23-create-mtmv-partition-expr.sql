CREATE MATERIALIZED VIEW acme_watch_time_daily_mv
(user_id, watch_day, watch_seconds)
BUILD IMMEDIATE REFRESH AUTO ON MANUAL
DUPLICATE KEY (user_id, watch_day)
PARTITION BY (date_trunc(watch_day, 'day'))
DISTRIBUTED BY HASH(user_id, watch_day) BUCKETS 8
PROPERTIES ("replication_num" = "1")
AS
SELECT
    user_id AS user_id,
    date_trunc(event_ts, 'day') AS watch_day,
    sum(watch_seconds) AS watch_seconds
FROM acme_events
GROUP BY user_id, date_trunc(event_ts, 'day');
