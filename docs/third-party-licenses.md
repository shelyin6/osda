# 第三方依赖与许可证清单

一期运行时不访问外网、不调用云端解析服务。下列组件随构建产物交付，版本由 `pom.xml` 与
`frontend/pnpm-lock.yaml` 固定。

## 运行时依赖（打进可执行 Jar）

| 组件 | 版本 | 许可证 |
| --- | --- | --- |
| Spring Boot | 3.5.7 | Apache-2.0 |
| Spring Framework | 6.2.12 | Apache-2.0 |
| Apache Tomcat (embed) | 10.1.48 | Apache-2.0 |
| Jackson databind / core / annotations / jsr310 / jdk8 / parameter-names | 2.19.2 | Apache-2.0 |
| Hibernate Validator | 8.0.3.Final | Apache-2.0 |
| Jakarta Validation API | 3.0.2 | Apache-2.0 |
| Jakarta Annotation API | 2.1.1 | EPL-2.0 或 GPL-2.0-with-classpath-exception |
| SLF4J API | 2.0.17 | MIT |
| Logback classic / core | 1.5.20 | EPL-1.0 或 LGPL-2.1 |
| Log4j API / log4j-to-slf4j | 2.24.3 | Apache-2.0 |
| Micrometer observation / commons | 1.15.5 | Apache-2.0 |
| SnakeYAML | 2.4 | Apache-2.0 |
| Classmate | 1.7.1 | Apache-2.0 |
| JBoss Logging | 3.6.1.Final | Apache-2.0 |
| **ANTLR 4 Runtime** | **4.13.2** | **BSD-3-Clause**（仅 `-Pwith-antlr` 构建包含） |

## 随仓库分发的第三方源码

| 资产 | 位置 | 许可证 | 说明 |
| --- | --- | --- | --- |
| Oracle PL/SQL 语法 | `src/antlr/grammar/com/osda/parser/antlr/PlSqlLexer.g4`、`PlSqlParser.g4` | Apache-2.0 | 取自 antlr/grammars-v4（`sql/plsql`），保留原始版权头与许可证声明 |
| 语法所需基类 | `src/antlr/java/com/osda/parser/antlr/PlSqlLexerBase.java`、`PlSqlParserBase.java` | Apache-2.0 | 同上，仅补充 `package` 声明 |

> 语法文件版权归 Alexandre Porcelli、Ivan Kochurkin、Mark Adams 等作者所有，按 Apache-2.0 授权使用；
> 发布离线包时需连同许可证文本一并交付。

## 前端依赖（构建期，产物随 Jar 内置）

| 组件 | 版本 | 许可证 |
| --- | --- | --- |
| React / React DOM | 18.3.1 | MIT |
| TypeScript | 5.6.3 | Apache-2.0 |
| Vite | 5.4.11 | MIT |
| @vitejs/plugin-react | 4.3.4 | MIT |
| esbuild | 0.21.3 | MIT |
| Rollup（Vite 内置） | 4.x | MIT |

## 测试依赖（不进入发布包）

JUnit Jupiter（EPL-2.0）、Mockito（MIT）、AssertJ（Apache-2.0）、Spring Boot Test（Apache-2.0）。

## 离线交付注意事项

1. 内网发布需同时准备：可执行 Jar、`application.yml`（如需改配置）、本清单、许可证原文、版本号与校验和。
2. Maven 依赖需提前通过 `mvn dependency:go-offline` 或内网 Nexus 镜像落盘，目标机器不联网。
3. 前端产物已提交在 `src/main/resources/static`，目标机器无需 Node 与 npm。
