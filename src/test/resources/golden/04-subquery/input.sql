SELECT x.id,
       (SELECT MAX(y.value) FROM demo.t_metric y WHERE y.id = x.id) AS max_value
FROM (SELECT id FROM demo.t_main) x
WHERE x.id IN (SELECT id FROM demo.t_filter);
