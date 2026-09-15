# 二期方案：从 KingbaseES 按存储过程名查询

> 一期边界说明：`AGENTS.md` 第 2、9 条明确「不连接数据库、不读取生产元数据、不执行任何 SQL」。
> 本文档只做二期方案设计，**当前代码不会连接任何数据库**；二期需独立立项、独立权限审批与验收。

## 1. 目标

把输入方式从「上传/粘贴 SQL 文件」扩展为「输入存储过程名（或模糊匹配）」，直接从 KingbaseES
读取程序单元源码，其余环节（解析、依赖抽取、去重汇总、血缘、导出）完全复用。

## 2. 实现思路：抽象"源码来源"，其余不动

现有流水线只有入口处依赖文件：

```text
SourceInput(fileName, content) -> SqlDependencyAnalyzer -> 关系/告警 -> 索引 -> REST/导出
```

二期只需新增一个来源实现，把数据库读到的源码包装成同样的 `SourceInput`：

```java
public interface ProcedureSourceProvider {
    String id();                                              // file / kingbase
    List<ProcedureRef> list(String ownerPattern, String namePattern);
    ProcedureSource load(ProcedureRef ref);
}

public record ProcedureRef(String owner, String name, String objectType, Instant lastDdlTime) {}

public record ProcedureSource(
        String sourceFile,   // 例如 kingbase://10.0.0.9:54321/ALMP.PROC_XXX，作为证据来源标识
        String text,         // 完整源码文本
        int firstLine,       // 源码起始行号（用于行号对齐）
        String objectType    // PROCEDURE / FUNCTION / PACKAGE / PACKAGE BODY
) {}
```

| 实现 | 阶段 | 说明 |
| --- | --- | --- |
| `FileProcedureSourceProvider` | 一期已有 | 上传文件、目录扫描、粘贴文本 |
| `KingBaseProcedureSourceProvider` | 二期新增 | 通过 JDBC 只读连接读取系统视图 |

解析层、抽取层、`AnalysisService`、血缘、导出全部无需改动——这是当前分层的直接收益。

## 3. 从 KingbaseES 取源码的两条路径

KingbaseES 是 PostgreSQL 兼容内核，并提供 Oracle 兼容模式。按现场情况二选一：

**路径 A：PostgreSQL 系统目录（通用）**

```sql
SELECT n.nspname AS owner,
       p.proname AS name,
       l.lanname AS language,
       pg_get_functiondef(p.oid) AS ddl
FROM pg_proc p
JOIN pg_namespace n ON n.oid = p.pronamespace
JOIN pg_language  l ON l.oid = p.prolang
WHERE n.nspname = ?          -- 例如 'ALMP'
  AND p.proname = ?          -- 例如 'PROC_DM_O_TARGET'
ORDER BY p.proname;
```

- `pg_get_functiondef(oid)` 返回完整 `CREATE` 语句，最适合直接喂给现有解析器；
- 若需要精确行号，可改用 `p.prosrc`（函数体文本），源码起始行按 `CREATE` 头部行数偏移。

**路径 B：Oracle 兼容视图（若现场开启 Oracle 兼容模式）**

```sql
SELECT owner, name, type, line, text
FROM all_source
WHERE owner = ?
  AND name  = ?
  AND type IN ('PROCEDURE', 'FUNCTION', 'PACKAGE', 'PACKAGE BODY', 'TRIGGER')
ORDER BY type, line;
```

这条更贴合现有模型：`type` 直接映射 `SourceType`，`line` 就是证据行号，按行拼接即可还原源码。
注意 `all_source` 只包含当前账号可见对象；`dba_source` 需要高权限，不建议在生产使用。

**模糊查询**（供界面下拉选择）：

```sql
SELECT owner, name, type FROM all_objects
WHERE object_type IN ('PROCEDURE','FUNCTION','PACKAGE','PACKAGE BODY')
  AND owner LIKE ? AND name LIKE ?
ORDER BY owner, name
FETCH FIRST 200 ROWS ONLY;
```

## 4. 权限与安全（硬性要求）

1. **专用只读账号**：仅 `CONNECT` + 系统视图 `SELECT`（或 `pg_get_functiondef` 执行权限），
   绝不在任何业务表上授予 DML/DDL；连接后显式 `SET TRANSACTION READ ONLY`。
2. **默认关闭**：`osda.kingbase.enabled=false`，必须显式开启并填写连接信息才会发起连接。
3. **凭据管理**：口令只从环境变量或外部配置文件读取，不入库、不入日志、不进 Git；
   日志中连接串需脱敏（只留 host/port/db，隐去账号口令）。
