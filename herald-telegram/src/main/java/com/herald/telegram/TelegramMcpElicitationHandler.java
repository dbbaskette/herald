package com.herald.telegram;

import io.modelcontextprotocol.spec.McpSchema.ElicitRequest;
import io.modelcontextprotocol.spec.McpSchema.ElicitResult;
import io.modelcontextprotocol.spec.McpSchema.ElicitResult.Action;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpElicitation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@ConditionalOnProperty("herald.telegram.bot-token")
public class TelegramMcpElicitationHandler {

    private static final Logger log = LoggerFactory.getLogger(TelegramMcpElicitationHandler.class);
    private final TelegramQuestionHandler questionHandler;

    public TelegramMcpElicitationHandler(TelegramQuestionHandler questionHandler) {
        this.questionHandler = questionHandler;
    }

    @McpElicitation(clients = {"google-calendar", "gmail"})
    public ElicitResult handleElicitation(ElicitRequest request) {
        log.info("MCP Elicitation request: {}", request.message());

        String questionText = "MCP server needs clarification:\n\n"
                + request.message()
                + "\n\nReply with your answer, or 'cancel' to decline.";

        String answer = questionHandler.askQuestion(questionText);

        if (answer == null || answer.isBlank() || "cancel".equalsIgnoreCase(answer.trim())) {
            log.info("MCP Elicitation declined or timed out");
            return new ElicitResult(Action.DECLINE, null);
        }

        log.info("MCP Elicitation accepted");
        return new ElicitResult(Action.ACCEPT, Map.of("response", answer));
    }
}
