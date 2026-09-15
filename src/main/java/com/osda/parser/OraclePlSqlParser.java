package com.osda.parser;

import com.osda.analysis.model.SourceLocation;
import com.osda.analysis.model.SourceType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Offline Oracle SQL/PLSQL parser.
 *
 * <p>Structure: lexer -> token stream -> recursive descent over statements and program units ->
 * shallow syntax tree ({@link Ast}). No regular expression is used to decide dependencies; all
 * decisions are taken on classified tokens, which keeps comments and string literals from
 * producing false objects.
 *
 * <p>Scope of this first iteration: SELECT / INSERT / UPDATE / DELETE / MERGE, CTEs, sub queries,
 * joins, aliases, PROCEDURE / FUNCTION / PACKAGE BODY boundaries, nested sub programs, anonymous
 * blocks and EXECUTE IMMEDIATE. Unsupported constructs never abort the file: they produce a
 * structured issue and parsing continues.
 */
public final class OraclePlSqlParser implements SqlAstParser {

    private static final Set<String> ALIAS_STOP_WORDS = Set.of(
            "AS", "ON", "WHERE", "GROUP", "ORDER", "HAVING", "LEFT", "RIGHT", "FULL", "INNER",
            "OUTER", "CROSS", "JOIN", "UNION", "MINUS", "INTERSECT", "CONNECT", "START", "MODEL",
            "FETCH", "OFFSET", "RETURNING", "PARTITION", "SET", "VALUES", "USING", "SELECT", "FROM",
            "AND", "OR", "NOT", "WHEN", "THEN", "ELSE", "END", "IS", "IN", "EXISTS", "BETWEEN",
            "LIKE", "ASC", "DESC", "NULLS", "PIVOT", "UNPIVOT", "SAMPLE", "WITH", "OVER", "KEEP",
            "LOOP", "BULK", "RETURN", "LIMIT"
    );

    private static final Set<String> CLAUSE_KEYWORDS = Set.of(
            "WHERE", "GROUP", "ORDER", "HAVING", "CONNECT", "START", "MODEL", "FETCH", "OFFSET",
            "UNION", "MINUS", "INTERSECT", "RETURNING", "PIVOT", "UNPIVOT"
    );

    private static final Set<String> STATEMENT_KEYWORDS = Set.of(
            "SELECT", "WITH", "INSERT", "UPDATE", "DELETE", "MERGE", "EXECUTE"
    );

    /** DDL and session statements are out of scope for phase one and are skipped without noise. */
    private static final Set<String> SKIPPED_DDL_KEYWORDS = Set.of(
            "CREATE", "ALTER", "DROP", "GRANT", "REVOKE", "COMMENT", "ANALYZE", "TRUNCATE",
            "SET", "COMMIT", "ROLLBACK", "DECLARE"
    );

    private String source = "";
    private List<Token> tokens = List.of();
    private int pos;
    private List<ParseIssue> issues = new ArrayList<>();
    private List<Ast.ProgramUnit> nestedUnits = new ArrayList<>();

    @Override
    public ParsedFile parse(String source) {
        this.source = source == null ? "" : source;
        this.tokens = new Lexer(this.source).tokenize();
        this.pos = 0;
        this.issues = new ArrayList<>();
        this.nestedUnits = new ArrayList<>();

        List<Ast.ProgramUnit> units = new ArrayList<>();
        List<Ast.Statement> topLevel = new ArrayList<>();

        while (!atEnd()) {
            Token token = peek();
            if (token.isKeyword("CREATE") || token.isKeyword("DECLARE") || token.isKeyword("BEGIN")) {
                int before = pos;
                Ast.ProgramUnit unit = parseProgramUnitOrBlock();
                if (unit != null) {
                    units.add(unit);
                    continue;
                }
                pos = before;
            }
            if (isStatementStart(peek())) {
                Ast.Statement statement = parseStatement();
                if (statement != null) {
                    topLevel.add(statement);
                    continue;
                }
            }
            if (peek().isSymbol("/") || peek().isSymbol(";")) {
                next();
                continue;
            }
            skipUnsupportedStatement();
        }
        units.addAll(nestedUnits);
        return new ParsedFile(List.copyOf(units), List.copyOf(topLevel), List.copyOf(issues));
    }

    // ------------------------------------------------------------------ program units

    private Ast.ProgramUnit parseProgramUnitOrBlock() {
        if (peek().isKeyword("DECLARE") || peek().isKeyword("BEGIN")) {
            return parseAnonymousBlock();
        }
        return parseCreateProgramUnit();
    }

