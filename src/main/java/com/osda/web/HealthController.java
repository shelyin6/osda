package com.osda.web;

import com.osda.analysis.service.AnalysisService;
import com.osda.config.OsdaProperties;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class HealthController {

    private final AnalysisService analysisService;
    private final OsdaProperties properties;

    public HealthController(AnalysisService analysisService, OsdaProperties properties) {
        this.analysisService = analysisService;
        this.properties = properties;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "parserVersion", properties.getParserVersion(),
                "hasAnalysis", analysisService.hasResult());
    }
}
