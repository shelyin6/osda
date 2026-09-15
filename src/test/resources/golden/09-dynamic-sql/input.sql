CREATE OR REPLACE PROCEDURE demo.p_dyn AS
    v_sql VARCHAR2(500);
    v_table VARCHAR2(60) := 'demo.t_dynamic';
BEGIN
    EXECUTE IMMEDIATE 'INSERT INTO demo.t_const_target SELECT * FROM demo.t_const_source';
    v_sql := 'UPDATE demo.t_other SET flag = ''Y''';
    EXECUTE IMMEDIATE v_sql;
    EXECUTE IMMEDIATE 'DELETE FROM ' || v_table || ' WHERE 1 = 1';
END;
/
