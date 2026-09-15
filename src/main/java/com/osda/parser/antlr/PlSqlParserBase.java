package com.osda.parser.antlr;

import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStream;

/**
 * Base class required by the vendored Oracle PL/SQL parser grammar.
 *
 * <p>Source: antlr/grammars-v4, {@code sql/plsql/Java/PlSqlParserBase.java},
 * Apache License 2.0. Only the package declaration was filled in.
 */
public abstract class PlSqlParserBase extends Parser {

    private boolean _isVersion12 = true;
    private boolean _isVersion11 = true;
    private boolean _isVersion10 = true;

    /** True if the last script_unit was PL/SQL (bare '/' requires a preceding ';'). */
    private boolean _lastUnitWasPlsql = false;

    public PlSqlParserBase(TokenStream input) {
        super(input);
    }

    @Override
    public void reset() {
        _lastUnitWasPlsql = false;
        super.reset();
    }

    public void setLastUnitPlsql() {
        _lastUnitWasPlsql = true;
    }

    public void setLastUnitSql() {
        _lastUnitWasPlsql = false;
    }

    public boolean isLastUnitSql() {
        return !_lastUnitWasPlsql;
    }

    public boolean isLastUnitPlsql() {
        return _lastUnitWasPlsql;
    }

    /**
     * Parser-level predicate: distinguishes SOLIDUS as a SQL*Plus separator (on its own line)
     * from SOLIDUS as a division operator (inside an expression).
     */
    public boolean isSolidusSeparator() {
        Token solidus = _input.LT(1);
        if (solidus == null || solidus.getType() != PlSqlParser.SOLIDUS) {
            return false;
        }
        int solidusLine = solidus.getLine();
        Token prev = _input.LT(-1);
        if (prev != null && prev.getType() != Token.EOF && prev.getLine() == solidusLine) {
            return false;
        }
        Token next = _input.LT(2);
        return next == null || next.getType() == Token.EOF || next.getLine() != solidusLine;
    }

    public boolean isVersion12() {
        return _isVersion12;
    }

    public void setVersion12(boolean value) {
        _isVersion12 = value;
    }

    public boolean isVersion11() {
        return _isVersion11;
    }

    public void setVersion11(boolean value) {
        _isVersion11 = value;
    }

    public boolean isVersion10() {
        return _isVersion10;
    }

    public void setVersion10(boolean value) {
        _isVersion10 = value;
    }

    public boolean IsNotNumericFunction() {
        Token lt1 = _input.LT(1);
        Token lt2 = _input.LT(2);
        boolean numericFunction = lt1.getType() == PlSqlParser.SUM
                || lt1.getType() == PlSqlParser.COUNT
                || lt1.getType() == PlSqlParser.AVG
                || lt1.getType() == PlSqlParser.MIN
                || lt1.getType() == PlSqlParser.MAX
                || lt1.getType() == PlSqlParser.ROUND
                || lt1.getType() == PlSqlParser.LEAST
                || lt1.getType() == PlSqlParser.GREATEST;
        return !(numericFunction && lt2.getType() == PlSqlParser.LEFT_PAREN);
    }

    public boolean isNotStartOfJoin() {
        Token lt1 = _input.LT(1);
        return lt1.getType() != PlSqlParser.INNER
                && lt1.getType() != PlSqlParser.CROSS
                && lt1.getType() != PlSqlParser.NATURAL
                && lt1.getType() != PlSqlParser.PARTITION
                && lt1.getType() != PlSqlParser.FULL
                && lt1.getType() != PlSqlParser.LEFT
                && lt1.getType() != PlSqlParser.RIGHT
                && lt1.getType() != PlSqlParser.OUTER;
    }
}