    private Ast.ProgramUnit parseCreateProgramUnit() {
        Token start = peek();
        int save = pos;
        next();
        if (peek().isKeyword("OR")) {
            next();
            if (peek().isKeyword("REPLACE")) {
                next();
            } else {
                pos = save;
                return null;
            }
        }
        if (peek().isKeyword("EDITIONABLE") || peek().isKeyword("NONEDITIONABLE")) {
            next();
        }

        SourceType type;
        if (peek().isKeyword("PROCEDURE")) {
            type = SourceType.PROCEDURE;
        } else if (peek().isKeyword("FUNCTION")) {
            type = SourceType.FUNCTION;
        } else if (peek().isKeyword("PACKAGE")) {
            type = SourceType.PACKAGE;
        } else {
            pos = save;
            return null;
        }
        next();
        if (type == SourceType.PACKAGE && peek().isKeyword("BODY")) {
            type = SourceType.PACKAGE_BODY;
            next();
        }

        Ast.ObjectName name = parseObjectName();
        if (name == null) {
            addIssue("OSDA-PARSE-001", "程序单元名称缺失，已跳过该程序单元", loc(start));
            skipToStatementEnd();
            return null;
        }

        if (!skipToBodyDeclaration()) {
            addIssue("OSDA-PARSE-002", "未找到 IS/AS 子句，程序单元正文无法定位", loc(start));
            return new Ast.ProgramUnit(
                    name.schema(), name.object(), type, null, loc(start),
                    start.startOffset(), previousEndOffset(), List.of());
        }

        List<Ast.Statement> statements = new ArrayList<>();
        int endOffset = parseUnitBody(name, type, null, statements, true, new ArrayDeque<>());
        return new Ast.ProgramUnit(
                name.schema(), name.object(), type, null, loc(start),
                start.startOffset(), endOffset, List.copyOf(statements));
    }

    private Ast.ProgramUnit parseAnonymousBlock() {
        Token start = peek();
        boolean declarations = peek().isKeyword("DECLARE");
        if (declarations) {
            next();
        }
        List<Ast.Statement> statements = new ArrayList<>();
        Deque<String> stack = new ArrayDeque<>();
        boolean inDeclarations = declarations;
        String name = "ANONYMOUS@" + start.line();
        int endOffset = parseUnitBody(
                new Ast.ObjectName(null, name, false, loc(start)),
                SourceType.SQL_SCRIPT,
                null,
                statements,
                inDeclarations,
                stack);
        return new Ast.ProgramUnit(
                null, name, SourceType.SQL_SCRIPT, null, loc(start),
                start.startOffset(), endOffset, List.copyOf(statements));
    }

    private void parseNestedSubprogram(Ast.ObjectName parentUnit, Token start) {
        SourceType type = peek().isKeyword("FUNCTION") ? SourceType.FUNCTION : SourceType.PROCEDURE;
        next();
        Ast.ObjectName name = parseObjectName();
        if (name == null) {
            skipToStatementEnd();
            return;
        }
        // A nested sub program belongs to the schema of its container when it is not qualified.
        String schema = name.schema() != null ? name.schema() : parentUnit.schema();
        Ast.ObjectName resolved = new Ast.ObjectName(schema, name.object(), name.quoted(), name.location());
        if (!skipToBodyDeclaration()) {
            addIssue("OSDA-PARSE-002", "嵌套子程序缺少 IS/AS 子句", loc(start));
            return;
        }
        List<Ast.Statement> statements = new ArrayList<>();
        int endOffset = parseUnitBody(
                resolved, type, parentUnit.qualified(), statements, true, new ArrayDeque<>());
        nestedUnits.add(new Ast.ProgramUnit(
                resolved.schema(), resolved.object(), type, parentUnit.qualified(), loc(start),
                start.startOffset(), endOffset, List.copyOf(statements)));
    }

