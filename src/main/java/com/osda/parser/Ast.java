package com.osda.parser;

import com.osda.analysis.model.SourceLocation;
import com.osda.analysis.model.SourceType;
import java.util.List;

/**
 * Syntax tree nodes produced by the offline Oracle SQL/PLSQL parser.
 *
 * <p>Every node keeps raw offsets so the analyzer can attach the original SQL fragment as
 * evidence. Nodes are intentionally shallow: the goal is dependency extraction, not a full
 * Oracle grammar.
 */
public final class Ast {

    private Ast() {
    }

    public record ObjectName(String schema, String object, boolean quoted, SourceLocation location) {

        public String qualified() {
            return schema == null || schema.isBlank() ? object : schema + "." + object;
        }
    }

    public record TableRef(ObjectName name, String alias, boolean cteReference, SourceLocation location) {
    }

    public record Cte(String name, SourceLocation location) {
    }

    public sealed interface Statement
            permits Select, Insert, Update, Delete, Merge, DynamicSql, Unparsed {

        int startOffset();

        int endOffset();

        SourceLocation location();
    }

    public record Select(
            int startOffset,
            int endOffset,
            SourceLocation location,
            List<Cte> ctes,
            List<TableRef> sources
    ) implements Statement {
    }

    public record Insert(
            int startOffset,
            int endOffset,
            SourceLocation location,
            ObjectName target,
            List<Cte> ctes,
            List<TableRef> sources
    ) implements Statement {
    }

    public record Update(
            int startOffset,
            int endOffset,
            SourceLocation location,
            ObjectName target,
            List<TableRef> sources
    ) implements Statement {
    }

    public record Delete(
            int startOffset,
            int endOffset,
            SourceLocation location,
            ObjectName target,
            List<TableRef> sources
    ) implements Statement {
    }

    public record Merge(
            int startOffset,
            int endOffset,
            SourceLocation location,
            ObjectName target,
            ObjectName mergeSource,
            List<TableRef> sources
    ) implements Statement {
    }

    /**
     * EXECUTE IMMEDIATE.
     *
     * @param extractedSql constant SQL text when it could be resolved statically, otherwise null
     * @param reason       short explanation when the dynamic SQL could not be resolved
     */
    public record DynamicSql(
            int startOffset,
            int endOffset,
            SourceLocation location,
            String extractedSql,
            String reason
    ) implements Statement {
    }

    /** Statement that could not be classified; kept so the analyzer can raise a warning. */
    public record Unparsed(
            int startOffset,
            int endOffset,
            SourceLocation location,
            String note
    ) implements Statement {
    }

    public record ProgramUnit(
            String schema,
            String name,
            SourceType type,
            String container,
            SourceLocation location,
            int startOffset,
            int endOffset,
            List<Statement> statements
    ) {

        public String qualifiedName() {
            return schema == null || schema.isBlank() ? name : schema + "." + name;
        }
    }
}
