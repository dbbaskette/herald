package com.herald.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A {@link ChatMemoryRepository} that stores each message as a full JSON blob in SQLite.
 * Unlike Spring AI's built-in {@code JdbcChatMemoryRepository}, this preserves all
 * structured fields: tool call IDs, tool response data, arguments, and metadata.
 *
 * <p>This eliminates the need for the {@code ToolPairSanitizingAdvisor} — tool messages
 * survive serialization/deserialization with all data intact.</p>
 */
public class JsonChatMemoryRepository implements ChatMemoryRepository {

    private static final Logger log = LoggerFactory.getLogger(JsonChatMemoryRepository.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final JdbcTemplate jdbcTemplate;

    public JsonChatMemoryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<String> findConversationIds() {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT conversation_id FROM SPRING_AI_CHAT_MEMORY ORDER BY conversation_id",
                String.class);
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        List<String> rows = jdbcTemplate.queryForList(
                "SELECT content FROM SPRING_AI_CHAT_MEMORY WHERE conversation_id = ? ORDER BY timestamp, rowid",
                String.class,
                conversationId);

        List<Message> messages = new ArrayList<>(rows.size());
        for (String json : rows) {
            try {
                Message msg = deserialize(json);
                if (msg != null) {
                    messages.add(msg);
                }
            } catch (Exception e) {
                log.warn("Skipping unreadable message in conversation '{}': {}", conversationId, e.getMessage());
            }
        }
        return messages;
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        // Spring AI 2.0.0 MessageWindowChatMemory no longer calls deleteByConversationId
        // before saveAll — the repository is expected to handle the full replacement.
        deleteByConversationId(conversationId);
        for (Message msg : messages) {
            try {
                String json = serialize(msg);
                jdbcTemplate.update(
                        "INSERT INTO SPRING_AI_CHAT_MEMORY (conversation_id, content, type, timestamp) VALUES (?, ?, ?, CURRENT_TIMESTAMP)",
                        conversationId,
                        json,
                        msg.getMessageType().name());
            } catch (Exception e) {
                log.warn("Failed to serialize message (type={}): {}", msg.getMessageType(), e.getMessage());
            }
        }
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        jdbcTemplate.update("DELETE FROM SPRING_AI_CHAT_MEMORY WHERE conversation_id = ?", conversationId);
    }

    // ---- Serialization ----

    String serialize(Message msg) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", msg.getMessageType().name());

        switch (msg) {
            case UserMessage user -> {
                node.put("content", user.getText());
            }
            case AssistantMessage assistant -> {
                node.put("content", assistant.getText() != null ? assistant.getText() : "");
                if (assistant.hasToolCalls()) {
                    ArrayNode toolCalls = mapper.createArrayNode();
                    for (AssistantMessage.ToolCall tc : assistant.getToolCalls()) {
                        ObjectNode tcNode = mapper.createObjectNode();
                        tcNode.put("id", tc.id());
                        tcNode.put("type", tc.type());
                        tcNode.put("name", tc.name());
                        tcNode.put("arguments", tc.arguments());
                        toolCalls.add(tcNode);
                    }
                    node.set("toolCalls", toolCalls);
                }
            }
            case ToolResponseMessage toolResponse -> {
                ArrayNode responses = mapper.createArrayNode();
                for (ToolResponseMessage.ToolResponse resp : toolResponse.getResponses()) {
                    ObjectNode respNode = mapper.createObjectNode();
                    respNode.put("id", resp.id());
                    respNode.put("name", resp.name());
                    respNode.put("responseData", resp.responseData());
                    responses.add(respNode);
                }
                node.set("responses", responses);
            }
            case SystemMessage system -> {
                node.put("content", system.getText());
            }
            default -> {
                node.put("content", msg.getText() != null ? msg.getText() : "");
            }
        }

        // Preserve metadata if present
        Map<String, Object> metadata = msg.getMetadata();
        if (metadata != null && !metadata.isEmpty()) {
            node.set("metadata", mapper.valueToTree(metadata));
        }

        return mapper.writeValueAsString(node);
    }

    Message deserialize(String json) {
        JsonNode node = mapper.readTree(json);
        String type = node.path("type").asText();

        return switch (type) {
            case "USER" -> {
                String content = node.path("content").asText("");
                yield new UserMessage(content);
            }
            case "ASSISTANT" -> {
                String content = node.path("content").asText("");
                var builder = AssistantMessage.builder().content(content);

                if (node.has("toolCalls")) {
                    List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
                    for (JsonNode tcNode : node.get("toolCalls")) {
                        toolCalls.add(new AssistantMessage.ToolCall(
                                tcNode.path("id").asText(),
                                tcNode.path("type").asText(),
                                tcNode.path("name").asText(),
                                tcNode.path("arguments").asText()));
                    }
                    builder.toolCalls(toolCalls);
                }

                yield builder.build();
            }
            case "TOOL" -> {
                List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
                if (node.has("responses")) {
                    for (JsonNode respNode : node.get("responses")) {
                        responses.add(new ToolResponseMessage.ToolResponse(
                                respNode.path("id").asText(),
                                respNode.path("name").asText(),
                                respNode.path("responseData").asText("")));
                    }
                }
                if (responses.isEmpty()) {
                    // Legacy row from old JdbcChatMemoryRepository — skip it
                    yield null;
                }
                yield ToolResponseMessage.builder().responses(responses).build();
            }
            case "SYSTEM" -> {
                yield new SystemMessage(node.path("content").asText(""));
            }
            default -> {
                log.warn("Unknown message type '{}', treating as USER", type);
                yield new UserMessage(node.path("content").asText(""));
            }
        };
    }
}
