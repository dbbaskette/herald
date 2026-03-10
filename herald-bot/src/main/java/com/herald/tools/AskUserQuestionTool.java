package com.herald.tools;

import com.herald.telegram.TelegramQuestionHandler;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Tool for asking the user a clarifying question during agent execution.
 * Delegates to TelegramQuestionHandler when available for interactive Q&A.
 */
@Component
public class AskUserQuestionTool {

    private final TelegramQuestionHandler questionHandler;

    AskUserQuestionTool(ObjectProvider<TelegramQuestionHandler> questionHandlerProvider) {
        this.questionHandler = questionHandlerProvider.getIfAvailable();
    }

    @Tool(description = "Ask the user a clarifying question when more information is needed to complete a task. Use sparingly — only when the task cannot proceed without user input.")
    public String ask_user(
            @ToolParam(description = "The question to ask the user") String question) {
        if (questionHandler == null) {
            return "PENDING: Question sent to user: " + question
                    + " — Awaiting response. (Note: interactive Q&A not yet wired — TelegramSender is not configured)";
        }

        String answer = questionHandler.askQuestion(question);
        if (answer == null || answer.isBlank()) {
            return "TIMEOUT: No response received from user within "
                    + TelegramQuestionHandler.DEFAULT_TIMEOUT_MINUTES + " minutes for question: " + question;
        }
        return answer;
    }
}
