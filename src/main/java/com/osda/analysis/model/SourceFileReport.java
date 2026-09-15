package com.osda.analysis.model;

import java.util.List;

public record SourceFileReport(
        String fileName,
        int lineCount,
        List<String> programUnits,
        int relationCount,
        int warningCount
) {
}
