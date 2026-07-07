WITH sourced AS (
    SELECT *
    FROM acme_derived.events_watch_time_by_user_1h_mv
    where window_start_at >= '2026-06-30'
      AND window_start_at < '2026-07-02'
)
SELECT *,
    LAG(earliest_event_at) OVER (PARTITION BY watch_rollup_type, watch_rollup_key, viewing_user_client_key,
                                      correlation_search_correlator_key, video_id,
                                      video_is_placeholder,
                                      video_paywall_type_id
                         ORDER BY earliest_event_at, latest_event_at, event_key
                        ) AS _prev_event_at
FROM sourced;
