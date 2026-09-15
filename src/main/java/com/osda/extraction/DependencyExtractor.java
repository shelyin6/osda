package com.osda.extraction;

import com.osda.analysis.model.Confidence;
import com.osda.analysis.model.DependencyRelation;
import com.osda.analysis.model.OperationType;
import com.osda.analysis.model.ParseWarning;
import com.osda.analysis.model.SourceLocation;
import com.osda.analysis.model.SourceType;
import com.osda.analysis.model.TargetType;
import com.osda.parser.Ast;
import com.osda.parser.ParseIssue;
import com.osda.parser.ParsedFile;
import com.osda.parser.SqlAstParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Turns the syntax tree into {@link DependencyRelation} records plus structured warnings.
 *
 * <p>Extraction rules follow the project dependency standard: every relation carries the source
 * unit, the target object, the operation, a confidence value and the original SQL fragment.
 */
@Component
public class DependencyExtractor {

    /** Oracle pseudo table: never a real dependency. */
    private static final Set<String> IGNORED_OBJECTS = Set.of("DUAL");

    private final SqlAstParser parser;

    public DependencyExtractor(SqlAstParser parser) {
        this.parser = parser;
    }

    public ExtractionResult extract(String fileName, String source, int snippetLength) {
        ParsedFile parsedFile = parse(source);
        List<DependencyRelation> relations = new ArrayList<>();
        List<ParseWarning> warnings = new ArrayList<>();

        int unitCount = parsedFile.programUnits().size();

        for (Ast.ProgramUnit unit : parsedFile.programUnits()) {
            for (Ast.Statement statement : unit.statements()) {
                collect(relations, warnings, source, fileName, unit.qualifiedName(), unit.type(),
                        statement, Confidence.HIGH, false, null, null, snippetLength);
            }
        }

        if (!parsedFile.topLevelStatements().isEmpty()) {
            String unitName = fileName;
            for (Ast.Statement statement : parsedFile.topLevelStatements()) {
                collect(relations, warnings, source, fileName, unitName, SourceType.SQL_SCRIPT,
                        statement, Confidence.HIGH, false, null, null, snippetLength);
            }
        }

        for (ParseIssue issue : parsedFile.issues()) {
            warnings.add(new ParseWarning(issue.code(), issue.message(), fileName, issue.location()));
        }

        List<DependencyRelation> merged = mergeReadWrite(relations);
        return new ExtractionResult(merged, warnings, unitCount);
    }

    private void collect(
            List<DependencyRelation> relations,
            List<ParseWarning> warnings,
            String source,
            String fileName,
            String sourceUnit,
            SourceType sourceType,
            Ast.Statement statement,
            Confidence baseConfidence,
            boolean dynamic,
            SourceLocation evidenceLocation,
            String evidenceSnippet,
            int snippetLength
    ) {
        switch (statement) {
            case Ast.Select select -> {
                for (Ast.TableRef ref : select.sources()) {
                    add(relations, source, fileName, sourceUnit, sourceType, ref, OperationType.READ,
                            baseConfidence, dynamic, statement, evidenceLocation, evidenceSnippet, snippetLength);
                }
            }
            case Ast.Insert insert -> {
                add(relations, source, fileName, sourceUnit, sourceType, insert.target(), OperationType.INSERT,
                        baseConfidence, dynamic, statement, evidenceLocation, evidenceSnippet, snippetLength);
                for (Ast.TableRef ref : insert.sources()) {
                    add(relations, source, fileName, sourceUnit, sourceType, ref, OperationType.READ,
                            baseConfidence, dynamic, statement, evidenceLocation, evidenceSnippet, snippetLength);
                }
            }
            case Ast.Update update -> {
                add(relations, source, fileName, sourceUnit, sourceType, update.target(), OperationType.UPDATE,
                        baseConfidence, dynamic, statement, evidenceLocation, evidenceSnippet, snippetLength);
                for (Ast.TableRef ref : update.sources()) {
                    add(relations, source, fileName, sourceUnit, sourceType, ref, OperationType.READ,
                            baseConfidence, dynamic, statement, evidenceLocation, evidenceSnippet, snippetLength);
                }
            }
            case Ast.Delete delete -> {
                add(relations, source, fileName, sourceUnit, sourceType, delete.target(), OperationType.DELETE,
                        baseConfidence, dynamic, statement, evidenceLocation, evidenceSnippet, snippetLength);
                for (Ast.TableRef ref : delete.sources()) {
                    add(relations, source, fileName, sourceUnit, sourceType, ref, OperationType.READ,
                            baseConfidence, dynamic, statement, evidenceLocation, evidenceSnippet, snippetLength);
                }
            }
            case Ast.Merge merge -> {
                add(relations, source, fileName, sourceUnit, sourceType, merge.target(), OperationType.MERGE,
                        baseConfidence, dynamic, statement, evidenceLocation, evidenceSnippet, snippetLength);
                for (Ast.TableRef ref : merge.sources()) {
                    add(relations, source, fileName, sourceUnit, sourceType, ref, OperationType.READ,
                            baseConfidence, dynamic, statement, evidenceLocation, evidenceSnippet, snippetLength);
                }
            }
            case Ast.DynamicSql dynamicSql -> collectDynamicSql(
                    relations, warnings, source, fileName, sourceUnit, sourceType,
                    dynamicSql, snippetLength);
            case Ast.Unparsed unparsed -> warnings.add(new ParseWarning(
                    "OSDA-PARSE-100", unparsed.note(), fileName, unparsed.location()));
        }
    }

