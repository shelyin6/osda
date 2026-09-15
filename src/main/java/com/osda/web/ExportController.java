package com.osda.web;

import com.osda.analysis.service.AnalysisService;
import com.osda.analysis.service.ObjectSummaryService;
import com.osda.export.ExportService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/export")
public class ExportController {

    private final AnalysisService analysisService;
    private final ObjectSummaryService objectSummaryService;
    private final ExportService exportService;
    private final ObjectMapper objectMapper;

    public ExportController(
            AnalysisService analysisService,
            ObjectSummaryService objectSummaryService,
            ExportService exportService,
            ObjectMapper objectMapper
    ) {
        this.analysisService = analysisService;
        this.objectSummaryService = objectSummaryService;
        this.exportService = exportService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/relations.csv")
    public ResponseEntity<byte[]> relationsCsv() {
        byte[] body = exportService.relationsCsv(analysisService.current().relations());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"osda-relations.csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(body);
    }

    @GetMapping("/analysis.json")
    public ResponseEntity<byte[]> analysisJson() throws Exception {
        byte[] body = objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsBytes(analysisService.current());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"osda-analysis.json\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    /** De-duplicated table level export. */
    @GetMapping("/objects.csv")
    public ResponseEntity<byte[]> objectsCsv() {
        byte[] body = exportService.objectsCsv(objectSummaryService.summaries());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"osda-objects.csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(body);
    }
}
