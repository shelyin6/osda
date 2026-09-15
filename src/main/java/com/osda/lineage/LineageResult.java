package com.osda.lineage;

import java.util.List;

public record LineageResult(
        String direction,
        String object,
        int maxDepth,
        int reachedDepth,
        boolean truncated,
        List<LineageNode> roots
) {
}
