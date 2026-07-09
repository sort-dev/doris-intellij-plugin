CREATE TABLE acme_site_visits (
    site_id INT,
    dt DATE,
    city VARCHAR(32),
    pv BIGINT SUM DEFAULT "0",
    max_latency INT MAX DEFAULT "0",
    min_latency INT MIN DEFAULT "999999",
    last_visit DATETIME REPLACE,
    uv BITMAP BITMAP_UNION,
    uniq_users HLL HLL_UNION
)
AGGREGATE KEY(site_id, dt, city)
DISTRIBUTED BY HASH(site_id) BUCKETS 10
PROPERTIES ("replication_num" = "1");
