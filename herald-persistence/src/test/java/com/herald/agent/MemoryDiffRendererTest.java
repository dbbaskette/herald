package com.herald.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryDiffRendererTest {

    @Test
    void renderStrReplaceShowsOldAndNewBlocks() {
        String input = """
                {"path":"concepts/hot_path.md","old_str":"Hot path = CPU","new_str":"Hot path = req/resp"}
                """;

        String diff = MemoryDiffRenderer.render("memoryStrReplace", input, null);

        assertThat(diff).contains("→ Edit concepts/hot_path.md");
        assertThat(diff).contains("  - Hot path = CPU");
        assertThat(diff).contains("  + Hot path = req/resp");
    }

    @Test
    void renderCreateShowsFullContent() {
        String input = """
                {"path":"entities/jamie.md","content":"---\\nname: Jamie\\ntype: entity\\n---\\nA person."}
                """;

        String diff = MemoryDiffRenderer.render("memoryCreate", input, null);

        assertThat(diff).contains("→ Create entities/jamie.md");
        assertThat(diff).contains("  + ---");
        assertThat(diff).contains("  + name: Jamie");
    }

    @Test
    void renderDeleteShowsExistingContent(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("sources/old.md");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "---\ntype: source\n---\nWill be deleted.\n");

        String input = """
                {"path":"sources/old.md"}
                """;
        String diff = MemoryDiffRenderer.render("memoryDelete", input, tempDir);

        assertThat(diff).contains("→ Delete sources/old.md");
        assertThat(diff).contains("  - Will be deleted.");
    }

    @Test
    void renderDeleteMissingFileFallsBackToPlaceholder(@TempDir Path tempDir) {
        String input = "{\"path\":\"missing.md\"}";

        String diff = MemoryDiffRenderer.render("memoryDelete", input, tempDir);

        assertThat(diff).contains("→ Delete missing.md");
        assertThat(diff).contains("(file content unavailable for preview)");
    }

    @Test
    void renderRenameShowsPathSwap() {
        String input = """
                {"old_path":"old.md","new_path":"renamed.md"}
                """;

        String diff = MemoryDiffRenderer.render("memoryRename", input, null);

        assertThat(diff).contains("→ Rename old.md → renamed.md");
    }

    @Test
    void resolvePageTypeReadsFrontmatterFromExistingFile(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("concepts/hot_path.md");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "---\nname: Hot Path\ntype: concept\n---\nbody");

        String type = MemoryDiffRenderer.resolvePageType(tempDir, "concepts/hot_path.md", null);

        assertThat(type).isEqualTo("concept");
    }

    @Test
    void resolvePageTypeFallsBackToPathPrefix(@TempDir Path tempDir) {
        // No file present and no create-content — heuristic on path.
        assertThat(MemoryDiffRenderer.resolvePageType(tempDir, "concepts/foo.md", null))
                .isEqualTo("concept");
        assertThat(MemoryDiffRenderer.resolvePageType(tempDir, "entities/jamie.md", null))
                .isEqualTo("entity");
        assertThat(MemoryDiffRenderer.resolvePageType(tempDir, "sources/x.md", null))
                .isEqualTo("source");
    }

    @Test
    void resolvePageTypeReadsFromCreateContent() {
        String content = "---\nname: New\ntype: project\n---\nBody.";

        String type = MemoryDiffRenderer.resolvePageType(null, "anywhere.md", content);

        assertThat(type).isEqualTo("project");
    }

    @Test
    void truncateAddsEllipsisMarker() {
        String big = "a".repeat(MemoryDiffRenderer.MAX_BODY_PREVIEW + 50);

        String result = MemoryDiffRenderer.truncate(big, MemoryDiffRenderer.MAX_BODY_PREVIEW);

        assertThat(result).hasSizeGreaterThan(MemoryDiffRenderer.MAX_BODY_PREVIEW);
        assertThat(result).contains("[truncated, 50 chars elided]");
    }

    @Test
    void prefixLinesAddsPrefixToEveryLine() {
        String result = MemoryDiffRenderer.prefixLines("  + ", "line1\nline2\nline3");

        assertThat(result).isEqualTo("  + line1\n  + line2\n  + line3");
    }

    @Test
    void parseTypeFromFrontmatterReturnsNullForBadInput() {
        assertThat(MemoryDiffRenderer.parseTypeFromFrontmatter(null)).isNull();
        assertThat(MemoryDiffRenderer.parseTypeFromFrontmatter("no frontmatter")).isNull();
        assertThat(MemoryDiffRenderer.parseTypeFromFrontmatter("---\nname: foo\n---\n")).isNull();
    }
}
