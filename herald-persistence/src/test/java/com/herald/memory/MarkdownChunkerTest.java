package com.herald.memory;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class MarkdownChunkerTest {

    private final MarkdownChunker chunker = new MarkdownChunker(500, 1000);

    @Test
    void shortFileReturnedWhole() {
        String content = "Short note about weather.";
        List<String> chunks = chunker.chunk(content);
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).isEqualTo(content);
    }

    @Test
    void longFileChunkedByHeadings() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Section One\n\n");
        sb.append("A".repeat(800)).append("\n\n");
        sb.append("## Section Two\n\n");
        sb.append("B".repeat(800)).append("\n\n");
        sb.append("## Section Three\n\n");
        sb.append("C".repeat(800)).append("\n\n");

        List<String> chunks = chunker.chunk(sb.toString());
        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks.get(0)).contains("Section One");
    }

    @Test
    void emptyContentReturnsEmpty() {
        List<String> chunks = chunker.chunk("");
        assertThat(chunks).isEmpty();
    }

    @Test
    void noHeadingsButLongFileSplitsByParagraph() {
        String content = ("This is a paragraph.\n\n").repeat(120);
        List<String> chunks = chunker.chunk(content);
        assertThat(chunks).hasSizeGreaterThan(1);
    }

    @Test
    void frontmatterStripped() {
        String content = "---\ntags: [test]\n---\n\n# Title\n\nBody text here.";
        List<String> chunks = chunker.chunk(content);
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).doesNotContain("tags: [test]");
    }
}
