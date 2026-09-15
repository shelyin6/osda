package com.osda.parser;

/**
 * Parsing boundary of the analyzer.
 *
 * <p>The current implementation is a hand written lexer plus recursive descent parser, chosen
 * because the build environment is offline and the ANTLR Oracle PL/SQL grammar assets cannot be
 * fetched. Any other syntax tree producer (for example an ANTLR based one) can replace it by
 * implementing this interface; the dependency extraction layer only depends on {@link Ast}.
 */
public interface SqlAstParser {

    ParsedFile parse(String source);
}