    /**
     * Walks the declaration section and the executable body of a program unit.
     *
     * <p>BEGIN / IF / LOOP / CASE push a frame, every END pops one; the unit is complete when the
     * outermost frame is closed. Statements inside the unit are parsed with the same SQL parser,
     * so reads and writes keep their own evidence positions.
     */
    private int parseUnitBody(
            Ast.ObjectName unitName,
            SourceType type,
            String container,
            List<Ast.Statement> statements,
            boolean declarations,
            Deque<String> stack
    ) {
        while (!atEnd()) {
            Token token = peek();
            if (token.type() == TokenType.EOF || token.isSymbol("/")) {
                break;
            }
            if (declarations) {
                // A package specification has no BEGIN block; a new CREATE statement always ends it.
                if (token.isKeyword("CREATE")) {
                    break;
                }
                if (token.isKeyword("BEGIN")) {
                    declarations = false;
                    stack.push("BEGIN");
                    next();
                    continue;
                }
                if (token.isKeyword("PROCEDURE") || token.isKeyword("FUNCTION")) {
                    parseNestedSubprogram(unitName, token);
                    continue;
                }
                if (isStatementStart(token)) {
                    Ast.Statement statement = parseStatement();
                    if (statement != null) {
                        statements.add(statement);
                        continue;
                    }
                }
                next();
                continue;
            }

            switch (token.type() == TokenType.IDENTIFIER ? token.text().toUpperCase(Locale.ROOT) : "") {
                case "BEGIN", "IF", "LOOP", "CASE" -> {
                    stack.push(token.text().toUpperCase(Locale.ROOT));
                    next();
                    continue;
                }
                case "END" -> {
                    Token endToken = next();
                    if (peek().isAnyKeyword("IF", "LOOP", "CASE")) {
                        next();
                    } else if (isIdentifierLike(peek()) && !peek().isSymbol(";")) {
                        next();
                    }
                    if (peek().isSymbol(";")) {
                        next();
                    }
                    if (!stack.isEmpty()) {
                        stack.pop();
                    }
                    if (stack.isEmpty()) {
                        return endToken.endOffset();
                    }
                    continue;
                }
                default -> {
                    if (isStatementStart(token)) {
                        Ast.Statement statement = parseStatement();
                        if (statement != null) {
                            statements.add(statement);
                            continue;
                        }
                    }
                    next();
                }
            }
        }
        addIssue("OSDA-PARSE-003",
                "程序单元 " + unitName.qualified() + " 未找到配对的 END，已按文件末尾结束",
                unitName.location());
        return previousEndOffset();
    }

    private boolean skipToBodyDeclaration() {
        while (!atEnd()) {
            Token token = peek();
            if (token.isSymbol("(")) {
                skipBalanced();
                continue;
            }
            if (token.isKeyword("IS") || token.isKeyword("AS")) {
                next();
                return true;
            }
            if (token.isSymbol(";") || token.type() == TokenType.EOF) {
                return false;
            }
            next();
        }
        return false;
    }

    // ------------------------------------------------------------------ statements

    private Ast.Statement parseStatement() {
        Token token = peek();
        if (token.isKeyword("EXECUTE")) {
            return parseDynamicSql();
        }
        if (token.isKeyword("INSERT")) {
            return parseInsert();
        }
        if (token.isKeyword("UPDATE")) {
            return parseUpdate();
        }
        if (token.isKeyword("DELETE")) {
            return parseDelete();
        }
        if (token.isKeyword("MERGE")) {
            return parseMerge();
        }
        if (token.isKeyword("SELECT") || token.isKeyword("WITH")) {
            return parseSelect();
        }
        return null;
    }

    private Ast.Statement parseSelect() {
        Token start = peek();
        List<Ast.Cte> ctes = new ArrayList<>();
        List<Ast.TableRef> sources = new ArrayList<>();
        scanQueryExpression(sources, ctes, new HashSet<>(), false);
        int end = consumeTerminator();
        return new Ast.Select(start.startOffset(), end, loc(start), List.copyOf(ctes), List.copyOf(sources));
    }

    private Ast.Statement parseInsert() {
        Token start = peek();
        next();
        if (peek().isKeyword("OVERWRITE")) {
            next();
        }
        if (peek().isKeyword("INTO")) {
            next();
        }
        if (peek().isKeyword("TABLE")) {
            next();
        }
        Ast.ObjectName target = parseObjectName();
        if (target == null) {
            addIssue("OSDA-PARSE-004", "INSERT 缺少目标对象，该语句未产生依赖", loc(start));
            skipToStatementEnd();
            return null;
        }
        if (peek().isSymbol("(") && !isQueryStart(pos + 1)) {
            skipBalanced();
        }
        List<Ast.Cte> ctes = new ArrayList<>();
        List<Ast.TableRef> sources = new ArrayList<>();
        scanQueryExpression(sources, ctes, new HashSet<>(), false);
        int end = consumeTerminator();
        return new Ast.Insert(
                start.startOffset(), end, loc(start), target, List.copyOf(ctes), List.copyOf(sources));
    }

