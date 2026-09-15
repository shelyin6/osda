package com.osda.analysis.service;

import com.osda.analysis.model.Confidence;
import com.osda.analysis.model.DependencyRelation;
import com.osda.analysis.model.ObjectSummary;
import com.osda.analysis.model.OperationType;
import com.osda.analysis.model.SourceLocation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Aggregates relations into a de-duplicated, table level result.
 */
@Service
public class ObjectSummaryService {

    /** Display order of operations inside one summary row. */
    private static final List<OperationType> OPERATION_ORDER = List.of(
            OperationType.READ,
            OperationType.INSERT,
            OperationType.UPDATE,
            OperationType.DELETE,
            OperationType.MERGE,
            OperationType.READ_WRITE,
            OperationType.UNKNOWN);

    private final AnalysisService analysisService;

    public ObjectSummaryService(AnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    public List<ObjectSummary> summaries() {
        return summarize(analysisService.current().relations());
    }

    public List<ObjectSummary> summarize(List<DependencyRelation> relations) {
        Map<String, List<DependencyRelation>> grouped = new LinkedHashMap<>();
        for (DependencyRelation relation : relations) {
            grouped.computeIfAbsent(relation.targetKey(), key -> new ArrayList<>()).add(relation);
        }

        List<ObjectSummary> summaries = new ArrayList<>(grouped.size());
        for (Map.Entry<String, List<DependencyRelation>> entry : grouped.entrySet()) {
            summaries.add(summarizeOne(entry.getValue()));
        }
        summaries.sort(Comparator.comparing(ObjectSummary::qualifiedName));
        return List.copyOf(summaries);
    }

    private ObjectSummary summarizeOne(List<DependencyRelation> relations) {
        DependencyRelation first = relations.get(0);
        Set<OperationType> operations = new LinkedHashSet<>();
        for (OperationType operation : OPERATION_ORDER) {
            boolean present = relations.stream().anyMatch(relation -> relation.operation() == operation);
            if (present) {
                operations.add(operation);
            }
        }

        int readCount = (int) relations.stream().filter(DependencyRelation::reads).count();
        int writeCount = (int) relations.stream().filter(DependencyRelation::writes).count();
        boolean dynamic = relations.stream().anyMatch(DependencyRelation::dynamicSql);
        Confidence confidence = relations.stream()
                .map(DependencyRelation::confidence)
                .min(Comparator.comparingInt(ObjectSummaryService::weakness))
                .orElse(Confidence.HIGH);

        List<String> units = relations.stream()
                .map(DependencyRelation::sourceUnit)
                .distinct()
                .sorted()
                .toList();
        List<String> files = relations.stream()
                .map(DependencyRelation::sourceFile)
                .distinct()
                .sorted()
                .toList();
        DependencyRelation earliest = relations.stream()
                .min(Comparator.comparing((DependencyRelation relation) -> relation.sourceFile())
                        .thenComparingInt(relation -> relation.sourceLocation().line()))
                .orElse(first);

        return new ObjectSummary(
                first.targetSchema(),
                first.targetObject(),
                first.targetKey(),
                operations,
                relations.size(),
                readCount,
                writeCount,
                units,
                files,
                confidence,
                dynamic,
                earliest.sourceLocation(),
                earliest.sqlSnippet(),
                note(operations));
    }

    private String note(Set<OperationType> operations) {
        boolean read = operations.contains(OperationType.READ);
        boolean write = operations.stream().anyMatch(operation -> operation != OperationType.READ
                && operation != OperationType.UNKNOWN);
        if (read && write) {
            return "该对象既有读取也有写入";
        }
        if (write) {
            return "仅作为写入目标";
        }
        if (read) {
            return "仅作为读取来源";
        }
        return "";
    }

    /** Lower value means more trustworthy; used to keep the most conservative confidence. */
    private static int weakness(Confidence confidence) {
        return switch (confidence) {
            case HIGH -> 0;
            case MEDIUM -> 1;
            case LOW -> 2;
        };
    }

    /** Reused by the lineage page to list known objects. */
    public Set<String> objectKeys() {
        Set<String> keys = new LinkedHashSet<>();
        analysisService.current().relations().forEach(relation -> keys.add(relation.targetKey()));
        return keys;
    }

    public SourceLocation locationOf(DependencyRelation relation) {
        return relation.sourceLocation();
    }
}
