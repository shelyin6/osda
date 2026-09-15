package com.osda.config;

import com.osda.parser.OraclePlSqlParser;
import com.osda.parser.SqlAstParser;
import com.osda.parser.SqlAstParserFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Selects the syntax tree producer used by the analyzer.
 *
 * <p>{@code osda.parser-engine=native} (default) uses the built-in recursive descent parser.
 * {@code antlr} and {@code hybrid} require a build made with {@code -Pwith-antlr}; in a release
 * build without that profile they transparently degrade to the built-in parser.
 */
@Configuration
public class ParserConfiguration {

    @Bean
    public SqlAstParser sqlAstParser(OsdaProperties properties) {
        return SqlAstParserFactory.create(properties.getParserEngine());
    }
}
