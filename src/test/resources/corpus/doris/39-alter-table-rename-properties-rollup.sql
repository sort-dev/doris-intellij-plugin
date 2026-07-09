ALTER TABLE acme_events RENAME acme_events_v2;
ALTER TABLE acme_events SET ("dynamic_partition.enable" = "false");
ALTER TABLE acme_events ADD ROLLUP r_user (user_id, event_ts);
ALTER TABLE acme_events DROP ROLLUP r_user;
