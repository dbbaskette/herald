package com.herald.agent.profile;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentProfileParserTest {

    @Test
    void agentProfileRecordHoldsAllFields() {
        var profile = new AgentProfile(
                "cf-analyzer",
                "Analyzes Cloud Foundry environments",
                "sonnet",
                "anthropic",
                List.of("filesystem", "shell", "web"),
                null,   // skillsDirectory
                null,   // subagentsDirectory
                false,  // memory
                "./CONTEXT.md",
                200_000
        );

        assertThat(profile.name()).isEqualTo("cf-analyzer");
        assertThat(profile.description()).isEqualTo("Analyzes Cloud Foundry environments");
        assertThat(profile.model()).isEqualTo("sonnet");
        assertThat(profile.provider()).isEqualTo("anthropic");
        assertThat(profile.tools()).containsExactly("filesystem", "shell", "web");
        assertThat(profile.skillsDirectory()).isNull();
        assertThat(profile.subagentsDirectory()).isNull();
        assertThat(profile.memory()).isFalse();
        assertThat(profile.contextFile()).isEqualTo("./CONTEXT.md");
        assertThat(profile.maxTokens()).isEqualTo(200_000);
    }

    @Test
    void parsesFullAgentMd() {
        String content = """
                ---
                name: cf-analyzer
                description: Analyzes Cloud Foundry environments
                model: sonnet
                provider: anthropic
                tools: [filesystem, shell, web, skills]
                skills_directory: ./skills
                subagents_directory: ./.claude/agents
                memory: false
                context_file: ./CONTEXT.md
                max_tokens: 200000
                ---

                You are a Cloud Foundry operations analyst.
                """;

        AgentProfileParser.Result result = AgentProfileParser.parse(content);

        assertThat(result.profile().name()).isEqualTo("cf-analyzer");
        assertThat(result.profile().description()).isEqualTo("Analyzes Cloud Foundry environments");
        assertThat(result.profile().model()).isEqualTo("sonnet");
        assertThat(result.profile().provider()).isEqualTo("anthropic");
        assertThat(result.profile().tools()).containsExactly("filesystem", "shell", "web", "skills");
        assertThat(result.profile().skillsDirectory()).isEqualTo("./skills");
        assertThat(result.profile().subagentsDirectory()).isEqualTo("./.claude/agents");
        assertThat(result.profile().memory()).isFalse();
        assertThat(result.profile().contextFile()).isEqualTo("./CONTEXT.md");
        assertThat(result.profile().maxTokens()).isEqualTo(200_000);
        assertThat(result.systemPrompt()).isEqualTo("You are a Cloud Foundry operations analyst.");
    }

    @Test
    void parsesWithMissingOptionalFields() {
        String content = """
                ---
                name: simple-agent
                description: A simple agent
                ---

                Do the thing.
                """;

        AgentProfileParser.Result result = AgentProfileParser.parse(content);

        assertThat(result.profile().name()).isEqualTo("simple-agent");
        assertThat(result.profile().description()).isEqualTo("A simple agent");
        assertThat(result.profile().model()).isNull();
        assertThat(result.profile().provider()).isNull();
        assertThat(result.profile().tools()).isEmpty();
        assertThat(result.profile().skillsDirectory()).isNull();
        assertThat(result.profile().subagentsDirectory()).isNull();
        assertThat(result.profile().memory()).isFalse();
        assertThat(result.profile().contextFile()).isNull();
        assertThat(result.profile().maxTokens()).isNull();
        assertThat(result.systemPrompt()).isEqualTo("Do the thing.");
    }

    @Test
    void parsesCommaSeparatedTools() {
        String content = """
                ---
                name: test
                description: test
                tools: Read, Grep, Glob
                ---

                prompt
                """;

        AgentProfileParser.Result result = AgentProfileParser.parse(content);
        assertThat(result.profile().tools()).containsExactly("Read", "Grep", "Glob");
    }

    @Test
    void throwsForMissingFrontmatter() {
        String content = "Just a plain markdown file with no frontmatter.";

        assertThatThrownBy(() -> AgentProfileParser.parse(content))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("frontmatter");
    }

    @Test
    void throwsForMissingName() {
        String content = """
                ---
                description: no name field
                ---

                prompt
                """;

        assertThatThrownBy(() -> AgentProfileParser.parse(content))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void parsesFromFile() throws IOException {
        Path file = Files.createTempFile("agent", ".md");
        Files.writeString(file, """
                ---
                name: file-agent
                description: Loaded from file
                model: opus
                memory: true
                ---

                You are loaded from a file.
                """);

        AgentProfileParser.Result result = AgentProfileParser.parseFile(file);

        assertThat(result.profile().name()).isEqualTo("file-agent");
        assertThat(result.profile().memory()).isTrue();
        assertThat(result.systemPrompt()).isEqualTo("You are loaded from a file.");

        Files.deleteIfExists(file);
    }

    @Test
    void memoryDefaultsToFalse() {
        String content = """
                ---
                name: test
                description: test
                ---

                prompt
                """;

        AgentProfileParser.Result result = AgentProfileParser.parse(content);
        assertThat(result.profile().memory()).isFalse();
    }
}
