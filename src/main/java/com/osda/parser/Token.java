package com.osda.parser;

import java.util.Locale;

/**
 * A lexical unit of an Oracle SQL/PLSQL source file.
 *
 * <p>Offsets are 0-based indexes into the original source; line and column are 1-based.
 */
public record Token(
        TokenType type,
        String text,
        int startOffset,
        int endOffset,
        int line,
        int column,
        int endLine,
        int endColumn
) {

    public boolean isKeyword(String keyword) {
        return type == TokenType.IDENTIFIER && text.equalsIgnoreCase(keyword);
    }

    public boolean isAnyKeyword(String... keywords) {
        for (String keyword : keywords) {
            if (isKeyword(keyword)) {
                return true;
            }
        }
        return false;
    }

    public boolean isSymbol(String symbol) {
        return type == TokenType.SYMBOL && text.equals(symbol);
    }

    /** Unquoted identifiers are case insensitive and normalized to upper case. */
    public String normalizedText() {
        return type == TokenType.IDENTIFIER ? text.toUpperCase(Locale.ROOT) : text;
    }
}
