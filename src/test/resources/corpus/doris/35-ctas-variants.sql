CREATE TABLE acme_summary
PROPERTIES ("replication_num" = "1")
AS SELECT user_id, COUNT(*) AS cnt FROM acme_events GROUP BY user_id;
CREATE TABLE acme_summary2 (uid, total)
DISTRIBUTED BY HASH(uid) BUCKETS 4
PROPERTIES ("replication_num" = "1")
AS SELECT user_id, SUM(amount) FROM acme_orders GROUP BY user_id;