    /**
     * EXECUTE IMMEDIATE handling.
     *
     * <p>A constant SQL literal is parsed recursively but can never exceed MEDIUM confidence.
     * A concatenated or variable based argument produces a warning and no relation, because the
     * dependency cannot be stated statically.
     */
    private void collectDynamicSql(
            List<DependencyRelation> relations,
            List<ParseWarning> warnings,
            String source,
            String fileName,
            String sourceUnit,
            SourceType sourceType,
            Ast.DynamicSql dynamicSql,
            int snippetLength
    ) {
        if (dynamicSql.extractedSql() == null) {
            warnings.add(new ParseWarning(
                    "OSDA-DYNAMIC-001",
                    "动态 SQL 无法静态解析，未产生依赖关系：" + dynamicSql.reason(),
                    fileName,
                    dynamicSql.location()));
            return;
        }

        ParsedFile inner = parse(dynamicSql.extractedSql());
        SourceLocation evidenceLocation = dynamicSql.location();
        String evidenceSnippet = snippet(source, dynamicSql, snippetLength);
        boolean produced = false;
        for (Ast.Statement statement : inner.topLevelStatements()) {
            collect(relations, warnings, dynamicSql.extractedSql(), fileName, sourceUnit, sourceType,
                    statement, Confidence.MEDIUM, true, evidenceLocation, evidenceSnippet, snippetLength);
            produced = true;
        }
        for (Ast.ProgramUnit unit : inner.programUnits()) {
            for (Ast.Statement statement : unit.statements()) {
                collect(relations, warnings, dynamicSql.extractedSql(), fileName, sourceUnit, sourceType,
                        statement, Confidence.MEDIUM, true, evidenceLocation, evidenceSnippet, snippetLength);
                produced = true;
            }
        }
        if (!produced) {
            warnings.add(new ParseWarning(
                    "OSDA-DYNAMIC-002",
                    "常量动态 SQL 中未识别到依赖关系",
                    fileName,
                    dynamicSql.location()));
        }
        for (ParseIssue issue : inner.issues()) {
            warnings.add(new ParseWarning(
                    "OSDA-DYNAMIC-003",
                    "常量动态 SQL 解析告警：" + issue.message(),
                    fileName,
                    dynamicSql.location()));
        }
    }

    private void add(
            List<DependencyRelation> relations,
            String source,
            String fileName,
            String sourceUnit,
            SourceType sourceType,
            Ast.TableRef ref,
            OperationType operation,
            Confidence confidence,
            boolean dynamic,
            Ast.Statement statement,
            SourceLocation evidenceLocation,
            String evidenceSnippet,
            int snippetLength
    ) {
        if (ref.cteReference()) {
            return;
        }
        add(relations, source, fileName, sourceUnit, sourceType, ref.name(), operation, confidence,
                dynamic, statement, evidenceLocation, evidenceSnippet, snippetLength);
    }

