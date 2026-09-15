package com.osda.analysis.model;

/**
 * Confidence of a statically extracted relation.
 *
 * <p>HIGH: parsed straight from the syntax tree with a fully resolved object name.
 * MEDIUM: resolved from a constant dynamic SQL string, or an object name resolved through a doc hint.
 * LOW: object name or SQL body could not be resolved statically (UNKNOWN relations).
 */
public enum Confidence {
    HIGH,
    MEDIUM,
    LOW
}