    private Ast.Statement parseUpdate() {
        Token start = peek();
        next();
        Ast.ObjectName target = parseObjectName();
        if (target == null) {
            addIssue("OSDA-PARSE-005", "UPDATE 缺少目标对象，该语句未产生依赖", loc(start));
            skipToStatementEnd();
            return null;
        }
        List<Ast.TableRef> sources = new ArrayList<>();
        scanQueryExpression(sources, new ArrayList<>(), new HashSet<>(), false);
        int end = consumeTerminator();
        return new Ast.Update(start.startOffset(), end, loc(start), target, List.copyOf(sources));
    }

    private Ast.Statement parseDelete() {
        Token start = peek();
        next();
        if (peek().isKeyword("FROM")) {
            next();
        }
        Ast.ObjectName target = parseObjectName();
        if (target == null) {
            addIssue("OSDA-PARSE-006", "DELETE 缺少目标对象，该语句未产生依赖", loc(start));
            skipToStatementEnd();
            return null;
        }
        List<Ast.TableRef> sources = new ArrayList<>();
        scanQueryExpression(sources, new ArrayList<>(), new HashSet<>(), false);
        int end = consumeTerminator();
        return new Ast.Delete(start.startOffset(), end, loc(start), target, List.copyOf(sources));
    }

    private Ast.Statement parseMerge() {
        Token start = peek();
        next();
        if (peek().isKeyword("INTO")) {
            next();
        }
        Ast.ObjectName target = parseObjectName();
        if (target == null) {
            addIssue("OSDA-PARSE-007", "MERGE 缺少目标对象，该语句未产生依赖", loc(start));
            skipToStatementEnd();
            return null;
        }
        List<Ast.TableRef> sources = new ArrayList<>();
        scanQueryExpression(sources, new ArrayList<>(), new HashSet<>(), false);
        int end = consumeTerminator();
        Ast.ObjectName mergeSource = sources.isEmpty() ? null : sources.get(0).name();
        return new Ast.Merge(
                start.startOffset(), end, loc(start), target, mergeSource, List.copyOf(sources));
    }

    private Ast.Statement parseDynamicSql() {
        Token start = peek();
        next();
        if (!peek().isKeyword("IMMEDIATE")) {
            addIssue("OSDA-PARSE-008", "EXECUTE 后未跟随 IMMEDIATE，该语句按无法静态解析处理", loc(start));
            skipToStatementEnd();
            return new Ast.DynamicSql(
                    start.startOffset(), previousEndOffset(), loc(start), null,
                    "EXECUTE 后未跟随 IMMEDIATE，无法静态解析");
        }
        next();

        List<Token> expression = new ArrayList<>();
        while (!atEnd()
                && !peek().isSymbol(";")
                && !peek().isSymbol("/")
                && !peek().isKeyword("USING")
                && !peek().isKeyword("INTO")
                && !peek().isKeyword("BULK")) {
            expression.add(next());
        }
        int end = consumeTerminator();

        String extracted = extractConstantSql(expression);
        String reason = extracted == null
                ? "动态 SQL 由变量或表达式拼接，无法静态解析出完整语句"
                : null;
        return new Ast.DynamicSql(start.startOffset(), end, loc(start), extracted, reason);
    }

    /** Returns the SQL text when the EXECUTE IMMEDIATE argument is a constant literal. */
    private String extractConstantSql(List<Token> expression) {
        if (expression.isEmpty()) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        boolean expectLiteral = true;
        for (Token token : expression) {
            if (token.type() == TokenType.STRING) {
                if (!expectLiteral) {
                    return null;
                }
                builder.append(unquote(token.text()));
                expectLiteral = false;
                continue;
            }
            if (token.isSymbol("||")) {
                expectLiteral = true;
                continue;
            }
            if (token.type() == TokenType.IDENTIFIER && token.text().equalsIgnoreCase("q")
                    && expression.size() == 2) {
                return null;
            }
            return null;
        }
        if (expectLiteral) {
            return null;
        }
        String sql = builder.toString().trim();
        // Oracle alternative quoting: q'[ ... ]'
        if (expression.size() == 2
                && expression.get(0).text().equalsIgnoreCase("q")
                && expression.get(1).type() == TokenType.STRING) {
            String raw = unquote(expression.get(1).text());
            if (raw.length() > 2) {
                sql = raw.substring(1, raw.length() - 1).trim();
            }
        }
        return sql.isEmpty() ? null : sql;
    }

    // ------------------------------------------------------------------ scanning

