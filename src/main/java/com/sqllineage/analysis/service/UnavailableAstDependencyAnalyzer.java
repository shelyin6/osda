package com.sqllineage.analysis.service;

import com.sqllineage.analysis.model.AnalysisResult;
import com.sqllineage.analysis.model.ParseWarning;
import com.sqllineage.analysis.model.SourceLocation;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Explicit interim implementation. It prevents a false success before the Oracle AST grammar is integrated.
 */
@Service
public class UnavailableAstDependencyAnalyzer implements SqlDependencyAnalyzer {
    private static final String PARSER_VERSION = "oracle-antlr-pending";

    @Override
    public AnalysisResult analyze(String sourceName, String sqlText) {
        return new AnalysisResult(
                List.of(),
                List.of(new ParseWarning(
                        "PARSER_NOT_READY",
                        "Oracle PL/SQL AST parser has not been integrated yet; no dependency result was inferred.",
                        new SourceLocation(1, 1)
                )),
                PARSER_VERSION
        );
    }
}
