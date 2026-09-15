package com.osda.parser;

import java.util.ArrayList;
import java.util.List;
import com.osda.parser.antlr.AntlrSqlAstParser;

/**
 * Best-of-both engine: strict grammar validation with a lenient safety net.
 *
 * <p>The ANTLR grammar gives a real syntax check and a precise tree. Real world production SQL is
 * not always valid Oracle SQL (typos such as {@code ASIN} instead of {@code AND} do occur), and a
 * strict grammar then loses whole FROM clauses during error recovery. In that case this engine
 * falls back to the built-in lenient parser so that phase one never silently drops a dependency,
 * and it keeps the grammar problems as structured warnings so the incomplete input is still
 * visible to the DBA.
 */
public final class HybridSqlAstParser implements SqlAstParser {

    private final SqlAstParser strict = new AntlrSqlAstParser();
    private final SqlAstParser lenient = new OraclePlSqlParser();

    @Override
    public ParsedFile parse(String source) {
        ParsedFile strictResult = strict.parse(source);
        List<ParseIssue> syntaxIssues = strictResult.issues().stream()
                .filter(issue -> issue.code() != null && issue.code().startsWith("OSDA-ANTLR"))
                .toList();
        if (syntaxIssues.isEmpty()) {
            return strictResult;
        }

        ParsedFile lenientResult = lenient.parse(source);
        List<ParseIssue> issues = new ArrayList<>();
        issues.add(new ParseIssue(
                "OSDA-HYBRID-001",
                "检测到 " + syntaxIssues.size() + " 处语法问题，已回退到内置宽松解析器以保证不遗漏常规 DML；"
                        + "语法问题位置见后续告警，建议先修正 SQL 语法",
                syntaxIssues.get(0).location()));
        issues.addAll(syntaxIssues);
        issues.addAll(lenientResult.issues());
        return new ParsedFile(
                lenientResult.programUnits(),
                lenientResult.topLevelStatements(),
                List.copyOf(issues));
    }
}
