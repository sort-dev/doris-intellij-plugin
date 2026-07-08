REFRESH TABLE hive_archive.acme_archive.events;
WARM UP COMPUTE GROUP etl
WITH TABLE acme_derived.events_watch_time_by_user_1h_mv
    AND TABLE acme_derived.events_watch_time_window_1d_mv;
CREATE DATABASE hive_tpa6.outbox
PROPERTIES (
    'location' = 's3://outbox-starrocks/outbox'
);
CREATE TABLE t2 (id BIGINT, name VARCHAR(64))
DISTRIBUTED BY HASH(id) BUCKETS 10 PROPERTIES ("replication_num" = "1");
