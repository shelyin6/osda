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
| 解析 | 双引擎：内置递归下降解析器 + ANTLR Oracle PL/SQL 语法，统一产出 AST（默认混合策略） |
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

前端（需要 Node 与 pnpm；仅前端改动时需要）：

```bash
cd frontend
pnpm install
pnpm run build         # 产物写入 ../src/main/resources/static
```

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
| GET | `/api/lineage` | 上下游追溯，参数 `object`、`direction=UPSTREAM|DOWNSTREAM`、`maxDepth` |
| GET | `/api/export/relations.csv` | 导出关系 CSV（UTF-8 BOM，Excel 可直接打开） |
| GET | `/api/export/analysis.json` | 导出完整分析结果 JSON |

## 解析引擎

`osda.parser-engine` 支持三种取值，两个引擎都实现 `SqlAstParser` 并产出同一套 AST：

| 取值 | 行为 | 适用场景 |
| --- | --- | --- |
| `native` | 内置词法器 + 递归下降解析器，宽松容错，单文件毫秒级 | 追求速度、SQL 语法不规范 |
| `antlr` | 供应商 Oracle PL/SQL 语法（ANTLR 4.13.2），严格语法校验 | 需要语法体检、SQL 合法 |
| `hybrid`（默认） | 先用 ANTLR 解析；出现语法错误时自动回退内置解析器，并把语法问题作为告警保留 | 生产环境推荐 |

在 `application.yaml` 中切换：

```yaml
osda:
  parser-engine: hybrid
```

Golden 用例库与 `ParserComparisonTest` 会对三个引擎做同步对比：11 个 Golden 用例在三个引擎下
**语义完全一致**；真实文件方面，ANTLR 会因为源文件语法不合法而漏检，混合引擎不会漏检。

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
  引擎会漏检并报语法告警，建议使用默认的 `hybrid`。
