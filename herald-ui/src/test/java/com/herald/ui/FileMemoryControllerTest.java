package com.herald.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FileMemoryControllerTest {

    @TempDir
    Path memoriesDir;

    @Test
    void groupsPagesByTypeAndExcludesIndexAndLogFiles() throws IOException {
        write(memoriesDir.resolve("user_profile.md"), """
                ---
                name: user_profile
                description: Dan, backend engineer
                type: user
                ---

                Backend engineer. Prefers short answers.
                """);
        write(memoriesDir.resolve("feedback_testing.md"), """
                ---
                name: testing
                description: Integration tests hit real DB
                type: feedback
                ---
                """);
        Path conceptsDir = memoriesDir.resolve("concepts");
        Files.createDirectories(conceptsDir);
        write(conceptsDir.resolve("hot_path.md"), """
                ---
                name: hot_path
                description: Request/response flow, not CPU-hot
                type: concept
                ---
                """);

        // files the controller must exclude
        write(memoriesDir.resolve("MEMORY.md"), "# Memory Index\n");
        write(memoriesDir.resolve("log.md"), "2026-04-22T10:00:00Z CREATE path=x\n");
        write(memoriesDir.resolve("hot.md"), "# Hot Context\n");

        // a page with no frontmatter — should land under unknown
        write(memoriesDir.resolve("orphan.md"), "just text\n");

        var controller = new FileMemoryController(memoriesDir.toString());
        var grouped = controller.listGroupedByType();

        assertThat(grouped).containsKeys("user", "feedback", "project", "reference",
                "concept", "entity", "source", "unknown");

        List<FileMemoryController.MemoryPage> users = grouped.get("user");
        assertThat(users).hasSize(1);
        assertThat(users.get(0).path()).isEqualTo("user_profile.md");
        assertThat(users.get(0).description()).isEqualTo("Dan, backend engineer");

        List<FileMemoryController.MemoryPage> concepts = grouped.get("concept");
        assertThat(concepts).hasSize(1);
        assertThat(concepts.get(0).path()).isEqualTo("concepts/hot_path.md");

        assertThat(grouped.get("unknown")).extracting("path").contains("orphan.md");
        assertThat(grouped.values().stream().flatMap(List::stream))
                .extracting("path")
                .doesNotContain("MEMORY.md", "log.md", "hot.md");
    }

    @Test
    void contentReturnsFileBodyAndRejectsTraversal() throws IOException {
        write(memoriesDir.resolve("a.md"), "hello world");
        var controller = new FileMemoryController(memoriesDir.toString());

        var ok = controller.content("a.md");
        assertThat(ok.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(ok.getBody().content()).isEqualTo("hello world");

        var escape = controller.content("../../../etc/passwd");
        assertThat(escape.getStatusCode().is4xxClientError()).isTrue();

        var missing = controller.content("nope.md");
        assertThat(missing.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void handlesMissingMemoriesDirGracefully() {
        Path missing = memoriesDir.resolve("doesnotexist");
        var controller = new FileMemoryController(missing.toString());
        var grouped = controller.listGroupedByType();
        assertThat(grouped).isNotEmpty();
        assertThat(grouped.values().stream().flatMap(List::stream)).isEmpty();
    }

    @Test
    void parseFrontmatterHandlesQuotedValuesAndMissingKeys() throws IOException {
        Path f = memoriesDir.resolve("quoted.md");
        Files.writeString(f, """
                ---
                name: "quoted name"
                description: plain desc
                type: entity
                ---

                body
                """, StandardCharsets.UTF_8);

        var fm = FileMemoryController.parseFrontmatter(f);
        assertThat(fm.name()).isEqualTo("quoted name");
        assertThat(fm.description()).isEqualTo("plain desc");
        assertThat(fm.type()).isEqualTo("entity");
    }

    private void write(Path file, String body) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, body, StandardCharsets.UTF_8);
    }
}