    /**
     * Scans a query or DML tail: collects CTEs, table references and nested sub queries until the
     * statement terminator. The scan is token based and never inspects raw characters.
     */
    private void scanQueryExpression(
            List<Ast.TableRef> sources,
            List<Ast.Cte> ctes,
            Set<String> visibleCtes,
            boolean stopAtCloseParen
    ) {
        if (peek().isKeyword("WITH")) {
            next();
            while (true) {
                Token nameToken = peek();
                if (!isIdentifierLike(nameToken)) {
                    break;
                }
                String cteName = normalizedKey(nameToken);
                next();
                if (peek().isSymbol("(") && !isQueryStart(pos + 1)) {
                    skipBalanced();
                }
                if (!peek().isKeyword("AS")) {
                    addIssue("OSDA-PARSE-009", "CTE 定义缺少 AS 子句", loc(nameToken));
                    break;
                }
                next();
                if (!peek().isSymbol("(")) {
                    addIssue("OSDA-PARSE-010", "CTE 定义缺少子查询", loc(nameToken));
                    break;
                }
                visibleCtes.add(cteName);
                ctes.add(new Ast.Cte(nameToken.text(), loc(nameToken)));
                next();
                List<Ast.TableRef> innerSources = new ArrayList<>();
                scanQueryExpression(innerSources, new ArrayList<>(), visibleCtes, true);
                if (peek().isSymbol(")")) {
                    next();
                }
                sources.addAll(innerSources);
                if (peek().isSymbol(",")) {
                    next();
                    continue;
                }
                break;
            }
        }

        boolean inFromList = false;
        int caseDepth = 0;
        while (!atEnd()) {
            Token token = peek();
            if (token.type() == TokenType.EOF || token.isSymbol(";")) {
                return;
            }
            if (token.isSymbol("/") && token.column() == 1) {
                return;
            }
            if (stopAtCloseParen && token.isSymbol(")")) {
                return;
            }
            if (token.isKeyword("CASE")) {
                caseDepth++;
                next();
                continue;
            }
            if (token.isKeyword("END")) {
                // END of a CASE expression belongs to the statement; any other END is a block boundary.
                if (caseDepth > 0) {
                    caseDepth--;
                    next();
                    continue;
                }
                return;
            }
            if (token.isSymbol("(")) {
                if (isQueryStart(pos + 1)) {
                    next();
                    List<Ast.TableRef> innerSources = new ArrayList<>();
                    scanQueryExpression(innerSources, new ArrayList<>(), visibleCtes, true);
                    if (peek().isSymbol(")")) {
                        next();
                    }
                    sources.addAll(innerSources);
                    continue;
                }
                skipBalanced();
                continue;
            }
            if (token.isKeyword("FROM") || token.isKeyword("JOIN") || token.isKeyword("USING")) {
                next();
                collectTableReference(sources, visibleCtes);
                inFromList = true;
                continue;
            }
            if (token.type() == TokenType.IDENTIFIER
                    && CLAUSE_KEYWORDS.contains(token.text().toUpperCase(Locale.ROOT))) {
                inFromList = false;
                next();
                continue;
            }
            if (inFromList && token.isSymbol(",")) {
                next();
                collectTableReference(sources, visibleCtes);
                continue;
            }
            next();
        }
    }

    private void collectTableReference(List<Ast.TableRef> sources, Set<String> visibleCtes) {
        if (peek().isSymbol("(")) {
            if (isQueryStart(pos + 1)) {
                next();
                List<Ast.TableRef> innerSources = new ArrayList<>();
                scanQueryExpression(innerSources, new ArrayList<>(), visibleCtes, true);
                if (peek().isSymbol(")")) {
                    next();
                }
                sources.addAll(innerSources);
                consumeAlias();
                return;
            }
            skipBalanced();
            return;
        }
        Ast.ObjectName name = parseObjectName();
        if (name == null) {
            next();
            return;
        }
        String alias = consumeAlias();
        if (peek().isSymbol("(") && !isQueryStart(pos + 1)) {
            skipBalanced();
        }
        boolean cteReference = visibleCtes.contains(normalizedKey(name.object()));
        sources.add(new Ast.TableRef(name, alias, cteReference, name.location()));
    }

    private String consumeAlias() {
        if (peek().isKeyword("AS")) {
            next();
            Token alias = peek();
            if (isIdentifierLike(alias)) {
                next();
                return alias.normalizedText();
            }
            return null;
        }
        Token candidate = peek();
        if (isIdentifierLike(candidate)
                && !(candidate.type() == TokenType.IDENTIFIER
                && ALIAS_STOP_WORDS.contains(candidate.text().toUpperCase(Locale.ROOT)))) {
            next();
            return candidate.normalizedText();
        }
        return null;
    }

