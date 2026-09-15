package com.osda.analysis.model;

import java.time.Instant;
import java.util.List;

public record AnalysisResult(
        String runId,
        String parserVersion,
        Instant analyzedAt,
        AnalysisSummary summary,
        List<SourceFileReport> files,
        List<DependencyRelation> relations,
        List<ParseWarning> warnings
) {
}
