/* 历史脚本示例：INSERT INTO demo.old_target SELECT * FROM demo.old_source; */
CREATE OR REPLACE PROCEDURE demo.p_doc AS
    v_note VARCHAR2(200);
    -- GET demo.commented_table
BEGIN
    v_note := 'DELETE FROM demo.string_table';
    INSERT INTO demo.real_target SELECT col FROM demo.real_source;  -- FROM demo.tail_table
    UPDATE demo.real_other SET flag = '--not a comment';
END;
/