    // ------------------------------------------------------------------ helpers

    private Ast.ObjectName parseObjectName() {
        Token first = peek();
        if (!isIdentifierLike(first)) {
            return null;
        }
        next();
        String schema = null;
        String object = first.normalizedText();
        boolean quoted = first.type() == TokenType.QUOTED_IDENTIFIER;
        if (peek().isSymbol(".")) {
            next();
            Token second = peek();
            if (isIdentifierLike(second)) {
                next();
                schema = object;
                object = second.normalizedText();
                quoted = second.type() == TokenType.QUOTED_IDENTIFIER;
            }
        }
        if (peek().isSymbol("@")) {
            next();
            if (isIdentifierLike(peek())) {
                next();
            }
        }
        return new Ast.ObjectName(schema, object, quoted, loc(first));
    }

    private boolean isQueryStart(int index) {
        Token token = tokenAt(index);
        return token.isKeyword("SELECT") || token.isKeyword("WITH");
    }

    private boolean isStatementStart(Token token) {
        return token.type() == TokenType.IDENTIFIER
                && STATEMENT_KEYWORDS.contains(token.text().toUpperCase(Locale.ROOT));
    }

    private void skipUnsupportedStatement() {
        Token token = peek();
        if (token.type() == TokenType.IDENTIFIER
                && SKIPPED_DDL_KEYWORDS.contains(token.text().toUpperCase(Locale.ROOT))) {
            skipToStatementEnd();
            return;
        }
        skipToStatementEnd();
    }

    private void skipToStatementEnd() {
        while (!atEnd()) {
            Token token = peek();
            if (token.isSymbol(";")) {
                next();
                return;
            }
            if (token.isSymbol("/") && token.column() == 1) {
                return;
            }
            if (token.isSymbol("(")) {
                skipBalanced();
                continue;
            }
            next();
        }
    }

    private int consumeTerminator() {
        if (peek().isSymbol(";")) {
            return next().endOffset();
        }
        if (peek().isSymbol("/") && peek().column() == 1) {
            return previousEndOffset();
        }
        return previousEndOffset();
    }

    private void skipBalanced() {
        if (!peek().isSymbol("(")) {
            return;
        }
        int depth = 0;
        while (!atEnd()) {
            Token token = next();
            if (token.isSymbol("(")) {
                depth++;
            } else if (token.isSymbol(")")) {
                depth--;
                if (depth == 0) {
                    return;
                }
            }
        }
    }

    private String unquote(String literal) {
        if (literal.length() >= 2 && literal.startsWith("'") && literal.endsWith("'")) {
            return literal.substring(1, literal.length() - 1).replace("''", "'");
        }
        return literal;
    }

    private String normalizedKey(Token token) {
        return token.type() == TokenType.QUOTED_IDENTIFIER
                ? token.text()
                : token.text().toUpperCase(Locale.ROOT);
    }

    private String normalizedKey(String name) {
        return name == null ? "" : name.toUpperCase(Locale.ROOT);
    }

    private boolean isIdentifierLike(Token token) {
        return token.type() == TokenType.IDENTIFIER || token.type() == TokenType.QUOTED_IDENTIFIER;
    }

    private SourceLocation loc(Token token) {
        return SourceLocation.of(token.line(), token.column());
    }

    private void addIssue(String code, String message, SourceLocation location) {
        issues.add(new ParseIssue(code, message, location));
    }

    private Token peek() {
        return tokenAt(pos);
    }

    private Token tokenAt(int index) {
        if (index < 0 || index >= tokens.size()) {
            return tokens.isEmpty()
                    ? new Token(TokenType.EOF, "", 0, 0, 1, 1, 1, 1)
                    : tokens.get(tokens.size() - 1);
        }
        return tokens.get(index);
    }

    private Token next() {
        Token token = peek();
        if (pos < tokens.size() - 1) {
            pos++;
        } else {
            pos = tokens.size();
        }
        return token;
    }

    private boolean atEnd() {
        return pos >= tokens.size() || tokens.get(pos).type() == TokenType.EOF;
    }

    private int previousEndOffset() {
        int index = Math.max(0, Math.min(pos, tokens.size()) - 1);
        return tokens.isEmpty() ? 0 : tokens.get(index).endOffset();
    }

}
