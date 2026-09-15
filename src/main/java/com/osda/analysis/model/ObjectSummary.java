package com.osda.analysis.model;

import java.util.List;
import java.util.Set;

/**
 * Table level view of the analysis result: one entry per distinct target object.
 *
 * <p>The relation list keeps every occurrence as evidence; this summary answers the DBA question
 * "which tables are involved and what happens to them" without repeating the same table once per
 * source line.
 */
public record ObjectSummary(
        String schema,
        String object,
        String qualifiedName,
        Set<OperationType> operations,
        int relationCount,
        int readCount,
        int writeCount,
        List<String> sourceUnits,
        List<String> sourceFiles,
        Confidence confidence,
        boolean dynamicSql,
        SourceLocation firstLocation,
        String sampleSnippet,
        String note
) {
}
