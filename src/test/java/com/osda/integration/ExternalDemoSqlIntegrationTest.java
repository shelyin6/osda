package com.osda.integration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.osda.analysis.model.DependencyRelation;
import com.osda.analysis.model.OperationType;
import com.osda.extraction.DependencyExtractor;
import com.osda.parser.OraclePlSqlParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * Regression test against the real, large demo PL/SQL files kept outside this repository.
 *
 * <p>The files are real business scripts and must never be committed. The test therefore locates
 * them on the local machine (system property {@code osda.external.sql.dir}, environment variable
 * {@code OSDA_EXTERNAL_SQL_DIR}, or {@code ~/Desktop/database_lineage_analysis}) and is skipped
 * when they are unavailable. Assertions only check structural invariants, never business names.
 */
class ExternalDemoSqlIntegrationTest {

    private final DependencyExtractor extractor = new DependencyExtractor(new OraclePlSqlParser());

    @Test
    void analyzesLargeRealWorldProcedureFiles() throws Exception {
        Path directory = externalDirectory();
        assumeTrue(Files.isDirectory(directory), "未找到外部演示 SQL 目录：" + directory);
        List<Path> files = List.of(directory.resolve("demo.sql"), directory.resolve("demo2.sql"));
        for (Path file : files) {
            assumeTrue(Files.isRegularFile(file), "缺少外部演示文件：" + file);
        }

        for (Path file : files) {
            String sql = Files.readString(file, StandardCharsets.UTF_8);
            long started = System.currentTimeMillis();
            DependencyExtractor.ExtractionResult result = extractor.extract(
                    file.getFileName().toString(), sql, 400);
            long elapsed = System.currentTimeMillis() - started;

            Map<String, Integer> byOperation = new TreeMap<>();
            for (DependencyRelation relation : result.relations()) {
                byOperation.merge(relation.operation().name(), 1, Integer::sum);
                assertTrue(relation.sourceLocation().line() >= 1, "依赖必须带行号");
                assertTrue(relation.sqlSnippet() != null && !relation.sqlSnippet().isBlank(),
                        "依赖必须带 SQL 片段证据");
                assertTrue(relation.sourceUnit() != null && !relation.sourceUnit().isBlank(),
                        "依赖必须带来源程序单元");
            }

            System.out.printf(Locale.ROOT,
                    "[external] %s: units=%d relations=%d warnings=%d elapsedMs=%d %s%n",
                    file.getFileName(), result.programUnitCount(), result.relations().size(),
                    result.warnings().size(), elapsed, byOperation);

            assertTrue(result.programUnitCount() >= 1, "应识别出存储过程单元");
            assertFalse(result.relations().isEmpty(), "真实存储过程必须解析出依赖关系");
            assertTrue(byOperation.containsKey(OperationType.INSERT.name()), "应识别出 INSERT 写入关系");
            assertTrue(byOperation.containsKey(OperationType.READ.name()), "应识别出 READ 读取关系");
            assertTrue(byOperation.containsKey(OperationType.DELETE.name()), "批处理脚本应识别出 DELETE 关系");
            assertTrue(elapsed < 30_000, "单个文件解析耗时不应超过 30 秒，实际 " + elapsed + " ms");
        }
    }

    private Path externalDirectory() {
        String configured = System.getProperty("osda.external.sql.dir");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("OSDA_EXTERNAL_SQL_DIR");
        }
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        return Path.of(System.getProperty("user.home"), "Desktop", "database_lineage_analysis");
    }
}
