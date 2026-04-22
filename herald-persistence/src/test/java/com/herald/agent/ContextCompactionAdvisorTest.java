package com.herald.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContextCompactionAdvisorTest {

    @TempDir
    Path tempDir;

    @Test
    void compactionWritesCompactEventToLogAndRefreshesHot() throws IOException {
        Path logFile = tempDir.resolve("log.md");
        Path hotFile = tempDir.resolve("hot.md");

        ChatMemory memory = mock(ChatMemory.class);
        List<Message> history = buildLargeHistory(20, 600);
        when(memory.get(any())).thenReturn(history);

        ChatModel summaryModel = mock(ChatModel.class);
        when(summaryModel.call(any(Prompt.class))).thenReturn(mockResponse(
                "User asked Herald to ship Phase A memory changes; compaction writes summary."));

        var advisor = new ContextCompactionAdvisor(memory, summaryModel, 1000, logFile, hotFile);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any())).thenAnswer(inv -> mock(ChatClientResponse.class));

        Prompt prompt = new Prompt(new SystemMessage("system"));
        ChatClientRequest request = new ChatClientRequest(prompt, Map.of());
        advisor.adviseCall(request, chain);

        List<String> lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0))
                .contains("COMPACT")
                .contains("removed_msgs=")
                .contains("kept_msgs=")
                .contains("summary=\"User asked Herald");

        String hot = Files.readString(hotFile, StandardCharsets.UTF_8);
        assertThat(hot).contains("User asked Herald to ship Phase A memory changes");
    }

    @Test
    void compactionSkipsWhenHistoryIsSmall() throws IOException {
        Path logFile = tempDir.resolve("log.md");
        Path hotFile = tempDir.resolve("hot.md");

        ChatMemory memory = mock(ChatMemory.class);
        when(memory.get(any())).thenReturn(List.of(new UserMessage("hi")));
        ChatModel summaryModel = mock(ChatModel.class);

        var advisor = new ContextCompactionAdvisor(memory, summaryModel, 10_000, logFile, hotFile);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any())).thenAnswer(inv -> mock(ChatClientResponse.class));

        advisor.adviseCall(
                new ChatClientRequest(new Prompt(new SystemMessage("s")), Map.of()), chain);

        assertThat(Files.exists(logFile)).isFalse();
        assertThat(Files.exists(hotFile)).isFalse();
    }

    private List<Message> buildLargeHistory(int turns, int charsEach) {
        String filler = "x".repeat(charsEach);
        List<Message> out = new ArrayList<>();
        for (int i = 0; i < turns; i++) {
            out.add(new UserMessage("u " + i + " " + filler));
            out.add(new AssistantMessage("a " + i + " " + filler));
        }
        return out;
    }

    private ChatResponse mockResponse(String text) {
        Generation g = new Generation(new AssistantMessage(text));
        ChatResponseMetadata md = ChatResponseMetadata.builder()
                .usage(new EmptyUsage()).model("test").build();
        return new ChatResponse(List.of(g), md);
    }
}
