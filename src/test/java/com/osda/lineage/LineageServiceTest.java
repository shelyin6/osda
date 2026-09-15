package com.osda.lineage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.osda.analysis.model.AnalysisResult;
import com.osda.analysis.model.AnalysisSummary;
import com.osda.analysis.model.Confidence;
import com.osda.analysis.model.DependencyRelation;
import com.osda.analysis.model.OperationType;
import com.osda.analysis.model.SourceLocation;
import com.osda.analysis.model.SourceType;
import com.osda.analysis.model.TargetType;
import com.osda.config.OsdaProperties;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LineageServiceTest {

    private final OsdaProperties properties = new OsdaProperties();
    private final LineageService service = new LineageService(properties);

    @Test
    void upstreamKeepsProgramUnitNodesAndStopsAtDepthLimit() {
        properties.setMaxLineageDepth(10);
        service.rebuild(result(List.of(
                relation("DEMO.P_UP", OperationType.INSERT, "DEMO.S_SRC", 10),
                relation("DEMO.P_UP", OperationType.READ, "DEMO.I_BASE", 12),
                relation("DEMO.P_LOAD", OperationType.INSERT, "DEMO.O_TARGET", 20),
                relation("DEMO.P_LOAD", OperationType.READ, "DEMO.S_SRC", 22))));

        LineageResult full = service.trace("demo.o_target", LineageService.UPSTREAM, 10);
        LineageNode root = full.roots().get(0);
        assertEquals(LineageNode.TABLE, root.nodeType());
        assertEquals("DEMO.O_TARGET", root.name());
        assertEquals("ROOT", root.status());

        LineageNode writer = root.children().get(0);
        assertEquals(LineageNode.PROGRAM_UNIT, writer.nodeType());
        assertEquals("DEMO.P_LOAD", writer.name());
        assertEquals(OperationType.INSERT, writer.operation());

        LineageNode upstreamTable = writer.children().get(0);
        assertEquals("DEMO.S_SRC", upstreamTable.name());
        assertEquals("DEMO.P_UP", upstreamTable.children().get(0).name());
        assertEquals("DEMO.I_BASE", upstreamTable.children().get(0).children().get(0).name());

        LineageResult limited = service.trace("DEMO.O_TARGET", LineageService.UPSTREAM, 2);
        assertTrue(limited.truncated(), "超过深度上限时结果必须标记为截断");
        assertEquals(2, limited.maxDepth());
        LineageNode limitedTable = limited.roots().get(0).children().get(0).children().get(0);
        assertEquals("DEPTH_LIMIT", limitedTable.status());
    }

    @Test
    void downstreamFollowsReadersThenTheirWrites() {
        service.rebuild(result(List.of(
                relation("DEMO.P_READ", OperationType.READ, "DEMO.S_SRC", 5),
                relation("DEMO.P_READ", OperationType.INSERT, "DEMO.M_MID", 7),
                relation("DEMO.P_WRITE", OperationType.INSERT, "DEMO.O_FINAL", 9))));

        LineageResult result = service.trace("DEMO.S_SRC", LineageService.DOWNSTREAM, 4);
        LineageNode root = result.roots().get(0);
        LineageNode reader = root.children().get(0);
        assertEquals("DEMO.P_READ", reader.name());
        assertEquals(OperationType.READ, reader.operation());
        assertEquals("DEMO.M_MID", reader.children().get(0).name());
    }

    @Test
    void cyclesAreReportedInsteadOfLoopingForever() {
        service.rebuild(result(List.of(
                relation("DEMO.P_A", OperationType.INSERT, "DEMO.T_1", 3),
                relation("DEMO.P_A", OperationType.READ, "DEMO.T_2", 4),
                relation("DEMO.P_B", OperationType.INSERT, "DEMO.T_2", 6),
                relation("DEMO.P_B", OperationType.READ, "DEMO.T_1", 7))));

        LineageResult result = service.trace("DEMO.T_1", LineageService.UPSTREAM, 8);

        List<String> statuses = new ArrayList<>();
        collectStatuses(result.roots().get(0), statuses);
        assertTrue(statuses.contains("CYCLE"), "循环依赖必须被标记：" + statuses);
    }

    private void collectStatuses(LineageNode node, List<String> statuses) {
        statuses.add(node.status());
        node.children().forEach(child -> collectStatuses(child, statuses));
    }

    private AnalysisResult result(List<DependencyRelation> relations) {
        return new AnalysisResult(
                "run-1",
                "test",
                Instant.now(),
                AnalysisSummary.of(1, relations.size(), relations, 0),
                List.of(),
                relations,
                List.of());
    }

    private DependencyRelation relation(String unit, OperationType operation, String target, int line) {
        int separator = target.indexOf('.');
        String schema = target.substring(0, separator);
        String object = target.substring(separator + 1);
        return new DependencyRelation(
                unit + "|" + operation + "|" + target + "|" + line,
                unit,
                SourceType.PROCEDURE,
                "demo.sql",
                SourceLocation.of(line, 5),
                schema,
                object,
                TargetType.TABLE,
                operation,
                Confidence.HIGH,
                "SELECT 1 FROM " + target,
                false,
                null);
    }
}
