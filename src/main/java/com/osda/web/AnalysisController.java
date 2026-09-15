package com.osda.web;

import com.osda.analysis.model.AnalysisResult;
import com.osda.analysis.model.DependencyRelation;
import com.osda.analysis.model.ObjectSummary;
import com.osda.analysis.service.AnalysisService;
import com.osda.analysis.service.ObjectSummaryService;
import com.osda.analysis.service.SourceInput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class AnalysisController {

    private final AnalysisService analysisService;
    private final ObjectSummaryService objectSummaryService;

    public AnalysisController(
            AnalysisService analysisService,
            ObjectSummaryService objectSummaryService
    ) {
        this.analysisService = analysisService;
        this.objectSummaryService = objectSummaryService;
    }

    /** Batch upload of .sql files. */
    @PostMapping(path = "/analysis/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AnalysisResult analyzeFiles(@RequestPart("files") MultipartFile[] files) throws IOException {
        List<SourceInput> inputs = new ArrayList<>();
        for (MultipartFile file : files) {
            String name = file.getOriginalFilename() == null ? "uploaded.sql" : file.getOriginalFilename();
            inputs.add(new SourceInput(name, new String(file.getBytes(), StandardCharsets.UTF_8)));
        }
        return analysisService.analyze(inputs);
    }

    /** Analysis of pasted code. */
    @PostMapping(path = "/analysis/text", consumes = MediaType.APPLICATION_JSON_VALUE)
    public AnalysisResult analyzeText(@RequestBody AnalysisTextRequest request) {
        String name = request.name() == null || request.name().isBlank() ? "pasted.sql" : request.name();
        return analysisService.analyze(List.of(new SourceInput(name, request.content())));
    }

    @GetMapping("/analysis/current")
    public AnalysisResult current() {
        return analysisService.current();
    }

    @GetMapping("/relations")
    public List<DependencyRelation> relations(
            @RequestParam(required = false) String operation,
            @RequestParam(required = false) String confidence,
            @RequestParam(required = false) String file,
            @RequestParam(required = false) String target,
            @RequestParam(required = false) String keyword
    ) {
        return analysisService.filterRelations(operation, confidence, file, target, keyword);
    }

    @GetMapping("/analysis/sql-files")
    public List<String> analyzedFiles() {
        return analysisService.current().files().stream().map(report -> report.fileName()).toList();
    }

    /** De-duplicated table level result: one row per target object. */
    @GetMapping("/objects")
    public List<ObjectSummary> objects() {
        return objectSummaryService.summaries();
    }

    public record AnalysisTextRequest(String name, String content) {
    }

}
