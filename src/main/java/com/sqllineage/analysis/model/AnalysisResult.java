package com.sqllineage.analysis.model;

import java.util.List;

public record AnalysisResult(
        List<DependencyRelation> relations,
        List<ParseWarning> warnings,
        String parserVersion
) {
    public AnalysisResult {
        relations = List.copyOf(relations);
        warnings = List.copyOf(warnings);
    }
}
