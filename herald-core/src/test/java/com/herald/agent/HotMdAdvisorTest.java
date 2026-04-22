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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HotMdAdvisorTest {

    @TempDir
    Path tempDir;

    @Test
    void injectsHotFileContentWrappedInHotContextTag() throws IOException {
        Path hot = tempDir.resolve("hot.md");
        Files.writeString(hot, "User is working on Phase A memory changes.", StandardCharsets.UTF_8);

        var advisor = new HotMdAdvisor(hot);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any())).thenAnswer(inv -> mock(ChatClientResponse.class));

        Prompt prompt = new Prompt(new SystemMessage("You are Herald."));
        ChatClientRequest request = new ChatClientRequest(prompt, Map.of());

        advisor.adviseCall(request, chain);

        verify(chain).nextCall(argThat(req -> {
            String text = req.prompt().getSystemMessage().getText();
            return text.contains("You are Herald.")
                    && text.contains("<hot-context>")
                    && text.contains("User is working on Phase A memory changes.")
                    && text.contains("</hot-context>");
        }));
    }

    @Test
    void skipsInjectionWhenFileMissing() {
        var advisor = new HotMdAdvisor(tempDir.resolve("nope.md"));
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any())).thenAnswer(inv -> mock(ChatClientResponse.class));

        Prompt prompt = new Prompt(new SystemMessage("You are Herald."));
        ChatClientRequest request = new ChatClientRequest(prompt, Map.of());

        advisor.adviseCall(request, chain);

        verify(chain).nextCall(argThat(req ->
                req.prompt().getSystemMessage().getText().equals("You are Herald.")));
    }

    @Test
    void orderedBetweenContextMdAndMemoryAdvisor() {
        assertThat(new HotMdAdvisor(tempDir.resolve("hot.md")).getOrder())
                .isGreaterThan(Ordered.HIGHEST_PRECEDENCE + 75)
                .isLessThan(Ordered.HIGHEST_PRECEDENCE + 100);
    }
}
