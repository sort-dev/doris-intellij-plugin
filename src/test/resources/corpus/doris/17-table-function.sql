SELECT Name, State FROM mv_infos('database'='acme_derived')
WHERE Name = 'events_watch_time_by_user_1h_mv';
