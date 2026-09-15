CREATE OR REPLACE PROCEDURE demo.p_rw AS
BEGIN
    INSERT INTO demo.t_hist SELECT * FROM demo.t_hist WHERE id > 0;
END;
/
