package com.osda.parser;

import com.osda.analysis.model.SourceLocation;

public record ParseIssue(String code, String message, SourceLocation location) {
}
