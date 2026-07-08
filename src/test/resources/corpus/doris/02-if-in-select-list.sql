SELECT
    date_trunc(c.event_at, 'hour')                  AS window_start_at,
    c.surface,
    IF(c.user_id IS NULL OR c.user_id = 0, 'A', 'U')  AS user_rollup_type,
    c.user_client_name,
    c.user_is_premium                                 AS is_premium,
    re.engine,
    COUNT(*)                                          AS click_count
FROM acme_import.events_clicks_to_video c
JOIN acme_bi.recommender_engines re
    ON  array_contains(c.experiments, 'src_v1_homepage-player-lineup')
WHERE c.possible_bot_user_agent = 0
  AND c.possible_bot_cidr = 0
GROUP BY 1, 2, 3, 4, 5, 6;
