package com.herald.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Tool for asking the user a clarifying question during agent execution.
 * Stub implementation until spring-ai-agent-utils provides the canonical version.
 * Currently returns a message indicating the question was posed; real implementation
 * will integrate with TelegramSender for interactive Q&A.
 */
@Component
public class AskUserQuestionTool {

    @Tool(description = "Ask the user a clarifying question when more information is needed to complete a task. Use sparingly — only when the task cannot proceed without user input.")
    public String ask_user(
            @ToolParam(description = "The question to ask the user") String question) {
        // TODO: Integrate with TelegramSender for real-time user interaction
        return "PENDING: Question sent to user: " + question
                + " — Awaiting response. (Note: interactive Q&A not yet wired)";
    }
}
