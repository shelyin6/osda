package com.osda.parser;

import java.util.ArrayList;
import java.util.List;

/**
 * Hand written lexer for Oracle SQL/PLSQL.
 *
 * <p>The lexer keeps every offset so that relations can point back to the exact evidence
 * fragment. Comments are skipped, and string literals are kept as single tokens so that
 * keywords inside literals can never produce a false dependency.
 */
public final class Lexer {

    private final String source;
    private int offset;
    private int line = 1;
    private int column = 1;

    public Lexer(String source) {
        this.source = source == null ? "" : source;
    }

    public List<Token> tokenize() {
        List<Token> tokens = new ArrayList<>();
        while (offset < source.length()) {
            char current = source.charAt(offset);
            if (Character.isWhitespace(current)) {
                advance();
            } else if (current == '-' && peek(1) == '-') {
                skipLineComment();
            } else if (current == '/' && peek(1) == '*') {
                skipBlockComment();
            } else if (current == '\'') {
                tokens.add(readString());
            } else if (current == '"') {
                tokens.add(readQuotedIdentifier());
            } else if (Character.isDigit(current)) {
                tokens.add(readNumber());
            } else if (isIdentifierStart(current)) {
                tokens.add(readIdentifier());
            } else {
                tokens.add(readSymbol());
            }
        }
        tokens.add(new Token(TokenType.EOF, "", offset, offset, line, column, line, column));
        return tokens;
    }

    private void skipLineComment() {
        while (offset < source.length() && source.charAt(offset) != '\n') {
            advance();
        }
    }

    private void skipBlockComment() {
        advance();
        advance();
        while (offset < source.length()) {
            if (source.charAt(offset) == '*' && peek(1) == '/') {
                advance();
                advance();
                return;
            }
            advance();
        }
    }

    private Token readString() {
        int startOffset = offset;
        int startLine = line;
        int startColumn = column;
        advance();
        while (offset < source.length()) {
            char current = source.charAt(offset);
            if (current == '\'') {
                if (peek(1) == '\'') {
                    advance();
                    advance();
                    continue;
                }
                advance();
                break;
            }
            advance();
        }
        return token(TokenType.STRING, startOffset, startLine, startColumn);
    }

    private Token readQuotedIdentifier() {
        int startOffset = offset;
        int startLine = line;
        int startColumn = column;
        advance();
        while (offset < source.length()) {
            char current = source.charAt(offset);
            if (current == '"') {
                if (peek(1) == '"') {
                    advance();
                    advance();
                    continue;
                }
                advance();
                break;
            }
            advance();
        }
        return token(TokenType.QUOTED_IDENTIFIER, startOffset, startLine, startColumn);
    }

    private Token readNumber() {
        int startOffset = offset;
        int startLine = line;
        int startColumn = column;
        while (offset < source.length()) {
            char current = source.charAt(offset);
            if (Character.isLetterOrDigit(current) || current == '.') {
                advance();
            } else {
                break;
            }
        }
        return token(TokenType.NUMBER, startOffset, startLine, startColumn);
    }

    private Token readIdentifier() {
        int startOffset = offset;
        int startLine = line;
        int startColumn = column;
        while (offset < source.length() && isIdentifierPart(source.charAt(offset))) {
            advance();
        }
        return token(TokenType.IDENTIFIER, startOffset, startLine, startColumn);
    }

    private Token readSymbol() {
        int startOffset = offset;
        int startLine = line;
        int startColumn = column;
        char current = source.charAt(offset);
        String twoChar = offset + 1 < source.length() ? source.substring(offset, offset + 2) : "";
        if (twoChar.equals(":=") || twoChar.equals("||") || twoChar.equals("<=")
                || twoChar.equals(">=") || twoChar.equals("<>") || twoChar.equals("!=")
                || twoChar.equals("=>") || twoChar.equals("..")) {
            advance();
            advance();
        } else {
            advance();
        }
        return token(TokenType.SYMBOL, startOffset, startLine, startColumn);
    }

    private Token token(TokenType type, int startOffset, int startLine, int startColumn) {
        return new Token(
                type,
                source.substring(startOffset, offset),
                startOffset,
                offset,
                startLine,
                startColumn,
                line,
                Math.max(1, column - 1)
        );
    }

    private void advance() {
        char current = source.charAt(offset);
        offset++;
        if (current == '\n') {
            line++;
            column = 1;
        } else {
            column++;
        }
    }

    private char peek(int distance) {
        int index = offset + distance;
        return index < source.length() ? source.charAt(index) : '\0';
    }

    private static boolean isIdentifierStart(char value) {
        return Character.isLetter(value) || value == '_';
    }

    private static boolean isIdentifierPart(char value) {
        return Character.isLetterOrDigit(value) || value == '_' || value == '$' || value == '#';
    }
}
