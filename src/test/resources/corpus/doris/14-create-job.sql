CREATE JOB backfill_2026_07_0b
ON SCHEDULE AT CURRENT_TIMESTAMP
DO
INSERT INTO acme_import.events
SELECT /*+ SET_VAR(query_timeout = 28800) */ * EXCEPT(year,month,day)
FROM  acme_pipeline.pipe_ingest_event_at_landing
WHERE year = 2026 AND month = 7 AND day in (3,4,5);
