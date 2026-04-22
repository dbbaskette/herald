package com.herald.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HeraldAutoMemoryAdvisorTest {

    @TempDir
    Path memoriesDir;

    @Test
    void buildsWrappedToolCallbacksForMutatingOps() {
        var advisor = HeraldAutoMemoryAdvisor.builder()
                .memoriesRootDirectory(memoriesDir)
                .logFile(memoriesDir.resolve("log.md"))
                .build();

        List<ToolCallback> callbacks = advisor.memoryToolCallbacks();
        assertThat(callbacks).isNotEmpty();

        for (ToolCallback cb : callbacks) {
            String name = cb.getToolDefinition().name();
            if (LoggingMemoryToolCallback.isMutatingMemoryTool(name)) {
                assertThat(cb)
                        .as("mutating tool %s should be wrapped", name)
                        .isInstanceOf(LoggingMemoryToolCallback.class);
            } else {
                assertThat(cb)
                        .as("read-only tool %s should not be wrapped", name)
                        .isNotInstanceOf(LoggingMemoryToolCallback.class);
            }
        }
    }

    @Test
    void injectsMemorySystemPromptAndMergesToolCallbacksWhenToolOptionsPresent() {
        var advisor = HeraldAutoMemoryAdvisor.builder()
                .memoriesRootDirectory(memoriesDir)
                .logFile(memoriesDir.resolve("log.md"))
                .build();

        AnthropicChatOptions options = AnthropicChatOptions.builder().build();
        Prompt prompt = new Prompt(List.of(new SystemMessage("You are Herald.")), options);
        ChatClientRequest request = new ChatClientRequest(prompt, Map.of());

        ChatClientRequest after = advisor.before(request, null);

        String system = after.prompt().getSystemMessage().getText();
        assertThat(system)
                .contains("You are Herald.")
                .containsIgnoringCase("memory");

        var merged = ((AnthropicChatOptions) after.prompt().getOptions()).getToolCallbacks();
        assertThat(merged).isNotEmpty();
        assertThat(merged.stream().map(c -> c.getToolDefinition().name()))
                .contains("MemoryCreate", "MemoryView");
    }

    @Test
    void passesThroughWhenOptionsLackToolCalling() {
        var advisor = HeraldAutoMemoryAdvisor.builder()
                .memoriesRootDirectory(memoriesDir)
                .build();

        Prompt prompt = new Prompt(new SystemMessage("You are Herald."));
        ChatClientRequest request = new ChatClientRequest(prompt, Map.of());

        ChatClientRequest after = advisor.before(request, null);

        assertThat(after).isSameAs(request);
    }
}
