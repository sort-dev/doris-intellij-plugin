SELECT a, (SELECT COUNT(*) FROM u WHERE u.t_id = t.id) AS n FROM t;
