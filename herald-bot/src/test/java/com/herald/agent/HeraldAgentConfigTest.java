package com.herald.agent;

import com.herald.config.HeraldConfig;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class HeraldAgentConfigTest {

    private final HeraldAgentConfig agentConfig = new HeraldAgentConfig();

    @Test
    void resolvePromptInjectsPersona() throws IOException {
        String template = loadPromptTemplate();
        HeraldConfig config = configWith("TestBot — a test persona", null);

        String result = agentConfig.resolvePrompt(template, config, "claude-sonnet-4-5", "skills");

        assertThat(result).contains("You are **TestBot — a test persona**");
        assertThat(result).doesNotContain("{persona}");
    }

    @Test
    void resolvePromptLeavesDynamicPlaceholders() throws IOException {
        String template = loadPromptTemplate();
        HeraldConfig config = configWith(null, null);

        String result = agentConfig.resolvePrompt(template, config, "claude-sonnet-4-5", "skills");

        // Dynamic placeholders are resolved per-turn by DateTimePromptAdvisor, not at startup
        assertThat(result).contains("{current_datetime}");
        assertThat(result).contains("{timezone}");
    }

    @Test
    void resolvePromptInjectsSystemPromptExtra() throws IOException {
        String template = loadPromptTemplate();
        HeraldConfig config = configWith(null, "Always respond in haiku format.");

        String result = agentConfig.resolvePrompt(template, config, "claude-sonnet-4-5", "skills");

        assertThat(result).contains("Always respond in haiku format.");
        assertThat(result).doesNotContain("{system_prompt_extra}");
    }

    @Test
    void resolvePromptHandlesEmptyExtra() throws IOException {
        String template = loadPromptTemplate();
        HeraldConfig config = configWith(null, null);

        String result = agentConfig.resolvePrompt(template, config, "claude-sonnet-4-5", "skills");

        assertThat(result).doesNotContain("{system_prompt_extra}");
    }

    @Test
    void resolvePromptInjectsTaskManagementGuidance() throws IOException {
        String template = loadPromptTemplate();
        HeraldConfig config = configWith(null, null);

        String result = agentConfig.resolvePrompt(template, config, "claude-sonnet-4-5", "skills");

        assertThat(result).doesNotContain("{task_management_guidance}");
        assertThat(result).contains("# Task Management");
        assertThat(result).contains("todoWrite");
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

        String result = agentConfig.resolvePrompt(template, config, "claude-sonnet-4-5", "skills");

        assertThat(result).contains("You are **Herald");
        assertThat(result).doesNotContain("{persona}");
    }

    @Test
    void blankPersonaFallsBackToDefault() throws IOException {
        String template = loadPromptTemplate();
        HeraldConfig config = configWith("   ", null);

        String result = agentConfig.resolvePrompt(template, config, "claude-sonnet-4-5", "skills");

        assertThat(result).contains("You are **Herald");
        assertThat(result).doesNotContain("{persona}");
    }

    @Test
    void loadPromptTemplateThrowsForMissingResource() {
        assertThatThrownBy(() ->
                agentConfig.modelSwitcher(null, configWith(null, null), false,
                        Optional.empty(),
                        mock(com.herald.tools.HeraldShellDecorator.class),
                        new com.herald.tools.FileSystemTools(),
                        Optional.empty(),
                        mock(org.springframework.beans.factory.ObjectProvider.class),
                        Optional.empty(), Optional.empty(),
                        new com.herald.tools.WebTools(""),
                        Optional.empty(), Optional.empty(),
                        new ClassPathResource("prompts/NONEXISTENT.md"),
                        ".claude/agents", new ReloadableSkillsTool("skills"),
                        new ValidateSkillTool("skills"),
                        "claude-sonnet-4-5", "claude-haiku-4-5",
                        "claude-sonnet-4-5", "claude-opus-4-5",
                        "gpt-4o", "llama3.2", "gemini-2.5-flash", "qwen/qwen3.5-35b-a3b",
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        List.of("shell", "filesystem", "todoWrite", "askUserQuestion", "task", "taskOutput", "skills", "web")))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("Failed to load system prompt template");
    }

    @Test
    void chatOptionsForAnthropicModelReturnsAnthropicOptions() {
        var model = mock(AnthropicChatModel.class);
        ChatOptions.Builder<?> options = HeraldAgentConfig.chatOptionsForModel(model, "claude-sonnet-4-5");
        assertThat(options.build()).isInstanceOf(AnthropicChatOptions.class);
    }

    @Test
    void chatOptionsForOpenAiModelReturnsChatOptions() {
        var model = mock(OpenAiChatModel.class);
        ChatOptions.Builder<?> options = HeraldAgentConfig.chatOptionsForModel(model, "gpt-4o");
        assertThat(options.build()).isInstanceOf(ChatOptions.class);
        assertThat(options.build().getModel()).isEqualTo("gpt-4o");
    }

    private HeraldConfig configWith(String persona, String extra) {
        return new HeraldConfig(null, null,
                new HeraldConfig.Agent(persona, extra, null, null, null, null, null), null, null, null, null, null, null, null);
    }

    private String loadPromptTemplate() throws IOException {
        try (var stream = getClass().getResourceAsStream("/prompts/MAIN_AGENT_SYSTEM_PROMPT.md")) {
            assertThat(stream).as("Prompt template resource must exist").isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
