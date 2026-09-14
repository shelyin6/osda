package com.sqllineage.api;

import com.sqllineage.analysis.model.AnalysisResult;
import com.sqllineage.analysis.service.SqlDependencyAnalyzer;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class AnalysisController {
    private final SqlDependencyAnalyzer analyzer;

    public AnalysisController(SqlDependencyAnalyzer analyzer) {
        this.analyzer = analyzer;
    }

    @PostMapping("/analyses")
    public ResponseEntity<AnalysisResult> analyze(@Valid @RequestBody AnalysisRequest request) {
        return ResponseEntity.ok(analyzer.analyze(request.sourceName(), request.sqlText()));
    }
}
