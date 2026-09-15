package com.osda.analysis.service;

import com.osda.analysis.model.AnalysisResult;
import com.osda.analysis.model.AnalysisSummary;
import com.osda.analysis.model.DependencyRelation;
import com.osda.analysis.model.ParseWarning;
import com.osda.analysis.model.SourceFileReport;
import com.osda.config.OsdaProperties;
import com.osda.lineage.LineageService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Keeps the most recent analysis run in memory and exposes it to the query and export layers.
 */
@Service
public class AnalysisService {

    private final SqlDependencyAnalyzer analyzer;
    private final LineageService lineageService;
    private final OsdaProperties properties;
    private volatile AnalysisResult current;

    public AnalysisService(
            SqlDependencyAnalyzer analyzer,
            LineageService lineageService,
            OsdaProperties properties
    ) {
        this.analyzer = analyzer;
        this.lineageService = lineageService;
        this.properties = properties;
    }

    public synchronized AnalysisResult analyze(List<SourceInput> inputs) {
        List<DependencyRelation> relations = new ArrayList<>();
        List<ParseWarning> warnings = new ArrayList<>();
        List<SourceFileReport> files = new ArrayList<>();
        int programUnits = 0;

        for (SourceInput input : inputs) {
            SqlDependencyAnalyzer.FileExtraction extraction = analyzer.analyze(input);
            relations.addAll(extraction.relations());
            warnings.addAll(extraction.warnings());
            programUnits += extraction.programUnitCount();
            files.add(new SourceFileReport(
                    extraction.fileName(),
                    extraction.lineCount(),
                    extraction.programUnits(),
                    extraction.relations().size(),
                    extraction.warnings().size()));
        }

        AnalysisResult result = new AnalysisResult(
                UUID.randomUUID().toString(),
                properties.getParserVersion(),
                Instant.now(),
                AnalysisSummary.of(files.size(), programUnits, relations, warnings.size()),
                List.copyOf(files),
                List.copyOf(relations),
                List.copyOf(warnings));
        this.current = result;
        lineageService.rebuild(result);
        return result;
    }

    public AnalysisResult current() {
        AnalysisResult result = current;
        if (result == null) {
            throw new IllegalStateException("尚未执行分析，请先上传 SQL 文件或粘贴代码");
        }
        return result;
    }

    public boolean hasResult() {
        return current != null;
    }

    public List<DependencyRelation> filterRelations(
            String operation,
            String confidence,
            String file,
            String target,
            String keyword
    ) {
        return current().relations().stream()
                .filter(relation -> operation == null || operation.isBlank()
                        || relation.operation().name().equalsIgnoreCase(operation))
                .filter(relation -> confidence == null || confidence.isBlank()
                        || relation.confidence().name().equalsIgnoreCase(confidence))
                .filter(relation -> file == null || file.isBlank()
                        || relation.sourceFile().equalsIgnoreCase(file))
                .filter(relation -> target == null || target.isBlank()
                        || relation.targetKey().toUpperCase().contains(target.toUpperCase()))
                .filter(relation -> keyword == null || keyword.isBlank()
                        || relation.sourceUnit().toUpperCase().contains(keyword.toUpperCase())
                        || relation.targetKey().toUpperCase().contains(keyword.toUpperCase())
                        || relation.sqlSnippet().toUpperCase().contains(keyword.toUpperCase()))
                .collect(Collectors.toList());
    }
}
