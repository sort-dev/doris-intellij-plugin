WITH dateRange AS (SELECT '2026-06-30' AS d)
SELECT * FROM events
WHERE created_at >= (SELECT d FROM dateRange) - INTERVAL 1 DAY;
