# OSDA：Oracle 存储过程依赖分析工具

面向 DBA 的**离线性静态分析工具**。输入 Oracle SQL/PLSQL 文件或代码文本，输出存储过程、函数、包体与数据库对象之间的
表级读写依赖，并提供代码行号与 SQL 片段证据，用于跑批失败或目标表数据异常时的影响范围定位。

一期只做静态分析与证据展示：**不连接数据库、不读取生产元数据、不执行任何 SQL、不自动重跑**。

## 项目规则

完整范围、依赖标准、解析原则、动态 SQL 规则、测试要求与安全边界见仓库根目录的 `AGENTS.md`。

## 技术栈

| 层次 | 选型 |
| --- | --- |
| 后端 | Java 21、Spring Boot 3.5.7、Maven |
| 解析 | 内置递归下降解析器（默认）+ 可选 ANTLR Oracle PL/SQL 语法，统一产出 AST |
| 前端 | React 18 + TypeScript 5 + Vite 5，构建产物内置于 jar |
| 部署 | 单机胖 Jar，浏览器界面不依赖 CDN，可完全离线运行 |

## 目录结构

```text
src/main/java/com/osda/
├── analysis/model/       依赖关系、告警、置信度等领域模型
├── analysis/service/     单文件分析与多文件聚合
├── config/               配置属性
├── export/               CSV / JSON 导出
├── extraction/           AST -> DependencyRelation 抽取
├── lineage/              上下游追溯（限深、环检测）
├── parser/               词法器、AST、递归下降解析器
└── web/                  REST API
src/test/resources/golden/  Golden SQL 用例库（每个用例含 input.sql 与 expected.json）
frontend/                   React + TypeScript 源码（产物输出到 src/main/resources/static）
docs/parser-design.md       解析器设计、替换方案与已知边界
```

## 构建与运行

后端：

```bash
mvn test              # 运行 Golden 用例与单元测试
mvn -DskipTests package
java -jar target/osda-0.1.0-SNAPSHOT.jar
```

两种构建形态：

| 命令 | 产物 | 说明 |
| --- | --- | --- |
| `mvn -DskipTests package` | 约 21.7 MB | **发布默认**：只含内置解析器，不含 ANTLR 语法与运行时 |
| `mvn -Pwith-antlr -DskipTests package` | 约 24.9 MB | 额外包含 ANTLR 引擎与三引擎对比测试，用于语法体检 |

前端（需要 Node 与 pnpm；仅前端改动时需要）：

```bash
cd frontend
pnpm install
pnpm run build         # 产物写入 ../src/main/resources/static
```

pnpm 版本注意事项：

- pnpm 12 起，安装脚本白名单的配置项由 `onlyBuiltDependencies` 改为 `allowBuilds` 映射。
  仓库的 `frontend/pnpm-workspace.yaml` **同时保留两种写法**，pnpm 10/11/12 都能直接 `pnpm install`。
- 若遇到 `ERR_PNPM_IGNORED_BUILDS: esbuild@...`，说明白名单配置缺失或格式不对；
  最省事的做法是在 `frontend` 目录执行 `pnpm approve-builds` 并按提示勾选 `esbuild`，它会自动写回正确配置。
- `frontend/.npmrc` 已把 registry 指向 npmmirror 镜像，并设置 `node-linker=hoisted`
  以避免 Windows 下的符号链接权限问题。

内网部署：把 `target/osda-0.1.0-SNAPSHOT.jar` 拷贝到目标机器，`java -jar` 启动后浏览器访问
`http://localhost:8080`。界面资源已打进 jar，不需要 node、不需要外网。

## API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/health` | 健康检查与解析器版本 |
| POST | `/api/analysis/files` | 批量上传 `.sql` 文件（multipart，字段名 `files`） |
| POST | `/api/analysis/text` | 分析粘贴代码，请求体 `{"name":"x.sql","content":"..."}` |
| GET | `/api/analysis/current` | 最近一次分析结果 |
| GET | `/api/relations` | 关系列表，支持 `operation`、`confidence`、`file`、`target`、`keyword` 过滤 |
| GET | `/api/objects` | **去重后的表级结果**：每个目标对象一行，含操作集合、读写次数、涉及单元与文件、最保守置信度 |
| GET | `/api/lineage` | 上下游追溯，参数 `object`、`direction=UPSTREAM|DOWNSTREAM`、`maxDepth` |
| GET | `/api/export/relations.csv` | 导出关系 CSV（UTF-8 BOM，Excel 可直接打开） |
| GET | `/api/export/objects.csv` | 导出去重后的表级结果 CSV |
| GET | `/api/export/analysis.json` | 导出完整分析结果 JSON |

