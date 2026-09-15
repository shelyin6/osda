package com.osda.parser.antlr;

import com.osda.analysis.model.SourceLocation;
import com.osda.analysis.model.SourceType;
import com.osda.parser.Ast;
import com.osda.parser.ParseIssue;
import com.osda.parser.ParsedFile;
import com.osda.parser.SqlAstParser;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.tree.ParseTree;

/**
 * AST producer backed by the vendored ANTLR Oracle PL/SQL grammar.
 *
 * <p>It walks the ANTLR parse tree and maps it onto the same {@link Ast} model as the built-in
 * recursive descent parser, so both engines are interchangeable behind {@link SqlAstParser} and
 * can be compared relation by relation.
 */
public final class AntlrSqlAstParser implements SqlAstParser {

    @Override
    public ParsedFile parse(String source) {
        String text = source == null ? "" : source;
        List<ParseIssue> issues = new ArrayList<>();
        CharStream chars = CharStreams.fromString(text);
        PlSqlLexer lexer = new PlSqlLexer(chars);
        lexer.removeErrorListeners();
        lexer.addErrorListener(new IssueCollector(issues));

        CommonTokenStream tokenStream = new CommonTokenStream(lexer);
        PlSqlParser parser = new PlSqlParser(tokenStream);
        parser.removeErrorListeners();
        parser.addErrorListener(new IssueCollector(issues));

        ParseTree tree;
        try {
            tree = parser.sql_script();
        } catch (RuntimeException exception) {
            issues.add(new ParseIssue(
                    "OSDA-ANTLR-001",
                    "ANTLR 语法树构建失败：" + exception.getClass().getSimpleName()
                            + " " + exception.getMessage(),
                    SourceLocation.of(1, 1)));
            return new ParsedFile(List.of(), List.of(), List.copyOf(issues));
        }

        TreeWalker walker = new TreeWalker(tokenStream);
        walker.walk(tree);
        return new ParsedFile(walker.units(), walker.topLevelStatements(), List.copyOf(issues));
    }

    /** Converts ANTLR syntax errors into structured project warnings. */
    private static final class IssueCollector extends BaseErrorListener {

        private final List<ParseIssue> issues;

        private IssueCollector(List<ParseIssue> issues) {
            this.issues = issues;
        }

        @Override
        public void syntaxError(
                Recognizer<?, ?> recognizer,
                Object offendingSymbol,
                int line,
                int charPositionInLine,
                String message,
                RecognitionException exception
        ) {
            issues.add(new ParseIssue(
                    "OSDA-ANTLR-002",
                    "语法解析告警：" + message,
                    SourceLocation.of(Math.max(1, line), charPositionInLine + 1)));
        }
    }

    /** Walks the parse tree and builds program units plus statements. */
    private static final class TreeWalker {

        private final TokenStream tokenStream;
        private final List<Ast.ProgramUnit> units = new ArrayList<>();
        private final List<Ast.Statement> topLevelStatements = new ArrayList<>();
        private final Deque<UnitFrame> stack = new ArrayDeque<>();
        private final Set<ParserRuleContext> consumed =
                Collections.newSetFromMap(new IdentityHashMap<>());

        private TreeWalker(TokenStream tokenStream) {
            this.tokenStream = tokenStream;
        }

        List<Ast.ProgramUnit> units() {
            return List.copyOf(units);
        }

        List<Ast.Statement> topLevelStatements() {
            return List.copyOf(topLevelStatements);
        }

        void walk(ParseTree node) {
            if (!(node instanceof ParserRuleContext context)) {
                return;
            }
            String rule = PlSqlParser.ruleNames[context.getRuleIndex()];
            switch (rule) {
                case "create_procedure_body" ->
                        handleUnit(context, SourceType.PROCEDURE, unitName(context, "procedure_name"));
                case "create_function_body" ->
                        handleUnit(context, SourceType.FUNCTION, unitName(context, "function_name"));
                case "create_package_body" ->
                        handleUnit(context, SourceType.PACKAGE_BODY, unitName(context, "package_name"));
                case "create_package" ->
                        handleUnit(context, SourceType.PACKAGE, unitName(context, "package_name"));
                case "procedure_body" ->
                        handleUnit(context, SourceType.PROCEDURE, unitName(context, "identifier"));
                case "function_body" ->
                        handleUnit(context, SourceType.FUNCTION, unitName(context, "identifier"));
                case "select_statement" -> handleSelect(context);
                case "insert_statement" -> handleInsert(context);
                case "update_statement" -> handleUpdate(context);
                case "delete_statement" -> handleDelete(context);
                case "merge_statement" -> handleMerge(context);
                case "execute_immediate" -> handleDynamicSql(context);
                case "from_clause" -> {
                    // Sub queries can appear inside expressions (for example RETURN (SELECT ...)),
                    // where the grammar produces no select_statement node. Any FROM clause that no
                    // statement handler consumed is still a real read.
                    if (!consumed.contains(context)) {
                        harvestFromClause(context);
                    }
                }
                default -> walkChildren(context);
            }
        }

