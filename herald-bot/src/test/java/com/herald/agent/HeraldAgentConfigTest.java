package com.herald.agent;

import com.herald.config.HeraldConfig;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HeraldAgentConfigTest {

    private final HeraldAgentConfig agentConfig = new HeraldAgentConfig();

    @Test
    void resolvePromptInjectsPersona() throws IOException {
        String template = loadPromptTemplate();
        HeraldConfig config = configWith("TestBot — a test persona", null);

        String result = agentConfig.resolvePrompt(template, config);

        assertThat(result).contains("You are **TestBot — a test persona**");
        assertThat(result).doesNotContain("{persona}");
    }

    @Test
    void resolvePromptInjectsDatetimeAndTimezone() throws IOException {
        String template = loadPromptTemplate();
        HeraldConfig config = configWith(null, null);

        String result = agentConfig.resolvePrompt(template, config);

        assertThat(result).doesNotContain("{current_datetime}");
        assertThat(result).doesNotContain("{timezone}");
        assertThat(result).contains("America/New_York");
    }

    @Test
    void resolvePromptInjectsSystemPromptExtra() throws IOException {
        String template = loadPromptTemplate();
        HeraldConfig config = configWith(null, "Always respond in haiku format.");

        String result = agentConfig.resolvePrompt(template, config);

        assertThat(result).contains("Always respond in haiku format.");
        assertThat(result).doesNotContain("{system_prompt_extra}");
    }

    @Test
    void resolvePromptHandlesEmptyExtra() throws IOException {
        String template = loadPromptTemplate();
        HeraldConfig config = configWith(null, null);

        String result = agentConfig.resolvePrompt(template, config);

        assertThat(result).doesNotContain("{system_prompt_extra}");
    }

    @Test
    void promptTemplateContainsAllRequiredSections() throws IOException {
        String template = loadPromptTemplate();

        assertThat(template).contains("# Identity");
        assertThat(template).contains("# Current Context");
        assertThat(template).contains("# Available Tools");
        assertThat(template).contains("# Memory Management");
        assertThat(template).contains("# Communication Style");
        assertThat(template).contains("# Safety Rules");
        assertThat(template).contains("# Dan's Context");
    }

    @Test
    void emptyPersonaFallsBackToDefault() throws IOException {
        String template = loadPromptTemplate();
        HeraldConfig config = configWith("", null);

        String result = agentConfig.resolvePrompt(template, config);

        assertThat(result).contains("You are **Herald");
        assertThat(result).doesNotContain("{persona}");
    }

    @Test
    void blankPersonaFallsBackToDefault() throws IOException {
        String template = loadPromptTemplate();
        HeraldConfig config = configWith("   ", null);

        String result = agentConfig.resolvePrompt(template, config);

        assertThat(result).contains("You are **Herald");
        assertThat(result).doesNotContain("{persona}");
    }

    @Test
    void loadPromptTemplateThrowsForMissingResource() {
        assertThatThrownBy(() ->
                agentConfig.mainClient(null, null, configWith(null, null),
                        null, null, null, null, null, null,
                        new ClassPathResource("prompts/NONEXISTENT.md"),
                        ".claude/agents", "claude-haiku-4-5", "claude-sonnet-4-5", "claude-opus-4-5"))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("Failed to load system prompt template");
    }

    private HeraldConfig configWith(String persona, String extra) {
        return new HeraldConfig(null, null,
                new HeraldConfig.Agent(persona, extra));
    }

    private String loadPromptTemplate() throws IOException {
        try (var stream = getClass().getResourceAsStream("/prompts/MAIN_AGENT_SYSTEM_PROMPT.md")) {
            assertThat(stream).as("Prompt template resource must exist").isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