界面默认进入「表级汇总（去重）」页签：同一对象的多次出现合并为一行，展示操作集合（READ/INSERT/…）、
关系数、读/写次数、涉及程序单元与文件数、置信度与说明（例如「该对象既有读取也有写入」）。
点击任意一行会跳到「依赖关系」页并按该对象过滤，便于直接查看原始 SQL 片段与行号证据。
实测两个真实文件：12 条关系去重后为 8 个对象，`SUM.PU_ORG` 的 3 次出现合并为一行。

## 解析引擎

`osda.parser-engine` 支持三种取值，两个引擎都实现 `SqlAstParser` 并产出同一套 AST：

| 取值 | 行为 | 适用场景 |
| --- | --- | --- |
| `native`（默认） | 内置词法器 + 递归下降解析器，宽松容错，单文件 1~2ms | 生产默认；SQL 语法不规范、方言漂移、追求速度 |
| `antlr` | 供应商 Oracle PL/SQL 语法（ANTLR 4.13.2），严格语法校验 | 语法体检；能发现拼写与结构错误 |
| `hybrid` | 先用 ANTLR 解析；出现语法错误时自动回退内置解析器，并把语法问题作为告警保留 | 既要语法校验又要保证不漏检 |

`antlr` 与 `hybrid` 只存在于 `-Pwith-antlr` 构建中。若在精简包里配置了这两个值，服务会**自动回退**
到内置解析器并在日志中给出警告，`/api/health` 的 `parserEngineImpl` 字段会显示实际生效的实现类。

在 `application.yaml` 中切换：

```yaml
osda:
  parser-engine: native
```

实测结论（`ParserComparisonTest` + 两个真实脱敏存储过程）：11 个 Golden 用例、`demo.sql`、
`demo2.sql` 在三个引擎下依赖关系**完全一致**，无漏检也无多出；耗时方面内置解析器 1~2ms/文件，
ANTLR 热身后 30~60ms/文件且首次解析有约 5 秒 JIT 冷启动。因此默认使用 `native`，ANTLR 保留为
可选的语法体检能力（正是它发现了 `demo2.sql` 第 123 行 `ASIN` 拼写错误）。

## 测试

Golden 用例库位于 `src/test/resources/golden/`，覆盖普通 DML、JOIN、别名、CTE、子查询、嵌套 SQL、
MERGE、过程/函数/包体、注释与字符串干扰、动态 SQL、同一对象读写。修改任何解析规则后必须运行：

```bash
mvn test
```

`ParserComparisonTest` 会对内置、ANTLR、混合三个引擎做同源对比，输出每条差异（漏检、多出、告警、
耗时）并断言：Golden 用例三引擎语义一致，混合引擎在真实文件上不漏检。

真实大体量回归：`ExternalDemoSqlIntegrationTest` 会读取仓库外的真实存储过程文件并校验结构不变量
（不使用其中的业务名称）。定位顺序为系统属性 `osda.external.sql.dir`、环境变量
`OSDA_EXTERNAL_SQL_DIR`、默认 `~/Desktop/database_lineage_analysis`。这些文件属于业务代码，
**不得提交到仓库**；文件不存在时该测试自动跳过。

## 与参考项目的关系

本项目的工程形态参考了本地已有的 SQL 元数据查看器项目（`sql-metadata-viewer`，MIT 许可），
借鉴了离线胖 Jar 部署、静态前端内置、证据行号跳转、限深血缘遍历等做法；解析核心则完全重写为
AST 方案，未复用其正则实现。具体取舍与替换方案见 `docs/parser-design.md`。

## 已知边界

- 仅支持 Oracle 方言；视图与同义词不展开到基础表。
- `TRUNCATE TABLE`、`OPEN ... FOR` 动态游标、`DBMS_SQL` 调用当前不产出依赖关系。
- 动态 SQL 仅在参数为常量字符串时解析，且置信度不超过 `MEDIUM`；变量拼接只产生告警。
- 列级血缘、影响分析图谱属于后续迭代范围。
- 真实交付的 SQL 常存在语法问题（例如本次实测的 `ASIN` 误写、变量声明缺分号），此时 `antlr`
  引擎会报语法告警并可能在错误恢复中漏检；默认的 `native` 引擎不受影响，需要语法体检时再用
  `antlr` 或 `hybrid`。

二期若要从 KingbaseES 直接按存储过程名查询（而不是上传文件），实现方案见
[docs/phase2-database-source.md](docs/phase2-database-source.md)：抽象"源码来源"、只读账号、
增量缓存与分阶段落地建议均已给出。一期代码不会连接任何数据库。
