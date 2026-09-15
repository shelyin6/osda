package com.osda.web;

import com.osda.analysis.service.AnalysisService;
import com.osda.config.OsdaProperties;
import com.osda.parser.SqlAstParser;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class HealthController {

    private final AnalysisService analysisService;
    private final OsdaProperties properties;
    private final SqlAstParser sqlAstParser;

    public HealthController(
            AnalysisService analysisService,
            OsdaProperties properties,
            SqlAstParser sqlAstParser
    ) {
        this.analysisService = analysisService;
        this.properties = properties;
        this.sqlAstParser = sqlAstParser;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "parserVersion", properties.getParserVersion(),
                "parserEngine", properties.getParserEngine(),
                // Actual implementation in use: differs from parserEngine when the configured
                // engine is not part of this build and the analyzer fell back to the built-in one.
                "parserEngineImpl", sqlAstParser.getClass().getSimpleName(),
                "hasAnalysis", analysisService.hasResult());
    }
}
