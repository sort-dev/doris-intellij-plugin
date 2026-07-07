CREATE OR REPLACE VIEW acme_bi.unique_experiments AS
SELECT tag, COUNT(*) AS row_count
FROM acme_derived.events_watch_time_window_1d
LATERAL VIEW EXPLODE(experiments) e AS tag
WHERE window_start_at >= CURRENT_TIMESTAMP - interval 6 month
GROUP BY tag
HAVING row_count > 1000
ORDER BY row_count DESC;