    private void add(
            List<DependencyRelation> relations,
            String source,
            String fileName,
            String sourceUnit,
            SourceType sourceType,
            Ast.ObjectName object,
            OperationType operation,
            Confidence confidence,
            boolean dynamic,
            Ast.Statement statement,
            SourceLocation evidenceLocation,
            String evidenceSnippet,
            int snippetLength
    ) {
        if (object == null) {
            return;
        }
        if (!object.quoted() && IGNORED_OBJECTS.contains(object.object().toUpperCase(Locale.ROOT))) {
            return;
        }
        SourceLocation location = evidenceLocation != null
                ? evidenceLocation
                : (dynamic ? statement.location() : object.location());
        String snippet = evidenceSnippet != null ? evidenceSnippet : snippet(source, statement, snippetLength);
        String id = fileName + "#" + sourceUnit + "#" + operation + "#" + location.line()
                + ":" + location.column() + "#" + object.qualified();
        relations.add(new DependencyRelation(
                id,
                sourceUnit,
                sourceType,
                fileName,
                location,
                object.schema(),
                object.object(),
                TargetType.TABLE,
                operation,
                confidence,
                snippet,
                dynamic,
                null
        ));
    }

    private String snippet(String source, Ast.Statement statement, int snippetLength) {
        int from = Math.max(0, Math.min(statement.startOffset(), source.length()));
        int to = Math.max(from, Math.min(statement.endOffset(), source.length()));
        String fragment = source.substring(from, to).trim();
        if (fragment.length() > snippetLength) {
            fragment = fragment.substring(0, snippetLength) + " ...";
        }
        return fragment;
    }

    /**
     * Adds a derived READ_WRITE relation when the same program unit reads and writes the same
     * object, keeping the original single-direction relations untouched.
     */
    private List<DependencyRelation> mergeReadWrite(List<DependencyRelation> relations) {
        Map<String, List<DependencyRelation>> grouped = new LinkedHashMap<>();
        for (DependencyRelation relation : relations) {
            grouped.computeIfAbsent(relation.sourceUnit() + "|" + relation.targetKey(),
                    key -> new ArrayList<>()).add(relation);
        }
        List<DependencyRelation> enriched = new ArrayList<>(relations);
        for (Map.Entry<String, List<DependencyRelation>> entry : grouped.entrySet()) {
            List<DependencyRelation> group = entry.getValue();
            DependencyRelation read = group.stream().filter(DependencyRelation::reads).findFirst().orElse(null);
            DependencyRelation write = group.stream().filter(DependencyRelation::writes).findFirst().orElse(null);
            boolean alreadyReadWrite = group.stream()
                    .anyMatch(relation -> relation.operation() == OperationType.READ_WRITE);
            if (read == null || write == null || alreadyReadWrite) {
                continue;
            }
            DependencyRelation base = read.sourceLocation().line() <= write.sourceLocation().line() ? read : write;
            Confidence confidence = read.confidence() == Confidence.LOW || write.confidence() == Confidence.LOW
                    ? Confidence.LOW
                    : (read.confidence() == Confidence.MEDIUM || write.confidence() == Confidence.MEDIUM
                    ? Confidence.MEDIUM : Confidence.HIGH);
            enriched.add(new DependencyRelation(
                    base.id() + "#READ_WRITE",
                    base.sourceUnit(),
                    base.sourceType(),
                    base.sourceFile(),
                    base.sourceLocation(),
                    base.targetSchema(),
                    base.targetObject(),
                    base.targetType(),
                    OperationType.READ_WRITE,
                    confidence,
                    base.sqlSnippet(),
                    base.dynamicSql(),
                    "同一程序单元既读取又写入该对象"
            ));
        }
        return dedupe(enriched);
    }

    private List<DependencyRelation> dedupe(List<DependencyRelation> relations) {
        Set<String> seen = new LinkedHashSet<>();
        List<DependencyRelation> result = new ArrayList<>(relations.size());
        for (DependencyRelation relation : relations) {
            if (seen.add(relation.id())) {
                result.add(relation);
            }
        }
        return result;
    }

    /** Guarded because a parser instance may keep per-run state. */
    private ParsedFile parse(String source) {
        synchronized (parser) {
            return parser.parse(source);
        }
    }

    public record ExtractionResult(
            List<DependencyRelation> relations,
            List<ParseWarning> warnings,
            int programUnitCount
    ) {
    }
}
