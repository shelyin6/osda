package com.sqllineage.analysis.model;

/** The table operation inferred from a static SQL statement. */
public enum OperationType {
    READ,
    INSERT,
    UPDATE,
    DELETE,
    MERGE,
    READ_WRITE,
    UNKNOWN
}
