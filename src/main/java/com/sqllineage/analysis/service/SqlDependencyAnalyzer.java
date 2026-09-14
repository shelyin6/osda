package com.sqllineage.analysis.service;

import com.sqllineage.analysis.model.AnalysisResult;

/**
 * AST-backed Oracle SQL/PLSQL dependency analyzer contract.
 * Implementations must return warnings instead of hiding unsupported syntax.
 */
public interface SqlDependencyAnalyzer {
    AnalysisResult analyze(String sourceName, String sqlText);
}
