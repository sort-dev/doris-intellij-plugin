INSERT OVERWRITE TABLE db.t PARTITION(*) SELECT * FROM s;
insert overwrite table t select * from s;
INSERT OVERWRITE TABLE t PARTITION(p1, p2) SELECT * FROM s;
