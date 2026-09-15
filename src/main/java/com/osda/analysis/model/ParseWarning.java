package com.osda.analysis.model;

public record ParseWarning(
        String code,
        String message,
        String sourceFile,
        SourceLocation location
) {
}
