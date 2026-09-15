package com.osda.analysis.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.osda.analysis.model.Confidence;
import com.osda.analysis.model.DependencyRelation;
import com.osda.analysis.model.ObjectSummary;
import com.osda.analysis.model.OperationType;
import com.osda.analysis.model.SourceLocation;
import com.osda.analysis.model.SourceType;
import com.osda.analysis.model.TargetType;
import java.util.List;
import org.junit.jupiter.api.Test;

class ObjectSummaryServiceTest {

    /** The aggregation itself is stateless; the service dependency is only used by summaries(). */
    private final ObjectSummaryService service = new ObjectSummaryService(null);

    @Test
    void mergesRepeatedOccurrencesIntoOneRowPerObject() {
        List<ObjectSummary> summaries = service.summarize(List.of(
                relation("DEMO.P_A", OperationType.READ, "SUM", "PU_ORG", 10, "f1", Confidence.HIGH, false),
                relation("DEMO.P_A", OperationType.READ, "SUM", "PU_ORG", 20, "f1", Confidence.HIGH, false),
                relation("DEMO.P_A", OperationType.INSERT, "ADS", "T_RESULT", 30, "f1", Confidence.HIGH, false),
                relation("DEMO.P_A", OperationType.READ, "ADS", "T_RESULT", 31, "f1", Confidence.HIGH, false),
                relation("DEMO.P_B", OperationType.READ, "KINGBASE", "V_SRC", 5, "f2", Confidence.MEDIUM, true)));

        assertEquals(3, summaries.size(), "每个目标对象只应出现一行");
        assertEquals(
                List.of("ADS.T_RESULT", "KINGBASE.V_SRC", "SUM.PU_ORG"),
                summaries.stream().map(ObjectSummary::qualifiedName).toList());

        ObjectSummary repeated = summaries.get(2);
        assertEquals(2, repeated.relationCount());
        assertEquals(2, repeated.readCount());
        assertEquals(0, repeated.writeCount());
        assertEquals(List.of(OperationType.READ), List.copyOf(repeated.operations()));
        assertEquals(List.of("DEMO.P_A"), repeated.sourceUnits());
        assertEquals("仅作为读取来源", repeated.note());
        assertEquals(10, repeated.firstLocation().line(), "最早的一次出现应作为证据位置");

        ObjectSummary readWrite = summaries.get(0);
        assertEquals(List.of(OperationType.READ, OperationType.INSERT), List.copyOf(readWrite.operations()));
        assertEquals(1, readWrite.readCount());
        assertEquals(1, readWrite.writeCount());
        assertEquals("该对象既有读取也有写入", readWrite.note());

        ObjectSummary dynamic = summaries.get(1);
        assertEquals(Confidence.MEDIUM, dynamic.confidence());
        assertEquals(true, dynamic.dynamicSql());
    }

    private DependencyRelation relation(
            String unit,
            OperationType operation,
            String schema,
            String object,
            int line,
            String file,
            Confidence confidence,
            boolean dynamic
    ) {
        return new DependencyRelation(
                unit + "|" + operation + "|" + schema + "." + object + "|" + line,
                unit,
                SourceType.PROCEDURE,
                file,
                SourceLocation.of(line, 1),
                schema,
                object,
                TargetType.TABLE,
                operation,
                confidence,
                "SELECT 1 FROM " + schema + "." + object,
                dynamic,
                null);
    }
}
