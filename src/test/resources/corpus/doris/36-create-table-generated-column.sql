CREATE TABLE acme_gen (
    a INT,
    b INT,
    c INT AS (a + b)
)
DUPLICATE KEY(a)
DISTRIBUTED BY HASH(a) BUCKETS 4
PROPERTIES ("replication_num" = "1");
