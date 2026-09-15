package com.osda.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.osda.analysis.model.SourceType;
import java.util.List;
import org.junit.jupiter.api.Test;

class OraclePlSqlParserTest {

    private final OraclePlSqlParser parser = new OraclePlSqlParser();

    @Test
    void keepsProcedureFunctionAndPackageBodyApart() {
        String sql = """
                CREATE OR REPLACE PROCEDURE demo.p_load AS
                BEGIN
                    INSERT INTO demo.t_target SELECT * FROM demo.t_source;
                END;
                /
                CREATE OR REPLACE FUNCTION demo.f_cnt RETURN NUMBER IS
                BEGIN
                    RETURN 1;
                END;
                /
                CREATE OR REPLACE PACKAGE BODY demo.pkg AS
                    PROCEDURE p_inner IS
                    BEGIN
                        SELECT 1 FROM demo.t_inner;
                    END;
                    PROCEDURE p_outer IS
                    BEGIN
                        p_inner;
                    END;
                END demo.pkg;
                /
                """;

        ParsedFile parsed = parser.parse(sql);
        List<String> names = parsed.programUnits().stream()
                .map(unit -> unit.type() + ":" + unit.qualifiedName())
                .sorted()
                .toList();

        assertEquals(5, parsed.programUnits().size(), "应为 3 个顶层单元加 2 个嵌套子程序：" + names);
        assertTrue(names.contains(SourceType.PROCEDURE + ":DEMO.P_LOAD"), names.toString());
        assertTrue(names.contains(SourceType.FUNCTION + ":DEMO.F_CNT"), names.toString());
        assertTrue(names.contains(SourceType.PACKAGE_BODY + ":DEMO.PKG"), names.toString());
        assertTrue(names.contains(SourceType.PROCEDURE + ":DEMO.P_INNER"), names.toString());
        assertTrue(names.contains(SourceType.PROCEDURE + ":DEMO.P_OUTER"), names.toString());
    }

    @Test
    void statementBoundariesSurviveNestedBlocks() {
        String sql = """
                CREATE OR REPLACE PROCEDURE demo.p_block AS
                BEGIN
                    IF 1 = 1 THEN
                        BEGIN
                            INSERT INTO demo.t_a SELECT 1 FROM demo.t_b;
                        END;
                    END IF;
                    LOOP
                        UPDATE demo.t_c SET x = 1;
                        EXIT;
                    END LOOP;
                    DELETE FROM demo.t_d WHERE 1 = 1;
                END;
                /
                """;

        ParsedFile parsed = parser.parse(sql);
        assertEquals(1, parsed.programUnits().size());
        assertEquals(3, parsed.programUnits().get(0).statements().size());
    }

    @Test
    void keywordsInsideCommentsAndLiteralsAreIgnored() {
        String sql = """
                -- INSERT INTO demo.fake_table SELECT * FROM demo.fake_source
                /* DELETE FROM demo.commented_table; */
                BEGIN
                    INSERT INTO demo.real_target
                    SELECT 'FROM demo.literal_table UPDATE demo.other' FROM demo.real_source;
                END;
                /
                """;

        ParsedFile parsed = parser.parse(sql);
        List<String> targets = parsed.programUnits().stream()
                .flatMap(unit -> unit.statements().stream())
                .flatMap(statement -> switch (statement) {
                    case Ast.Insert insert -> java.util.stream.Stream.of(insert.target().qualified());
                    default -> java.util.stream.Stream.<String>empty();
                })
                .toList();
        assertEquals(List.of("DEMO.REAL_TARGET"), targets);
    }
}
