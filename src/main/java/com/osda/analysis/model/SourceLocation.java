package com.osda.analysis.model;

/**
 * 1-based source position of the evidence fragment.
 */
public record SourceLocation(int line, int column, int endLine, int endColumn) {

    public static SourceLocation of(int line, int column) {
        return new SourceLocation(line, column, line, column);
    }

    public String display() {
        return line == endLine ? line + ":" + column : line + ":" + column + "-" + endLine + ":" + endColumn;
    }
}
