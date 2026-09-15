package com.osda.parser;

import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates the configured parser engine.
 *
 * <p>The release build intentionally excludes the ANTLR grammar and runtime: they are only added by
 * the {@code with-antlr} Maven profile. Engines that are not part of the current build are therefore
 * loaded reflectively, and a missing engine degrades to the built-in parser with a clear warning
 * instead of failing to start.
 */
public final class SqlAstParserFactory {

    public static final String NATIVE = "native";
    public static final String ANTLR = "antlr";
    public static final String HYBRID = "hybrid";

    private static final Logger LOG = LoggerFactory.getLogger(SqlAstParserFactory.class);
    private static final String ANTLR_PARSER = "com.osda.parser.antlr.AntlrSqlAstParser";
    private static final String HYBRID_PARSER = "com.osda.parser.HybridSqlAstParser";

    private SqlAstParserFactory() {
    }

    public static SqlAstParser create(String engine) {
        String normalized = engine == null || engine.isBlank()
                ? NATIVE
                : engine.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case ANTLR -> load(ANTLR_PARSER, ANTLR);
            case HYBRID -> load(HYBRID_PARSER, HYBRID);
            default -> new OraclePlSqlParser();
        };
    }

    private static SqlAstParser load(String className, String engine) {
        try {
            Class<?> type = Class.forName(className);
            return (SqlAstParser) type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | LinkageError exception) {
            LOG.warn("解析引擎 {} 未包含在当前构建中（{}），已回退到内置解析器；"
                    + "如需启用请使用 -Pwith-antlr 重新构建", engine, className);
            return new OraclePlSqlParser();
        }
    }
}
