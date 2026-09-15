package com.osda.analysis.model;

/**
 * Operation a program unit performs on a target database object.
 */
public enum OperationType {
    READ,
    INSERT,
    UPDATE,
    DELETE,
    MERGE,
    READ_WRITE,
    UNKNOWN
}
