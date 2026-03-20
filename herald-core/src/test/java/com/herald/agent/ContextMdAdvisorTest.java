package com.herald.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class ContextMdAdvisorTest {

    @TempDir
    Path tempDir;

    @Test
    void injectsContextFileContentIntoSystemPrompt() throws IOException {
        Path contextFile = tempDir.resolve("CONTEXT.md");
        Files.writeString(contextFile, "Dan is a Spring Boot expert.", StandardCharsets.UTF_8);

        var advisor = new ContextMdAdvisor(contextFile);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any())).thenAnswer(inv -> mock(ChatClientResponse.class));

        Prompt prompt = new Prompt(new SystemMessage("You are Herald."));
        ChatClientRequest request = new ChatClientRequest(prompt, Map.of());

        advisor.adviseCall(request, chain);

        verify(chain).nextCall(argThat(req -> {
            String text = req.prompt().getSystemMessage().getText();
            return text.contains("You are Herald.")
                    && text.contains("<context>")
                    && text.contains("Dan is a Spring Boot expert.")
                    && text.contains("</context>");
        }));
    }

    @Test
    void skipsInjectionWhenFileDoesNotExist() {
        Path contextFile = tempDir.resolve("nonexistent.md");

        var advisor = new ContextMdAdvisor(contextFile);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any())).thenAnswer(inv -> mock(ChatClientResponse.class));

        Prompt prompt = new Prompt(new SystemMessage("You are Herald."));
        ChatClientRequest request = new ChatClientRequest(prompt, Map.of());

        advisor.adviseCall(request, chain);

        // Original request passed through unchanged
        verify(chain).nextCall(request);
    }

    @Test
    void skipsInjectionWhenFileIsEmpty() throws IOException {
        Path contextFile = tempDir.resolve("CONTEXT.md");
        Files.writeString(contextFile, "   \n  ", StandardCharsets.UTF_8);

        var advisor = new ContextMdAdvisor(contextFile);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any())).thenAnswer(inv -> mock(ChatClientResponse.class));

        Prompt prompt = new Prompt(new SystemMessage("You are Herald."));
        ChatClientRequest request = new ChatClientRequest(prompt, Map.of());

        advisor.adviseCall(request, chain);

        // Whitespace-only file treated as empty — original request passed through
        verify(chain).nextCall(request);
    }

    @Test
    void readsFileOnEachTurn() throws IOException {
        Path contextFile = tempDir.resolve("CONTEXT.md");
        Files.writeString(contextFile, "Version 1", StandardCharsets.UTF_8);

        var advisor = new ContextMdAdvisor(contextFile);

        // First read
        assertThat(advisor.readContextFile()).isEqualTo("Version 1");

        // Update file — change reflected immediately
        Files.writeString(contextFile, "Version 2", StandardCharsets.UTF_8);
        assertThat(advisor.readContextFile()).isEqualTo("Version 2");
    }

    @Test
    void ensureTemplateCreatesFileWhenMissing() {
        Path contextFile = tempDir.resolve("subdir/CONTEXT.md");

        var advisor = new ContextMdAdvisor(contextFile);
        advisor.ensureTemplateExists("# Starter Template\nHello!");

        assertThat(contextFile).exists();
        assertThat(contextFile).content(StandardCharsets.UTF_8).contains("# Starter Template");
    }

    @Test
    void ensureTemplateDoesNotOverwriteExistingFile() throws IOException {
        Path contextFile = tempDir.resolve("CONTEXT.md");
        Files.writeString(contextFile, "My custom content", StandardCharsets.UTF_8);

        var advisor = new ContextMdAdvisor(contextFile);
        advisor.ensureTemplateExists("# Starter Template");

        assertThat(contextFile).content(StandardCharsets.UTF_8).isEqualTo("My custom content");
    }

    @Test
    void nameIsContextMdAdvisor() {
        var advisor = new ContextMdAdvisor(tempDir.resolve("CONTEXT.md"));
        assertThat(advisor.getName()).isEqualTo("ContextMdAdvisor");
    }

    @Test
    void orderIsBetweenDateTimeAndMemoryAdvisors() {
        var advisor = new ContextMdAdvisor(tempDir.resolve("CONTEXT.md"));
        var dateTimeOrder = Ordered.HIGHEST_PRECEDENCE + 50;
        var memoryOrder = Ordered.HIGHEST_PRECEDENCE + 100;

        assertThat(advisor.getOrder()).isGreaterThan(dateTimeOrder);
        assertThat(advisor.getOrder()).isLessThan(memoryOrder);
    }
}
