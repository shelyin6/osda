INSERT INTO demo.t_target (id, flag)
SELECT s.id,
       CASE WHEN s.amount > 0 THEN 'Y' ELSE 'N' END AS flag
FROM demo.t_source s
JOIN demo.t_extra e ON e.id = s.id;
