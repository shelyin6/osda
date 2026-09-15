package com.osda.config;

import com.osda.parser.OraclePlSqlParser;
import com.osda.parser.HybridSqlAstParser;
import com.osda.parser.SqlAstParser;
import com.osda.parser.antlr.AntlrSqlAstParser;
import java.util.Locale;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Selects the syntax tree producer used by the analyzer.
 *
 * <p>{@code osda.parser-engine=native} (default) uses the built-in recursive descent parser;
 * {@code antlr} uses the vendored Oracle PL/SQL grammar. Both implement {@link SqlAstParser},
 * so the extraction, lineage, REST and export layers are unaffected by the choice.
 */
@Configuration
public class ParserConfiguration {

    @Bean
    public SqlAstParser sqlAstParser(OsdaProperties properties) {
        String engine = properties.getParserEngine() == null
                ? "native"
                : properties.getParserEngine().trim().toLowerCase(Locale.ROOT);
        return switch (engine) {
            case "antlr" -> new AntlrSqlAstParser();
            case "native" -> new OraclePlSqlParser();
            default -> new HybridSqlAstParser();
        };
    }
}
