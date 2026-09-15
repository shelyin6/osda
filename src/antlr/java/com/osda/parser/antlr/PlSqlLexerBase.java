package com.osda.parser.antlr;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.Lexer;

/**
 * Base class required by the vendored Oracle PL/SQL lexer grammar.
 *
 * <p>Source: antlr/grammars-v4, {@code sql/plsql/Java/PlSqlLexerBase.java},
 * Apache License 2.0. Only the package declaration was filled in.
 */
public abstract class PlSqlLexerBase extends Lexer {

    public PlSqlLexerBase(CharStream input) {
        super(input);
    }

    protected boolean IsNewlineAtPos(int pos) {
        int la = _input.LA(pos);
        return la == -1 || la == '\n';
    }
}
