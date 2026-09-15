# 解析器设计说明

## 1. 为什么要替换掉参考项目的解析实现

参考项目（`sql-metadata-viewer`）用 20 余处正则表达式识别对象与 DML，例如
`TARGET_TABLE_PATTERN`、`SOURCE_TABLE_PATTERN`、`CALL_PATTERN`，还通过回看 24 个字符判断
`DELETE FROM`。这条路线在真实 PL/SQL 上会产生系统性误报与漏报，且与 `AGENTS.md` 第 4 条
「解析核心必须基于语法树（AST），不得以正则表达式作为唯一或主要实现」直接冲突。

## 2. 为什么当前不是 ANTLR

`AGENTS.md` 推荐 ANTLR Oracle PL/SQL Grammar，但该语法文件（grammars-v4 的
`PlSqlLexer.g4` / `PlSqlParser.g4`）需要从 GitHub 获取，而本机网络对 `github.com` 的连接会被重置，
离线环境下无法取得语法资产。因此这一版采用**自研词法器 + 递归下降解析器**：

- 它同样产出语法树（`Ast`），决策全部基于词法单元，不使用正则判断依赖；
- 不引入任何新增运行时依赖，符合离线交付约束；
- 解析器实现被隔离在 `SqlAstParser` 接口之后。

## 3. 解析流水线

```text
源码文本
  -> Lexer          词法单元（注释、字符串、引号标识符、数字、符号）并保留行/列/偏移
  -> OraclePlSqlParser  程序单元边界 + 语句扫描 -> Ast
  -> DependencyExtractor  遍历 Ast -> DependencyRelation + ParseWarning
  -> AnalysisService / LineageService / ExportService
```

关键点：

1. **注释与字符串不可能产生依赖**：词法器把注释直接丢弃、把字符串字面量压成单个 token，
   因此字符串里的 `FROM`/`JOIN` 永远不会进入语法分析。
2. **程序单元边界**：`BEGIN`/`IF`/`LOOP`/`CASE` 压栈，`END [IF|LOOP|CASE|名称];` 出栈，
   栈空即单元结束；包体内的嵌套子程序递归解析并继承包的 schema。
3. **语句扫描**：`FROM`/`JOIN`/`USING` 后的对象名进入表引用；`(` 后紧跟 `SELECT`/`WITH` 时递归
   解析子查询；其余括号整体跳过，保证语句终止符 `;` 的判定不受影响。
4. **CTE 与别名**：`WITH` 定义的名称进入作用域集合，`FROM` 引用到它们时标记 `cteReference`
   并在抽取阶段过滤；表别名词表与保留字列表共同避免把别名当表名。
5. **标识符规范化**：未加引号标识符统一大写；双引号标识符保留原始语义，不参与忽略名单匹配。

## 4. 动态 SQL 处理

| 情况 | 处理方式 | 置信度 |
| --- | --- | --- |
| `EXECUTE IMMEDIATE '完整常量 SQL'` | 递归解析该常量 SQL，位置与片段仍指回 `EXECUTE IMMEDIATE` | `MEDIUM` |
| 常量字符串之间用 `\|\|` 拼接 | 拼接后按常量处理 | `MEDIUM` |
| 参数包含变量、函数或表达式 | 不产出依赖，生成 `OSDA-DYNAMIC-001` 告警 | 不适用 |
| `EXECUTE` 后不是 `IMMEDIATE` | 告警 `OSDA-PARSE-008` | 不适用 |

规则对应 `AGENTS.md` 第 5 条：常量动态 SQL 置信度不得高于 `MEDIUM`；无法提取内容时保存代码位置与
「无法静态解析」的告警，且不声称依赖完整。

## 5. 关系语义

- `READ`：`SELECT`（含 `SELECT ... INTO`、游标定义中的查询）读取的表。
- `INSERT` / `UPDATE` / `DELETE` / `MERGE`：对应 DML 的目标对象。
- `MERGE`：目标对象记 `MERGE` 写入，`USING` 来源表记 `READ`。
- `READ_WRITE`：同一程序单元对同一对象既有 `READ` 又有写入关系时，追加一条派生关系并附说明。
- `UNKNOWN`：预留给无法静态确定的场景；当前版本对无法解析的动态 SQL 只输出告警，不伪造对象名。

每条关系均携带 `source_unit`、`source_type`、`source_file`、`source_location`（行、列、结束位置）、
`target_schema`、`target_object`、`target_type`、`operation`、`confidence`、`sql_snippet`、
`is_dynamic_sql`、`warning` 字段。

## 6. 已知边界与后续计划

1. `TRUNCATE TABLE`、`OPEN ... FOR`、`DBMS_SQL.PARSE` 暂不产出关系（避免臆断，后续按 AST 支持补充）。
2. 包规范（`PACKAGE`，非 `BODY`）只识别单元边界，不产出依赖；依赖来自包体实现。
3. 语句扫描是「浅语法」：只解析到表引用层级，不做完整表达式与列级血缘；列级血缘与影响分析属于后续迭代。
4. SQL*Plus 专属指令（`SET`、`SPOOL` 等）按语句整体跳过，不产出告警，避免噪声。
5. Oracle 伪表 `DUAL`（含 `SYSTEM.DUAL`）不产出依赖关系；忽略名单集中在
   `DependencyExtractor.IGNORED_OBJECTS`，后续可配置化。

## 7. 切换到 ANTLR 的路径

1. 取得合法的 Oracle PL/SQL 语法文件并纳入仓库（含许可证清单）。
2. 在 `pom.xml` 增加 `antlr4-maven-plugin` 与 `antlr4-runtime` 依赖。
3. 新增 `AntlrSqlAstParser implements SqlAstParser`，把 ANTLR 语法树映射为现有 `Ast` 节点。
4. 在 `DependencyExtractor` 中切换实现类即可，抽取层、服务层、接口层与 Golden 用例无需改动。

Golden 用例库（`src/test/resources/golden/`）是这条迁移路径的安全网：替换解析器后必须全部保持通过。