        private void walkChildren(ParserRuleContext context) {
            for (int index = 0; index < context.getChildCount(); index++) {
                walk(context.getChild(index));
            }
        }

        // ---------------------------------------------------------------- program units

        private void handleUnit(ParserRuleContext context, SourceType type, String[] parts) {
            UnitFrame parent = stack.peek();
            String schema = parts[0] != null
                    ? parts[0]
                    : (parent == null ? null : parent.schema);
            UnitFrame frame = new UnitFrame(schema, parts[1], type, context);
            stack.push(frame);
            walkChildren(context);
            stack.pop();
            units.add(new Ast.ProgramUnit(
                    schema,
                    parts[1],
                    type,
                    parent == null ? null : parent.qualified(),
                    location(context),
                    context.getStart().getStartIndex(),
                    endOffset(context),
                    List.copyOf(frame.statements)));
        }

        // ---------------------------------------------------------------- statements

        private void handleSelect(ParserRuleContext context) {
            List<Ast.TableRef> sources = collectSources(context);
            addStatement(new Ast.Select(
                    context.getStart().getStartIndex(),
                    endOffset(context),
                    location(context),
                    collectCtes(context),
                    sources));
        }

        private void handleInsert(ParserRuleContext context) {
            List<Ast.TableRef> sources = collectSources(context);
            List<Ast.Cte> ctes = collectCtes(context);
            for (ParserRuleContext into : findAll(context, "insert_into_clause")) {
                Ast.ObjectName target = tableNameOf(firstChild(into, "general_table_ref"));
                if (target == null) {
                    continue;
                }
                addStatement(new Ast.Insert(
                        context.getStart().getStartIndex(),
                        endOffset(context),
                        location(context),
                        target,
                        ctes,
                        sources));
            }
        }

        private void handleUpdate(ParserRuleContext context) {
            Ast.ObjectName target = tableNameOf(firstChild(context, "general_table_ref"));
            if (target == null) {
                return;
            }
            addStatement(new Ast.Update(
                    context.getStart().getStartIndex(),
                    endOffset(context),
                    location(context),
                    target,
                    collectSources(context)));
        }

        private void handleDelete(ParserRuleContext context) {
            Ast.ObjectName target = tableNameOf(firstChild(context, "general_table_ref"));
            if (target == null) {
                return;
            }
            addStatement(new Ast.Delete(
                    context.getStart().getStartIndex(),
                    endOffset(context),
                    location(context),
                    target,
                    collectSources(context)));
        }

        private void handleMerge(ParserRuleContext context) {
            List<ParserRuleContext> tableviews = findAll(context, "selected_tableview");
            if (tableviews.isEmpty()) {
                return;
            }
            Ast.ObjectName target = tableNameOf(tableviews.get(0));
            if (target == null) {
                return;
            }
            List<Ast.TableRef> sources = new ArrayList<>();
            for (int index = 1; index < tableviews.size(); index++) {
                Ast.ObjectName source = tableNameOf(tableviews.get(index));
                if (source != null) {
                    sources.add(new Ast.TableRef(source, null, false, source.location()));
                }
            }
            sources.addAll(collectSources(context));
            addStatement(new Ast.Merge(
                    context.getStart().getStartIndex(),
                    endOffset(context),
                    location(context),
                    target,
                    sources.isEmpty() ? null : sources.get(0).name(),
                    sources));
        }

        private void handleDynamicSql(ParserRuleContext context) {
            String extracted = constantSqlOf(firstChild(context, "expression"));
            String reason = extracted == null
                    ? "动态 SQL 由变量或表达式拼接，无法静态解析出完整语句"
                    : null;
            addStatement(new Ast.DynamicSql(
                    context.getStart().getStartIndex(),
                    endOffset(context),
                    location(context),
                    extracted,
                    reason));
        }

        private void addStatement(Ast.Statement statement) {
            UnitFrame frame = stack.peek();
            if (frame == null) {
                topLevelStatements.add(statement);
            } else {
                frame.statements.add(statement);
            }
        }

        // ---------------------------------------------------------------- extraction helpers

