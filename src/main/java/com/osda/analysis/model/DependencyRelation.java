package com.osda.analysis.model;

/**
 * Core model of the analyzer: source program unit --[operation]--> target database object.
 */
public record DependencyRelation(
        String id,
        String sourceUnit,
        SourceType sourceType,
        String sourceFile,
        SourceLocation sourceLocation,
        String targetSchema,
        String targetObject,
        TargetType targetType,
        OperationType operation,
        Confidence confidence,
        String sqlSnippet,
        boolean dynamicSql,
        String warning
) {

    /** Fully qualified, normalized target key used by the lineage indexes. */
    public String targetKey() {
        return targetSchema == null || targetSchema.isBlank()
                ? targetObject
                : targetSchema + "." + targetObject;
    }

    public boolean writes() {
        return operation == OperationType.INSERT
                || operation == OperationType.UPDATE
                || operation == OperationType.DELETE
                || operation == OperationType.MERGE
                || operation == OperationType.UNKNOWN;
    }

    public boolean reads() {
        return operation == OperationType.READ;
    }
}
