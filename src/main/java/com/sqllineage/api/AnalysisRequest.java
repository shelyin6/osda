package com.sqllineage.api;

import jakarta.validation.constraints.NotBlank;

public record AnalysisRequest(
        @NotBlank(message = "sourceName is required") String sourceName,
        @NotBlank(message = "sqlText is required") String sqlText
) {
}
