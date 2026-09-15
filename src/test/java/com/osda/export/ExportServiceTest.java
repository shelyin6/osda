package com.osda.export;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.osda.analysis.model.Confidence;
import com.osda.analysis.model.DependencyRelation;
import com.osda.analysis.model.OperationType;
import com.osda.analysis.model.SourceLocation;
import com.osda.analysis.model.SourceType;
import com.osda.analysis.model.TargetType;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExportServiceTest {

    private final ExportService exportService = new ExportService();

    @Test
    void csvKeepsBomHeaderAndEscapesEvidence() {
        DependencyRelation relation = new DependencyRelation(
                "id-1",
                "DEMO.P_LOAD",
                SourceType.PROCEDURE,
                "demo.sql",
                SourceLocation.of(12, 5),
                "DEMO",
                "T_TARGET",
                TargetType.TABLE,
                OperationType.INSERT,
                Confidence.HIGH,
                "INSERT INTO DEMO.T_TARGET (A, \"B\") VALUES (1, 2)",
                false,
                null);

        String csv = new String(exportService.relationsCsv(List.of(relation)), StandardCharsets.UTF_8);

        assertTrue(csv.startsWith("\uFEFF"), "CSV 需要 UTF-8 BOM 以便 Excel 正确识别中文");
        assertTrue(csv.contains("source_unit,source_type,source_file,line,column"));
        assertTrue(csv.contains("DEMO.T_TARGET"));
        assertTrue(csv.contains("\"INSERT INTO DEMO.T_TARGET (A, \"\"B\"\") VALUES (1, 2)\""));
    }
}
