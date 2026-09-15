package com.osda.parser;

import java.util.List;

public record ParsedFile(
        List<Ast.ProgramUnit> programUnits,
        List<Ast.Statement> topLevelStatements,
        List<ParseIssue> issues
) {
}
