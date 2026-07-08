INSERT OVERWRITE TABLE acme_derived.user_watch_video PARTITION(*)
WITH dateRange AS (
    SELECT '2026-06-30' AS _startRangeInclusive,
           '2026-07-05' AS _endRangeExclusive
), sourced AS (
    SELECT *
    FROM acme_derived.events_watch_time_by_user_1h_mv
    where window_start_at >= (SELECT _startRangeInclusive FROM dateRange) - INTERVAL 1 DAY
      AND window_start_at < (SELECT _endRangeExclusive FROM dateRange) + INTERVAL 1 DAY
), withPrevEvent AS (
    SELECT *,
        LAG(earliest_event_at) OVER (PARTITION BY watch_rollup_type, watch_rollup_key,
                                          viewing_user_client_key, video_id
                             ORDER BY earliest_event_at, latest_event_at, event_key
                            ) AS _prev_event_at
    FROM sourced
), sessionMarkPhase0 AS (
    SELECT *,
        CASE
            WHEN _prev_event_at IS NULL THEN 1
            WHEN TIMESTAMPDIFF(HOUR, _prev_event_at, earliest_event_at) > 4 THEN 1
            ELSE 0
        END AS _real_reset_flag
    FROM withPrevEvent
)
SELECT * FROM sessionMarkPhase0;
