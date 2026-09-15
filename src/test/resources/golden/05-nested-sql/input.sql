SELECT o.id
FROM demo.t_outer o
WHERE EXISTS (
    SELECT 1
    FROM demo.t_middle m
    JOIN (
        SELECT id FROM demo.t_inner i
        WHERE i.flag IN (SELECT flag FROM demo.t_deep)
    ) d ON d.id = m.id
    WHERE m.id = o.id
);
