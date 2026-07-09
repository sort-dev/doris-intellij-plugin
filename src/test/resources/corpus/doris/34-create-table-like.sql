CREATE TABLE acme_events_copy LIKE acme_events;
CREATE TABLE acme_events_copy2 LIKE acme_events WITH ROLLUP (r_user, r_daily);
