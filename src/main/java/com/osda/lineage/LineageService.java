package com.osda.lineage;

import com.osda.analysis.model.AnalysisResult;
import com.osda.analysis.model.Confidence;
import com.osda.analysis.model.DependencyRelation;
import com.osda.analysis.model.OperationType;
import com.osda.analysis.model.SourceLocation;
import com.osda.config.OsdaProperties;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Builds upstream/downstream traces.
 *
 * <p>UPSTREAM: target table &lt;- program units writing it &lt;- tables those units read.
 * DOWNSTREAM: target table -&gt; program units reading it -&gt; tables those units write.
 * Expansion always stops at the configured maximum depth and marks cycles explicitly.
 */
@Service
public class LineageService {

    public static final String UPSTREAM = "UPSTREAM";
    public static final String DOWNSTREAM = "DOWNSTREAM";

    private final OsdaProperties properties;
    private volatile Map<String, List<DependencyRelation>> writersByTarget = Map.of();
    private volatile Map<String, List<DependencyRelation>> readersByTarget = Map.of();
    private volatile Map<String, List<DependencyRelation>> relationsByUnit = Map.of();

    public LineageService(OsdaProperties properties) {
        this.properties = properties;
    }

    public synchronized void rebuild(AnalysisResult result) {
        Map<String, List<DependencyRelation>> writers = new LinkedHashMap<>();
        Map<String, List<DependencyRelation>> readers = new LinkedHashMap<>();
        Map<String, List<DependencyRelation>> byUnit = new LinkedHashMap<>();
        for (DependencyRelation relation : result.relations()) {
            byUnit.computeIfAbsent(relation.sourceUnit(), key -> new ArrayList<>()).add(relation);
            if (relation.writes()) {
                writers.computeIfAbsent(relation.targetKey(), key -> new ArrayList<>()).add(relation);
            }
            if (relation.reads()) {
                readers.computeIfAbsent(relation.targetKey(), key -> new ArrayList<>()).add(relation);
            }
        }
        this.writersByTarget = writers;
        this.readersByTarget = readers;
        this.relationsByUnit = byUnit;
    }

    public LineageResult trace(String object, String direction, Integer requestedDepth) {
        String normalizedDirection = DOWNSTREAM.equalsIgnoreCase(direction) ? DOWNSTREAM : UPSTREAM;
        int maxDepth = Math.max(1, Math.min(
                requestedDepth == null ? properties.getMaxLineageDepth() : requestedDepth,
                properties.getMaxLineageDepth()));
        String key = normalize(object);
        Holder holder = new Holder();
        LineageNode root = tableNode(key, normalizedDirection, 0, maxDepth, Set.of(), null, holder);
        return new LineageResult(
                normalizedDirection, key, maxDepth, holder.maxDepth, holder.truncated, List.of(root));
    }

    public Set<String> knownObjects() {
        Set<String> objects = new LinkedHashSet<>(writersByTarget.keySet());
        objects.addAll(readersByTarget.keySet());
        return objects;
    }

    private LineageNode tableNode(
            String tableKey,
            String direction,
            int depth,
            int maxDepth,
            Set<String> path,
            DependencyRelation incoming,
            Holder holder
    ) {
        holder.observe(depth);
        String pathKey = "T:" + tableKey;
        if (path.contains(pathKey)) {
            return node(tableKey, LineageNode.TABLE, "CYCLE", incoming, depth, List.of(), holder);
        }
        if (depth >= maxDepth) {
            holder.truncated = true;
            return node(tableKey, LineageNode.TABLE, "DEPTH_LIMIT", incoming, depth, List.of(), holder);
        }

        Set<String> nextPath = new LinkedHashSet<>(path);
        nextPath.add(pathKey);

        Map<String, List<DependencyRelation>> index = UPSTREAM.equals(direction)
                ? writersByTarget : readersByTarget;
        List<DependencyRelation> relations = index.getOrDefault(tableKey, List.of());

        Map<String, DependencyRelation> byUnit = new LinkedHashMap<>();
        for (DependencyRelation relation : relations) {
            byUnit.putIfAbsent(relation.sourceUnit(), relation);
        }

        List<LineageNode> children = new ArrayList<>();
        for (Map.Entry<String, DependencyRelation> entry : byUnit.entrySet()) {
            children.add(unitNode(
                    entry.getKey(), direction, depth + 1, maxDepth, nextPath, entry.getValue(), holder));
        }
        String status = depth == 0 ? "ROOT" : (children.isEmpty() ? "ORIGINAL" : "DIRECT");
        return node(tableKey, LineageNode.TABLE, status, incoming, depth, children, holder);
    }

    private LineageNode unitNode(
            String unit,
            String direction,
            int depth,
            int maxDepth,
            Set<String> path,
            DependencyRelation incoming,
            Holder holder
    ) {
        holder.observe(depth);
        String pathKey = "U:" + normalize(unit);
        if (path.contains(pathKey)) {
            return node(unit, LineageNode.PROGRAM_UNIT, "CYCLE", incoming, depth, List.of(), holder);
        }
        if (depth >= maxDepth) {
            holder.truncated = true;
            return node(unit, LineageNode.PROGRAM_UNIT, "DEPTH_LIMIT", incoming, depth, List.of(), holder);
        }

        Set<String> nextPath = new LinkedHashSet<>(path);
        nextPath.add(pathKey);

        List<DependencyRelation> relations = relationsByUnit.getOrDefault(unit, List.of());
        Map<String, DependencyRelation> nextObjects = new LinkedHashMap<>();
        for (DependencyRelation relation : relations) {
            boolean relevant = UPSTREAM.equals(direction) ? relation.reads() : relation.writes();
            if (relevant) {
                nextObjects.putIfAbsent(relation.targetKey(), relation);
            }
        }

        List<LineageNode> children = new ArrayList<>();
        for (Map.Entry<String, DependencyRelation> entry : nextObjects.entrySet()) {
            children.add(tableNode(
                    entry.getKey(), direction, depth + 1, maxDepth, nextPath, entry.getValue(), holder));
        }
        children.sort(Comparator.comparing(LineageNode::name));
        return node(unit, LineageNode.PROGRAM_UNIT, children.isEmpty() ? "ORIGINAL" : "DIRECT",
                incoming, depth, children, holder);
    }

    private LineageNode node(
            String name,
            String nodeType,
            String status,
            DependencyRelation relation,
            int depth,
            List<LineageNode> children,
            Holder holder
    ) {
        return new LineageNode(
                depth + "|" + nodeType + "|" + name,
                nodeType,
                name,
                relation == null ? null : relation.sourceType(),
                relation == null ? null : relation.sourceFile(),
                relation == null ? SourceLocation.of(0, 0) : relation.sourceLocation(),
                relation == null ? OperationType.UNKNOWN : relation.operation(),
                relation == null ? Confidence.HIGH : relation.confidence(),
                relation != null && relation.dynamicSql(),
                relation == null ? null : relation.sqlSnippet(),
                status,
                List.copyOf(children));
    }

    private String normalize(String value) {
        return value == null ? "" : value.replace("\"", "").replaceAll("\\s+", "")
                .toUpperCase(Locale.ROOT);
    }

    private static final class Holder {
        private int maxDepth;
        private boolean truncated;

        private void observe(int depth) {
            maxDepth = Math.max(maxDepth, depth);
        }
    }
}
