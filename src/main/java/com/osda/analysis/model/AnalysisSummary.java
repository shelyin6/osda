package com.osda.analysis.model;

import java.util.List;
import java.util.Map;

public record AnalysisSummary(
        int fileCount,
        int programUnitCount,
        int relationCount,
        int unknownRelationCount,
        int warningCount,
        Map<OperationType, Integer> relationCountByOperation,
        Map<Confidence, Integer> relationCountByConfidence
) {

    public static AnalysisSummary of(
            int fileCount,
            int programUnitCount,
            List<DependencyRelation> relations,
            int warningCount
    ) {
        Map<OperationType, Integer> byOperation = new java.util.TreeMap<>();
        Map<Confidence, Integer> byConfidence = new java.util.TreeMap<>();
        for (DependencyRelation relation : relations) {
            byOperation.merge(relation.operation(), 1, Integer::sum);
            byConfidence.merge(relation.confidence(), 1, Integer::sum);
        }
        long unknown = relations.stream()
                .filter(relation -> relation.operation() == OperationType.UNKNOWN
                        || relation.confidence() == Confidence.LOW)
                .count();
        return new AnalysisSummary(
                fileCount,
                programUnitCount,
                relations.size(),
                (int) unknown,
                warningCount,
                byOperation,
                byConfidence
        );
    }
}
