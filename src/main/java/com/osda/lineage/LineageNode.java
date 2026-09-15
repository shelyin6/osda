package com.osda.lineage;

import com.osda.analysis.model.Confidence;
import com.osda.analysis.model.OperationType;
import com.osda.analysis.model.SourceLocation;
import com.osda.analysis.model.SourceType;
import java.util.List;

/**
 * One node of an upstream/downstream trace.
 *
 * <p>Program unit nodes are kept in the graph on purpose: phase one must never present table to
 * table lineage as if the procedure logic did not exist.
 */
public record LineageNode(
        String id,
        String nodeType,
        String name,
        SourceType unitType,
        String sourceFile,
        SourceLocation location,
        OperationType operation,
        Confidence confidence,
        boolean dynamicSql,
        String sqlSnippet,
        String status,
        List<LineageNode> children
) {

    public static final String TABLE = "TABLE";
    public static final String PROGRAM_UNIT = "PROGRAM_UNIT";
}
