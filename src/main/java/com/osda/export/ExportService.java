package com.osda.export;

import com.osda.analysis.model.DependencyRelation;
import com.osda.analysis.model.SourceLocation;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ExportService {

    private static final String[] COLUMNS = {
            "source_unit", "source_type", "source_file", "line", "column",
            "target_schema", "target_object", "target_type", "operation", "confidence",
            "is_dynamic_sql", "warning", "sql_snippet"
    };

    /** CSV with a UTF-8 BOM so that Excel on Windows opens Chinese text correctly. */
    public byte[] relationsCsv(List<DependencyRelation> relations) {
        StringBuilder builder = new StringBuilder("\uFEFF");
        builder.append(String.join(",", COLUMNS)).append("\r\n");
        for (DependencyRelation relation : relations) {
            SourceLocation location = relation.sourceLocation();
            appendRow(builder, List.of(
                    relation.sourceUnit(),
                    relation.sourceType().name(),
                    relation.sourceFile(),
                    String.valueOf(location.line()),
                    String.valueOf(location.column()),
                    nullToEmpty(relation.targetSchema()),
                    relation.targetObject(),
                    relation.targetType().name(),
                    relation.operation().name(),
                    relation.confidence().name(),
                    String.valueOf(relation.dynamicSql()),
                    nullToEmpty(relation.warning()),
                    relation.sqlSnippet()));
        }
        return builder.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void appendRow(StringBuilder builder, List<String> values) {
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append(escape(values.get(index)));
        }
        builder.append("\r\n");
    }

    private String escape(String value) {
        String text = value == null ? "" : value;
        text = text.replace("\r\n", "\n").replace('\r', '\n');
        if (text.contains(",") || text.contains("\"") || text.contains("\n")) {
            return '"' + text.replace("\"", "\"\"") + '"';
        }
        return text;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
