package com.sqllineage.analysis.model;

/** One-based source location retained as evidence for a dependency. */
public record SourceLocation(int line, int column) {
}
