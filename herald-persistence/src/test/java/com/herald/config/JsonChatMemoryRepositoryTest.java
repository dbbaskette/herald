package com.herald.config;

import com.herald.agent.ContextCompactionAdvisor;
import com.herald.agent.TurnSafeChatMemory;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

class JsonChatMemoryRepositoryTest {
    @TempDir Path temp;
    private JdbcTemplate jdbc() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + temp.resolve("memory.db")));
        jdbc.execute("CREATE TABLE SPRING_AI_CHAT_MEMORY (conversation_id TEXT, content TEXT, type TEXT, timestamp TEXT)");
        return jdbc;
    }

    @Test void summaryAndToolMetadataSurviveJdbcReloadAndWindowAppend() {
        var jdbc = jdbc(); var repository = new JsonChatMemoryRepository(jdbc);
        var metadata = Map.<String, Object>of(ContextCompactionAdvisor.SYNTHETIC, true);
        var messages = List.<Message>of(
                UserMessage.builder().text("Earlier context").metadata(metadata).build(),
                AssistantMessage.builder().content("retained summary").properties(metadata).build(),
                new UserMessage("question"),
                AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall("id", "function", "read", "{}"))).build(),
                ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse("id", "read", "result"))).build());
        repository.saveAll("c", messages);
        var memory = new TurnSafeChatMemory(new JsonChatMemoryRepository(jdbc), 3);
        memory.add("c", new AssistantMessage("answer"));
        var restored = memory.get("c");
        assertThat(restored).hasSize(6);
        assertThat(restored.get(0).getMetadata()).containsEntry(ContextCompactionAdvisor.SYNTHETIC, true);
        assertThat(restored.get(1).getMetadata()).containsEntry(ContextCompactionAdvisor.SYNTHETIC, true);
        assertThat(((AssistantMessage) restored.get(3)).getToolCalls().getFirst().id()).isEqualTo("id");
        assertThat(((ToolResponseMessage) restored.get(4)).getResponses().getFirst().id()).isEqualTo("id");
    }

    @Test void failedInsertRollsBackEntireReplacement() {
        var jdbc = jdbc(); var repository = new JsonChatMemoryRepository(jdbc);
        repository.saveAll("c", List.of(new UserMessage("original")));
        jdbc.execute("CREATE TRIGGER reject_assistant BEFORE INSERT ON SPRING_AI_CHAT_MEMORY WHEN NEW.type = 'ASSISTANT' BEGIN SELECT RAISE(ABORT, 'fixture failure'); END");
        assertThatThrownBy(() -> repository.saveAll("c", List.of(new UserMessage("replacement"), new AssistantMessage("fail"))))
                .isInstanceOf(RuntimeException.class);
        assertThat(repository.findByConversationId("c")).extracting(Message::getText).containsExactly("original");
    }
}
