CREATE OR REPLACE PROCEDURE demo.p_load AS
BEGIN
    INSERT INTO demo.t_target SELECT id FROM demo.t_source;
END;
/
CREATE OR REPLACE FUNCTION demo.f_label(p_id IN NUMBER) RETURN VARCHAR2 IS
BEGIN
    RETURN (SELECT name FROM demo.t_label WHERE id = p_id);
END;
/
CREATE OR REPLACE PACKAGE BODY demo.pkg AS
    PROCEDURE p_inner IS
    BEGIN
        SELECT COUNT(*) INTO v_cnt FROM demo.t_inner;
    END;
    PROCEDURE p_outer IS
    BEGIN
        DELETE FROM demo.t_stage WHERE id > 0;
    END;
END pkg;
/
