WITH base AS (
    SELECT id FROM demo.t_base
),
label AS (
    SELECT id, name FROM demo.t_label
)
SELECT b.id, l.name
FROM base b
JOIN label l ON b.id = l.id;
