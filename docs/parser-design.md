# 解析器设计说明

## 1. 为什么要替换掉参考项目的解析实现

参考项目（`sql-metadata-viewer`）用 20 余处正则表达式识别对象与 DML，例如
`TARGET_TABLE_PATTERN`、`SOURCE_TABLE_PATTERN`、`CALL_PATTERN`，还通过回看 24 个字符判断
`DELETE FROM`。这条路线在真实 PL/SQL 上会产生系统性误报与漏报，且与 `AGENTS.md` 第 4 条
「解析核心必须基于语法树（AST），不得以正则表达式作为唯一或主要实现」直接冲突。

## 2. 两套解析引擎与混合策略

`AGENTS.md` 推荐 ANTLR Oracle PL/SQL Grammar。项目现在同时具备两套语法树生产者，二者都实现
`SqlAstParser`，产出同一个 `Ast`：

| 引擎 | 配置值 | 实现 | 特点 |
| --- | --- | --- | --- |
| 内置解析器 | `native` | `OraclePlSqlParser` | 自研词法器 + 递归下降；宽松、容错、速度最快（毫秒级） |
| ANTLR 引擎 | `antlr` | `AntlrSqlAstParser` + 供应商语法 | 严格语法校验，语法树精确；对非法 SQL 会丢失错误恢复区域内的依赖 |
| 混合引擎（默认） | `hybrid` | `HybridSqlAstParser` | 先用 ANTLR 校验与解析；一旦出现语法错误，自动回退内置解析器并保留语法告警 |

历史背景：内置解析器最初是因为本机 `github.com` 不可达、无法取得语法文件而实现的。代理开通后已按
`AGENTS.md` 的建议补充 ANTLR 实现，语法与基类以 Apache-2.0 许可随仓库分发（见
`docs/third-party-licenses.md`）。

### 为什么默认用混合引擎

真实交付的 SQL 并不总是合法 Oracle 语法。用 `database_lineage_analysis` 目录下的真实存储过程实测：

- `demo.sql`：ANTLR 报出 23 处语法问题（变量声明缺少分号、使用 `string`/`int` 等非 Oracle 类型）；
- `demo2.sql`：第 123 行把 `AND` 误写成 `ASIN`，ANTLR 在错误恢复中丢掉了 `FROM ads.bi_ph_s75_corp_loan_dtl`
  与 `INNER JOIN SUM.pu_org` 两条真实依赖，内置解析器仍然识别出来。

严格语法树在这种情况下会**静默漏检**，与 `AGENTS.md` 第 8 条「常规 DML 不得无告警漏检」冲突；
混合引擎因此成为默认：既不放弃语法校验，也不放弃召回率。

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

该路径已完成：

1. 语法文件与基类已纳入仓库（Apache-2.0，保留版权头）。
2. `pom.xml` 已加入 `antlr4-maven-plugin` 与 `antlr4-runtime` 4.13.2。
3. `AntlrSqlAstParser` 把 ANTLR 语法树映射为既有 `Ast`：程序单元取 `create_procedure_body` /
   `create_function_body` / `create_package_body` 与嵌套 `procedure_body`；语句取 `select_statement` /
   `insert_statement` / `update_statement` / `delete_statement` / `merge_statement` /
   `execute_immediate`；表引用取 `from_clause` 内的 `tableview_name`；CTE 名称取
   `subquery_factoring_clause`。另外单独处理表达式内子查询（如 `RETURN (SELECT ...)`，该结构在
   ANTLR 树中不是 `select_statement`），否则会漏掉这类依赖。
4. 引擎通过 `osda.parser-engine` 选择，抽取层、服务层、接口层与 Golden 用例不随引擎变化。

后续升级语法版本时：替换 `.g4` 与基类，重新生成后运行 `ParserComparisonTest` 与 Golden 用例即可。
