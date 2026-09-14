package com.sqllineage.analysis.model;

import java.util.Optional;

/** Immutable evidence-backed relationship from one program unit to one database object. */
public record DependencyRelation(
        String sourceUnit,
        String sourceType,
        String sourceFile,
        SourceLocation sourceLocation,
        String targetSchema,
        String targetObject,
        String targetType,
        OperationType operation,
        Confidence confidence,
        String sqlSnippet,
        boolean dynamicSql,
        String warning
) {
    public Optional<String> warningMessage() {
        return Optional.ofNullable(warning).filter(message -> !message.isBlank());
    }
}
