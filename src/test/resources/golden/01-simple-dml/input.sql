INSERT INTO demo.t_target (id, name) SELECT s.id, s.name FROM demo.t_source s;

UPDATE demo.t_other o SET o.flag = 'Y' WHERE o.id IN (SELECT id FROM demo.t_lookup);

DELETE FROM demo.t_temp WHERE id = 1;