4. **资源保护**：连接池上限 2~4，`connectTimeout` 5 秒、查询超时 10~15 秒、单次最大返回行数限制。
5. **审计**：记录"谁、何时、查询了哪个对象"，不记录源码全文（对齐 `AGENTS.md` 第 7 条）。
6. **网络**：内网白名单 + TLS（如现场支持）；禁止跨网段直连生产库。

## 5. 配置示例

```yaml
osda:
  kingbase:
    enabled: false
    url: jdbc:kingbase8://10.0.0.9:54321/DBNAME
    driver-class-name: com.kingbase8.Driver
    username: osda_ro
    password: ${OSDA_KINGBASE_PASSWORD}   # 只从环境变量注入
    default-schema: ALMP
    connect-timeout-seconds: 5
    query-timeout-seconds: 15
    max-rows: 5000
```

依赖：KingbaseES JDBC 驱动（`kingbase8-*.jar`）需随离线包交付或推入内网 Nexus，
并在 `docs/third-party-licenses.md` 登记版本与许可证。

## 6. 交互流程

1. 界面新增「数据库查询」入口，仅在 `enabled=true` 时显示。
2. 输入 `OWNER.对象名`（支持 `%` 模糊）→ 返回候选列表（对象名 + 类型 + 最后 DDL 时间）。
3. 选择后拉取源码 → 走现有 `AnalysisService.analyze(...)` → 复用现有结果页
   （表级汇总去重、依赖明细、血缘、CSV/JSON 导出）。
4. 证据列显示 `kingbase://<host>:<port>/<OWNER>.<OBJECT>`，行号与库内源码行号一致，
   便于 DBA 回到开发工具核对。
5. 可选「整库/整 schema 扫描」：批量拉取 → 形成内存快照 → 支持按对象/按过程名检索。

## 7. 增量刷新与缓存

- 解析结果缓存到本地 SQLite（对齐 `AGENTS.md` 第 7 条单机 SQLite 约定）：
  `object_key, owner, name, type, last_ddl_time, source_hash, parser_version, analyzed_at`。
- 失效条件：`last_ddl_time` 变化、源码哈希变化、`parser_version` 变化，三者任一命中即重解析。
- 批量扫描时只重解析变化的程序单元，未变化对象直接复用缓存关系，降低对生产库的压力。
- 缓存文件与连接配置由部署方指定路径，纳管进备份与清理策略。

## 8. 分阶段落地

| 阶段 | 内容 | 验收要点 |
| --- | --- | --- |
| 阶段 1 | 单对象按名查询 → 拉源码 → 解析 → 展示 | 结果与文件模式一致；只读账号；日志脱敏 |
| 阶段 2 | schema 级批量扫描 + SQLite 缓存 + 增量刷新 | 扫描耗时、缓存命中率、重复解析次数 |
| 阶段 3 | 与批次/调度信息关联（超出静态分析范围） | 需另行立项 |

## 9. 风险与验证要点

- **方言差异**：KingbaseES 的 PL/pgSQL 与 Oracle PL/SQL 存在语法差异（Oracle 兼容模式可缓解）。
  现有解析器面向 Oracle 方言，二期上线前必须用真实 Kingbase 包体跑 Golden 用例，
  确认静态表引用识别率仍不低于 95%（`AGENTS.md` 第 8 条）。
- **加密对象**：若存储过程为 `WRAP` 加密，`prosrc` 不可读，需要事先评估可读覆盖率
  （对比 `all_objects` 与 `all_source` 的数量差）。
- **大包体**：几十万行的包体需实测解析耗时与内存（当前内置解析器为毫秒级/千行量级，需压测）。
- **字符集**：库内可能是 GBK/UTF-8，JDBC 需显式指定编码，避免中文注释乱码。
- **权限审批**：生产库连接受限场景多，建议先在测试库或只读备库验证，再走审批流程。

## 10. 与现有代码的衔接点

新增（二期）：

- `com.osda.source.ProcedureSourceProvider` 及其 `KingBaseProcedureSourceProvider` 实现
- `com.osda.web.ProcedureQueryController`（候选列表、按名加载、触发分析）
- `com.osda.config.KingBaseProperties` 与驱动依赖

不改动：`parser`、`extraction`、`lineage`、`export`、`ObjectSummaryService`、前端结果页
（仅新增"数据库查询"入口）。
