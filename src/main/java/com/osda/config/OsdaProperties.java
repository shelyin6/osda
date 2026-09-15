package com.osda.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "osda")
public class OsdaProperties {

    /** Parser identifier stored in every analysis result for traceability. */
    private String parserVersion = "osda-parser/0.1.0";

    /** Maximum characters of the original SQL kept as evidence per relation. */
    private int snippetLength = 400;

    /** Maximum depth allowed when expanding upstream/downstream lineage. */
    private int maxLineageDepth = 10;

    public String getParserVersion() {
        return parserVersion;
    }

    public void setParserVersion(String parserVersion) {
        this.parserVersion = parserVersion;
    }

    public int getSnippetLength() {
        return snippetLength;
    }

    public void setSnippetLength(int snippetLength) {
        this.snippetLength = snippetLength;
    }

    public int getMaxLineageDepth() {
        return maxLineageDepth;
    }

    public void setMaxLineageDepth(int maxLineageDepth) {
        this.maxLineageDepth = maxLineageDepth;
    }
}
