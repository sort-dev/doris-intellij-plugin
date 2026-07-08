select count(*) from acme_import.events
where event_at >= '2026-06-29'
  AND INSTR(CAST(CAST(event_value AS JSON) AS STRING), 'ephemeral') > 0
limit 1000;
