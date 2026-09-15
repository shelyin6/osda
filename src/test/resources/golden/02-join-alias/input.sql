SELECT a.id, b.name
FROM demo.t_a a
JOIN demo.t_b b ON a.id = b.id
LEFT JOIN demo.t_c AS c ON b.id = c.id;