        /** Source tables come from FROM clauses; joins and nested sub queries live inside them. */
        private List<Ast.TableRef> collectSources(ParserRuleContext statement) {
            Set<String> cteNames = new LinkedHashSet<>();
            for (Ast.Cte cte : collectCtes(statement)) {
                cteNames.add(cteName(cte.name()));
            }
            List<Ast.TableRef> refs = new ArrayList<>();
            for (ParserRuleContext fromClause : findAll(statement, "from_clause")) {
                consumed.add(fromClause);
                collectTableRefs(fromClause, cteNames, refs);
            }
            return refs;
        }

        /** Builds a READ statement for a FROM clause that belongs to an unhandled statement type. */
        private void harvestFromClause(ParserRuleContext fromClause) {
            ParserRuleContext anchor = nearestStatement(fromClause);
            ParserRuleContext scope = anchor == null ? fromClause : anchor;
            List<Ast.Cte> ctes = collectCtes(scope);
            Set<String> cteNames = new LinkedHashSet<>();
            for (Ast.Cte cte : ctes) {
                cteNames.add(cteName(cte.name()));
            }
            List<Ast.TableRef> refs = new ArrayList<>();
            collectTableRefs(fromClause, cteNames, refs);
            if (refs.isEmpty()) {
                return;
            }
            addStatement(new Ast.Select(
                    scope.getStart().getStartIndex(),
                    endOffset(scope),
                    location(scope),
                    ctes,
                    refs));
        }

        private ParserRuleContext nearestStatement(ParserRuleContext context) {
            ParseTree parent = context.getParent();
            while (parent instanceof ParserRuleContext candidate) {
                String rule = PlSqlParser.ruleNames[candidate.getRuleIndex()];
                if (rule.endsWith("_statement")) {
                    return candidate;
                }
                parent = candidate.getParent();
            }
            return null;
        }

        private void collectTableRefs(ParserRuleContext scope, Set<String> cteNames, List<Ast.TableRef> refs) {
            for (ParserRuleContext tableview : findAll(scope, "tableview_name")) {
                Ast.ObjectName name = objectNameOf(tableview);
                if (name == null) {
                    continue;
                }
                boolean cte = cteNames.contains(cteName(name.qualified()));
                refs.add(new Ast.TableRef(name, null, cte, name.location()));
            }
        }

        private List<Ast.Cte> collectCtes(ParserRuleContext statement) {
            List<Ast.Cte> ctes = new ArrayList<>();
            for (ParserRuleContext factoring : findAll(statement, "subquery_factoring_clause")) {
                ParserRuleContext queryName = firstChild(factoring, "query_name");
                if (queryName != null) {
                    ctes.add(new Ast.Cte(queryName.getText(), location(queryName)));
                }
            }
            return ctes;
        }

        private String constantSqlOf(ParserRuleContext expression) {
            if (expression == null) {
                return null;
            }
            int from = expression.getStart().getTokenIndex();
            int to = expression.getStop().getTokenIndex();
            List<Token> significant = new ArrayList<>();
            for (int index = from; index <= to; index++) {
                Token token = tokenStream.get(index);
                if (token.getChannel() == Token.DEFAULT_CHANNEL) {
                    significant.add(token);
                }
            }
            StringBuilder builder = new StringBuilder();
            int index = 0;
            while (index < significant.size()) {
                Token literal = significant.get(index);
                String text = sqlTextOf(literal);
                if (text == null) {
                    return null;
                }
                builder.append(text);
                index++;
                if (index >= significant.size()) {
                    break;
                }
                // The lexer emits '||' as two BAR tokens.
                if (index + 1 >= significant.size()
                        || significant.get(index).getType() != PlSqlParser.BAR
                        || significant.get(index + 1).getType() != PlSqlParser.BAR) {
                    return null;
                }
                index += 2;
            }
            String sql = builder.toString().trim();
            return sql.isEmpty() ? null : sql;
        }

        /** Returns the SQL text of a string literal token, or null when it is not a literal. */
        private String sqlTextOf(Token token) {
            if (token.getType() == PlSqlParser.CHAR_STRING) {
                return unquote(token.getText());
            }
            // Oracle alternative quoting: q'[ ... ]', q'{ ... }', q'( ... )' and friends.
            String text = token.getText();
            if (text.length() > 4
                    && (text.startsWith("q'") || text.startsWith("Q'"))
                    && text.endsWith("'")) {
                return text.substring(3, text.length() - 1);
            }
            return null;
        }

        // ---------------------------------------------------------------- tree helpers

        private List<ParserRuleContext> findAll(ParseTree node, String ruleName) {
            List<ParserRuleContext> found = new ArrayList<>();
            collectAll(node, ruleName, found);
            return found;
        }

