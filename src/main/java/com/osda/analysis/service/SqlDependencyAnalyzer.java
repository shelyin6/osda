package com.osda.analysis.service;

import com.osda.analysis.model.Confidence;
import com.osda.analysis.model.DependencyRelation;
import com.osda.analysis.model.OperationType;
import com.osda.analysis.model.ParseWarning;
import com.osda.analysis.model.SourceLocation;
import com.osda.config.OsdaProperties;
import com.osda.extraction.DependencyExtractor;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Analyzes one SQL/PLSQL source text.
 *
 * <p>A failure inside a single file never aborts the surrounding analysis: the error is turned
 * into a structured warning and the remaining files are still processed.
 */
@Service
public class SqlDependencyAnalyzer {

    private final DependencyExtractor extractor;
    private final OsdaProperties properties;

    public SqlDependencyAnalyzer(DependencyExtractor extractor, OsdaProperties properties) {
        this.extractor = extractor;
        this.properties = properties;
    }

    public FileExtraction analyze(SourceInput input) {
        try {
            DependencyExtractor.ExtractionResult result = extractor.extract(
                    input.fileName(),
                    input.content() == null ? "" : input.content(),
                    properties.getSnippetLength());
            List<ParseWarning> warnings = new ArrayList<>(result.warnings());
            if (result.relations().isEmpty() && warnings.isEmpty()) {
                warnings.add(new ParseWarning(
                        "OSDA-ANALYSIS-001",
                        "未在该文件中识别到任何依赖关系，请确认文件内容或语法是否受支持",
                        input.fileName(),
                        SourceLocation.of(1, 1)));
            }
            return new FileExtraction(
                    input.fileName(),
                    result.relations(),
                    warnings,
                    result.programUnitCount(),
                    lineCount(input.content()));
        } catch (RuntimeException exception) {
            List<ParseWarning> warnings = List.of(new ParseWarning(
                    "OSDA-ANALYSIS-002",
                    "文件解析失败，已跳过该文件：" + exception.getClass().getSimpleName()
                            + " " + exception.getMessage(),
                    input.fileName(),
                    SourceLocation.of(1, 1)));
            return new FileExtraction(input.fileName(), List.of(), warnings, 0, lineCount(input.content()));
        }
    }

    private int lineCount(String content) {
        if (content == null || content.isEmpty()) {
            return 0;
        }
        return content.split("\\R", -1).length;
    }

    public record FileExtraction(
            String fileName,
            List<DependencyRelation> relations,
            List<ParseWarning> warnings,
            int programUnitCount,
            int lineCount
    ) {

        public List<String> programUnits() {
            List<String> units = new ArrayList<>();
            for (DependencyRelation relation : relations) {
                if (!units.contains(relation.sourceUnit())) {
                    units.add(relation.sourceUnit());
                }
            }
            return units;
        }

        public long unknownCount() {
            return relations.stream()
                    .filter(relation -> relation.operation() == OperationType.UNKNOWN
                            || relation.confidence() == Confidence.LOW)
                    .count();
        }
    }
}
