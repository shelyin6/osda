package com.osda.golden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.osda.analysis.model.DependencyRelation;
import com.osda.extraction.DependencyExtractor;
import com.osda.parser.OraclePlSqlParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Golden SQL corpus.
 *
 * <p>Every case directory under {@code src/test/resources/golden} contains {@code input.sql} and
 * {@code expected.json}. Any change to the parsing rules must keep these cases green; this is the
 * regression net required by the project rules.
 */
class GoldenSqlTest {

    private static final Path GOLDEN_ROOT = Path.of("src", "test", "resources", "golden");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DependencyExtractor extractor = new DependencyExtractor(new OraclePlSqlParser());

    static Stream<String> cases() throws IOException {
        try (Stream<Path> paths = Files.list(GOLDEN_ROOT)) {
            return paths.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList()
                    .stream();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void matchesExpectedRelations(String caseName) throws IOException {
        Path caseDirectory = GOLDEN_ROOT.resolve(caseName);
        String sql = Files.readString(caseDirectory.resolve("input.sql"), StandardCharsets.UTF_8);
        CollectionType type = MAPPER.getTypeFactory()
                .constructCollectionType(List.class, GoldenExpectation.class);
        List<GoldenExpectation> expected = MAPPER.readValue(
                caseDirectory.resolve("expected.json").toFile(), type);

        DependencyExtractor.ExtractionResult result = extractor.extract(caseName, sql, 400);
        Set<String> actualKeys = new LinkedHashSet<>();
        for (DependencyRelation relation : result.relations()) {
            actualKeys.add(key(
                    relation.targetKey(),
                    relation.operation().name(),
                    relation.confidence().name(),
                    relation.dynamicSql(),
                    relation.sourceUnit(),
                    relation.sourceLocation().line()));
        }
        Set<String> expectedKeys = new LinkedHashSet<>();
        for (GoldenExpectation expectation : expected) {
            expectedKeys.add(key(
                    expectation.target(),
                    expectation.operation(),
                    expectation.confidence(),
                    expectation.dynamicSql(),
                    expectation.sourceUnit(),
                    expectation.line()));
        }

        assertEquals(expectedKeys, actualKeys, "依赖关系与预期不一致，用例目录: " + caseDirectory);
        for (DependencyRelation relation : result.relations()) {
            assertTrue(relation.sqlSnippet() != null && !relation.sqlSnippet().isBlank(),
                    "每条依赖都必须携带 SQL 片段证据");
            assertTrue(relation.sourceLocation().line() >= 1, "每条依赖都必须携带行号");
        }
    }

    private static String key(
            String target,
            String operation,
            String confidence,
            boolean dynamic,
            String sourceUnit,
            int line
    ) {
        return target + "|" + operation + "|" + confidence + "|dynamic=" + dynamic
                + "|" + sourceUnit + "|L" + line;
    }

    public record GoldenExpectation(
            String target,
            String operation,
            String confidence,
            boolean dynamicSql,
            String sourceUnit,
            int line
    ) {
    }
}