        private void collectAll(ParseTree node, String ruleName, List<ParserRuleContext> found) {
            if (node instanceof ParserRuleContext context) {
                if (ruleName.equals(PlSqlParser.ruleNames[context.getRuleIndex()])) {
                    found.add(context);
                    return;
                }
                for (int index = 0; index < context.getChildCount(); index++) {
                    collectAll(context.getChild(index), ruleName, found);
                }
            }
        }

        private ParserRuleContext firstChild(ParseTree node, String ruleName) {
            List<ParserRuleContext> found = findAll(node, ruleName);
            return found.isEmpty() ? null : found.get(0);
        }

        /**
         * Reads {@code (schema_object_name '.')? name} from the direct children of a program unit.
         *
         * <p>Using direct children avoids picking up a deeply nested definition that happens to
         * have the same rule name.
         */
        private String[] unitName(ParserRuleContext context, String nameRule) {
            String schema = null;
            String object = null;
            for (int index = 0; index < context.getChildCount(); index++) {
                if (!(context.getChild(index) instanceof ParserRuleContext child)) {
                    continue;
                }
                String rule = PlSqlParser.ruleNames[child.getRuleIndex()];
                if ("schema_object_name".equals(rule) && schema == null) {
                    schema = normalize(child.getText());
                } else if (nameRule.equals(rule)) {
                    object = normalize(child.getText());
                    break;
                }
            }
            if (object == null || object.isBlank()) {
                return new String[] {schema, "UNKNOWN"};
            }
            return new String[] {schema, object};
        }

        private Ast.ObjectName tableNameOf(ParserRuleContext node) {
            if (node == null) {
                return null;
            }
            ParserRuleContext tableview = firstChild(node, "tableview_name");
            return tableview == null ? null : objectNameOf(tableview);
        }

        private Ast.ObjectName objectNameOf(ParserRuleContext tableview) {
            if (!(tableview instanceof PlSqlParser.Tableview_nameContext context)) {
                return null;
            }
            if (context.identifier() == null) {
                return null;
            }
            String first = context.identifier().getText();
            String second = context.id_expression() == null ? null : context.id_expression().getText();
            String schema = second == null ? null : normalize(first);
            String object = normalize(second == null ? first : second);
            if (object == null || object.isBlank()) {
                return null;
            }
            return new Ast.ObjectName(schema, object, isQuoted(second == null ? first : second), location(tableview));
        }

        private String[] splitName(String rawName) {
            if (rawName == null || rawName.isBlank()) {
                return new String[] {null, "UNKNOWN"};
            }
            String trimmed = rawName.trim();
            int dot = trimmed.indexOf('.');
            if (dot < 0) {
                return new String[] {null, normalize(trimmed)};
            }
            return new String[] {
                    normalize(trimmed.substring(0, dot)),
                    normalize(trimmed.substring(dot + 1))
            };
        }

        private String normalize(String value) {
            if (value == null) {
                return null;
            }
            String trimmed = value.trim();
            if (trimmed.isEmpty()) {
                return null;
            }
            if (isQuoted(trimmed)) {
                return trimmed.substring(1, trimmed.length() - 1).replace("\"\"", "\"");
            }
            return trimmed.toUpperCase(Locale.ROOT);
        }

        private boolean isQuoted(String value) {
            return value != null && value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"");
        }

        private String cteName(String value) {
            return normalize(value);
        }

        private String unquote(String literal) {
            if (literal.length() >= 2 && literal.startsWith("'") && literal.endsWith("'")) {
                return literal.substring(1, literal.length() - 1).replace("''", "'");
            }
            return literal;
        }

        private SourceLocation location(ParserRuleContext context) {
            Token start = context.getStart();
            Token stop = context.getStop();
            int endLine = stop == null ? start.getLine() : stop.getLine();
            int endColumn = stop == null ? start.getCharPositionInLine() + 1 : stop.getCharPositionInLine() + 1;
            return new SourceLocation(start.getLine(), start.getCharPositionInLine() + 1, endLine, endColumn);
        }

        private int endOffset(ParserRuleContext context) {
            Token stop = context.getStop();
            return stop == null ? context.getStart().getStartIndex() : stop.getStopIndex() + 1;
        }

        private static final class UnitFrame {

            private final String schema;
            private final String name;
            private final SourceType type;
            private final ParserRuleContext context;
            private final List<Ast.Statement> statements = new ArrayList<>();

            private UnitFrame(String schema, String name, SourceType type, ParserRuleContext context) {
                this.schema = schema;
                this.name = name;
                this.type = type;
                this.context = context;
            }

            private String qualified() {
                return schema == null || schema.isBlank() ? name : schema + "." + name;
            }
        }
    }
}
