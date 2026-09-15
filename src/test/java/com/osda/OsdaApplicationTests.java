package com.osda;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.osda.parser.OraclePlSqlParser;
import com.osda.parser.SqlAstParser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class OsdaApplicationTests {

    @Autowired
    private SqlAstParser sqlAstParser;

    @Test
    void contextLoads() {
    }

    /** The production default is the built-in parser; see docs/parser-design.md for the rationale. */
    @Test
    void defaultEngineIsTheBuiltInParser() {
        assertInstanceOf(OraclePlSqlParser.class, sqlAstParser);
    }
}
