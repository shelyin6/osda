package com.sqllineage.analysis.model;

/** A recoverable analysis limitation; warnings must not discard other valid results. */
public record ParseWarning(
        String code,
        String message,
        SourceLocation sourceLocation
) {
}
