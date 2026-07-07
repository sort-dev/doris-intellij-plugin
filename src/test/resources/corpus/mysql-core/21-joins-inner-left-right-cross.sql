SELECT *
FROM t
INNER JOIN u ON t.k = u.k
LEFT JOIN s ON t.k = s.k
RIGHT JOIN r ON t.k = r.k
CROSS JOIN w;
