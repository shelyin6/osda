package com.osda.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.osda.analysis.model.DependencyRelation;
import com.osda.extraction.DependencyExtractor;
import com.osda.parser.antlr.AntlrSqlAstParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Compares the three parser engines.
 *
 * <p>Native = built-in recursive descent, ANTLR = vendored Oracle PL/SQL grammar, Hybrid = grammar
 * validation with lenient fallback. Golden cases must be semantically identical across all three;
 * for real world files the ANTLR engine may lose dependencies when the input SQL is not valid, but
 * the Hybrid engine must never lose one.
 */
class ParserComparisonTest {

    private static final Path GOLDEN_ROOT = Path.of("src", "test", "resources", "golden");

    private static final DependencyExtractor NATIVE =
            new DependencyExtractor(new OraclePlSqlParser());
    private static final DependencyExtractor ANTLR =
            new DependencyExtractor(new AntlrSqlAstParser());
    private static final DependencyExtractor HYBRID =
            new DependencyExtractor(new HybridSqlAstParser());

    static Stream<String> goldenCases() throws IOException {
        try (Stream<Path> paths = Files.list(GOLDEN_ROOT)) {
            return paths.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList()
                    .stream();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("goldenCases")
    void goldenCasesAreIdenticalAcrossEngines(String caseName) throws IOException {
        String sql = Files.readString(GOLDEN_ROOT.resolve(caseName).resolve("input.sql"), StandardCharsets.UTF_8);
        Result nativeResult = extract(NATIVE, caseName, sql);
        Result antlrResult = extract(ANTLR, caseName, sql);
        Result hybridResult = extract(HYBRID, caseName, sql);
        report(caseName, nativeResult, antlrResult, hybridResult);

        assertFalse(antlrResult.relations().isEmpty(), "ANTLR 引擎必须解析出依赖：" + caseName);
        assertEquals(nativeResult.semantic(), antlrResult.semantic(),
                "Golden 用例在内置与 ANTLR 引擎之间必须语义一致：" + caseName);
        assertEquals(nativeResult.semantic(), hybridResult.semantic(),
                "Golden 用例在混合引擎与内置引擎之间必须语义一致：" + caseName);
    }

    @Test
    void realWorldFilesKeepFullCoverageInHybridEngine() throws Exception {
        Path directory = externalDirectory();
        if (!Files.isDirectory(directory)) {
            return;
        }
        for (String fileName : List.of("demo.sql", "demo2.sql")) {
            Path file = directory.resolve(fileName);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            String sql = Files.readString(file, StandardCharsets.UTF_8);
            Result nativeResult = extract(NATIVE, fileName, sql);
            Result antlrResult = extract(ANTLR, fileName, sql);
            Result hybridResult = extract(HYBRID, fileName, sql);
            report(fileName, nativeResult, antlrResult, hybridResult);

            assertFalse(nativeResult.relations().isEmpty(), "内置引擎必须解析出依赖：" + fileName);
            assertTrue(missing(hybridResult.semantic(), nativeResult.semantic()).isEmpty(),
                    "混合引擎不得漏掉内置引擎已识别的依赖：" + fileName);
        }
    }

    private Result extract(DependencyExtractor extractor, String name, String sql) {
        long started = System.nanoTime();
        DependencyExtractor.ExtractionResult result = extractor.extract(name, sql, 400);
        long elapsedMillis = Math.max(1L, (System.nanoTime() - started) / 1_000_000L);
        List<String> samples = result.warnings().stream()
                .limit(3)
                .map(warning -> warning.code() + "@L" + warning.location().line() + " "
                        + trim(warning.message()))
                .toList();
        return new Result(result.relations(), result.warnings().size(), samples, elapsedMillis);
    }

    private String trim(String message) {
        if (message == null) {
            return "";
        }
        String single = message.replaceAll("\\s+", " ");
        return single.length() > 110 ? single.substring(0, 110) + "..." : single;
    }

    private void report(String name, Result nativeResult, Result antlrResult, Result hybridResult) {
        Set<String> missingInAntlr = missing(nativeResult.semantic(), antlrResult.semantic());
        Set<String> antlrOnly = missing(antlrResult.semantic(), nativeResult.semantic());
        Set<String> hybridMissing = missing(hybridResult.semantic(), nativeResult.semantic());

        System.out.printf(
                "[parser-compare] %s: native=%d antlr=%d hybrid=%d "
                        + "antlr(native-only=%d, antlr-only=%d) hybrid-missing=%d "
                        + "warnings(native=%d, antlr=%d, hybrid=%d) elapsedMs(native=%d, antlr=%d, hybrid=%d)%n",
                name,
                nativeResult.relations().size(),
                antlrResult.relations().size(),
                hybridResult.relations().size(),
                missingInAntlr.size(),
                antlrOnly.size(),
                hybridMissing.size(),
                nativeResult.warningCount(),
                antlrResult.warningCount(),
                hybridResult.warningCount(),
                nativeResult.elapsedMillis(),
                antlrResult.elapsedMillis(),
                hybridResult.elapsedMillis());
        missingInAntlr.forEach(key -> System.out.println("    native-only(missing-in-antlr): " + key));
        antlrOnly.forEach(key -> System.out.println("    antlr-only: " + key));
        antlrResult.warningSamples().forEach(sample -> System.out.println("    antlr-warning: " + sample));
    }

    /** Keys present in {@code left} but absent from {@code right}. */
    private Set<String> missing(Set<String> left, Set<String> right) {
        Set<String> difference = new TreeSet<>(left);
        difference.removeAll(right);
        return difference;
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

    private record Result(
            List<DependencyRelation> relations,
            int warningCount,
            List<String> warningSamples,
            long elapsedMillis
    ) {

        Set<String> semantic() {
            Set<String> keys = new LinkedHashSet<>();
            for (DependencyRelation relation : relations) {
                keys.add(relation.sourceUnit() + "|" + relation.operation() + "|" + relation.targetKey());
            }
            return keys;
        }
    }
}
