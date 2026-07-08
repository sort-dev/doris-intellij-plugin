SELECT REGEXP(user_agent, '(?i)bot|crawler') AS is_bot FROM events;
