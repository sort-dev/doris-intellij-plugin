CREATE TABLE acme_regions (
    region VARCHAR(16) NOT NULL,
    city VARCHAR(32) NOT NULL,
    population BIGINT
)
DUPLICATE KEY(region)
PARTITION BY LIST(region, city) (
    PARTITION p_east VALUES IN (("east", "boston"), ("east", "nyc")),
    PARTITION p_west VALUES IN (("west", "sf"))
)
DISTRIBUTED BY HASH(city) BUCKETS 4
PROPERTIES ("replication_num" = "1");
