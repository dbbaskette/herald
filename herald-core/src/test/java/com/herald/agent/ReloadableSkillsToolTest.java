package com.herald.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReloadableSkillsToolTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsSkillFromDirectory() throws IOException {
        Path skillDir = tempDir.resolve("my-skill");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: my-skill
                description: A test skill
                ---
                This is the skill content.
                """);

        var tool = new ReloadableSkillsTool(tempDir.toString());
        assertThat(tool.getSkills()).hasSize(1);
        assertThat(tool.getSkills().get(0).name()).isEqualTo("my-skill");
        assertThat(tool.hasSkills()).isTrue();
    }

    @Test
    void returnsEmptyWhenDirectoryDoesNotExist() {
        var tool = new ReloadableSkillsTool(tempDir.resolve("nonexistent").toString());
        assertThat(tool.getSkills()).isEmpty();
        assertThat(tool.hasSkills()).isFalse();
        assertThat(tool.call("{\"command\":\"anything\"}")).isEqualTo("No skills are currently loaded.");
    }

    @Test
    void reloadPicksUpNewSkills() throws IOException {
        var tool = new ReloadableSkillsTool(tempDir.toString());
        assertThat(tool.getSkills()).isEmpty();

        Path skillDir = tempDir.resolve("new-skill");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: new-skill
                description: Added after initial load
                ---
                New content.
                """);

        int count = tool.reload();
        assertThat(count).isEqualTo(1);
        assertThat(tool.getSkills()).hasSize(1);
    }

    @Test
    void getAllowedToolsReturnsListFromCommaSeparatedFrontmatter() throws IOException {
        Path skillDir = tempDir.resolve("restricted-skill");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: restricted-skill
                description: Skill with tool restrictions
                allowed-tools: shell, web_fetch
                ---
                Only use shell and web_fetch.
                """);

        var tool = new ReloadableSkillsTool(tempDir.toString());
        List<String> allowed = tool.getAllowedTools("restricted-skill");
        assertThat(allowed).containsExactly("shell", "web_fetch");
    }

    @Test
    void getAllowedToolsReturnsEmptyWhenNotSpecified() throws IOException {
        Path skillDir = tempDir.resolve("open-skill");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: open-skill
                description: No restrictions
                ---
                Use any tools.
                """);

        var tool = new ReloadableSkillsTool(tempDir.toString());
        assertThat(tool.getAllowedTools("open-skill")).isEmpty();
    }

    @Test
    void getAllowedToolsReturnsEmptyForUnknownSkill() {
        var tool = new ReloadableSkillsTool(tempDir.toString());
        assertThat(tool.getAllowedTools("nonexistent")).isEmpty();
    }

    @Test
    void getSkillModelReturnsModelFromFrontmatter() throws IOException {
        Path skillDir = tempDir.resolve("model-skill");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: model-skill
                description: Skill with model preference
                model: opus
                ---
                Use opus for this.
                """);

        var tool = new ReloadableSkillsTool(tempDir.toString());
        assertThat(tool.getSkillModel("model-skill")).isEqualTo("opus");
    }

    @Test
    void getSkillModelReturnsNullWhenNotSpecified() throws IOException {
        Path skillDir = tempDir.resolve("no-model");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: no-model
                description: No model specified
                ---
                Content.
                """);

        var tool = new ReloadableSkillsTool(tempDir.toString());
        assertThat(tool.getSkillModel("no-model")).isNull();
    }

    @Test
    void callPrependsAllowedToolsConstraint() throws IOException {
        Path skillDir = tempDir.resolve("guarded-skill");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: guarded-skill
                description: Restricted skill
                allowed-tools: shell
                ---
                Use shell only.
                """);

        var tool = new ReloadableSkillsTool(tempDir.toString());
        String result = tool.call("{\"command\":\"guarded-skill\"}");
        assertThat(result).contains("<allowed-tools>");
        assertThat(result).contains("shell");
    }

    @Test
    void callDoesNotPrependWhenNoAllowedTools() throws IOException {
        Path skillDir = tempDir.resolve("free-skill");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: free-skill
                description: No restrictions
                ---
                Content here.
                """);

        var tool = new ReloadableSkillsTool(tempDir.toString());
        String result = tool.call("{\"command\":\"free-skill\"}");
        assertThat(result).doesNotContain("<allowed-tools>");
    }

    @Test
    void parseStringOrListHandlesCommaSeparated() {
        assertThat(ReloadableSkillsTool.parseStringOrList("shell, web_fetch, filesystem"))
                .containsExactly("shell", "web_fetch", "filesystem");
    }

    @Test
    void parseStringOrListHandlesJavaList() {
        assertThat(ReloadableSkillsTool.parseStringOrList(List.of("a", "b")))
                .containsExactly("a", "b");
    }

    @Test
    void parseStringOrListHandlesNullAndBlank() {
        assertThat(ReloadableSkillsTool.parseStringOrList(null)).isEmpty();
        assertThat(ReloadableSkillsTool.parseStringOrList("")).isEmpty();
        assertThat(ReloadableSkillsTool.parseStringOrList("  ")).isEmpty();
    }

    @Test
    void extractSkillNameFromJson() throws IOException {
        Path skillDir = tempDir.resolve("extract-test");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: extract-test
                description: Test extraction
                allowed-tools: web_search
                ---
                Search the web.
                """);

        var tool = new ReloadableSkillsTool(tempDir.toString());
        // The call method should extract "extract-test" from JSON input
        String result = tool.call("{\"command\":\"extract-test\"}");
        assertThat(result).contains("<allowed-tools>");
        assertThat(result).contains("web_search");
    }
}
