CREATE OR REPLACE VIEW v_modern AS
SELECT * EXCEPT (raw_json),
       REGEXP(user_agent, '(?i)bot|crawler') AS is_bot,
       FROM_SECOND(db_ts) AS event_at,
       JSON_OBJECT('ip', user_ip, 'os', user_os) AS info
FROM events;
