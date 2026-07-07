SELECT country, COUNT(*) AS n FROM users GROUP BY country HAVING COUNT(*) > 10 ORDER BY n DESC;
