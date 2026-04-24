package com.herald.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;

/**
 * Produces an on-demand retrospective of the previous conversation turn —
 * the implementation of the {@code /why} slash command (#318).
 *
 * <p>Pulls the last user/assistant pair from {@link ChatMemory}, constructs
 * a rationale-focused prompt, and runs a one-off {@link ChatClient} call
 * against the active provider with extended thinking enabled. Results stream
 * back as text chunks via {@link Flux}; the retrospective is NOT appended to
 * chat memory — it's a read-only sidecar, not part of the conversation.</p>
 */
@Service
@ConditionalOnBean(ChatMemory.class)
public class RetrospectiveService {

    private static final Logger log = LoggerFactory.getLogger(RetrospectiveService.class);
    private static final long THINKING_BUDGET_TOKENS = 16_384;
    private static final String PROMPT_RESOURCE = "prompts/RETROSPECTIVE_PROMPT.md";

    private final ChatMemory chatMemory;
    private final ModelSwitcher modelSwitcher;
    private final String promptTemplate;

    @Autowired
    public RetrospectiveService(ChatMemory chatMemory, ModelSwitcher modelSwitcher) {
        this.chatMemory = chatMemory;
        this.modelSwitcher = modelSwitcher;
        this.promptTemplate = loadPromptTemplate();
    }

    /** Test constructor — injects a pre-loaded template. */
    RetrospectiveService(ChatMemory chatMemory, ModelSwitcher modelSwitcher, String promptTemplate) {
        this.chatMemory = chatMemory;
        this.modelSwitcher = modelSwitcher;
        this.promptTemplate = promptTemplate;
    }

    /**
     * Generate a retrospective for the previous turn in the given conversation.
     * Returns a Flux of text chunks suitable for streaming to Telegram. Emits a
     * single error string (wrapped as a completed Flux) when no prior turn is
     * available or the model call fails.
     */
    public Flux<String> explainLastTurn(String conversationId) {
        LastPair pair = findLastPair(conversationId);
        if (pair == null) {
            return Flux.just("No prior turn to explain — send a message first, "
                    + "then run /why afterwards.");
        }

        String retroPrompt = renderPrompt(pair.userText(), pair.assistantText());
        log.info("Generating retrospective for conversation={}, userMsgLen={}, assistantMsgLen={}",
                conversationId, pair.userText().length(), pair.assistantText().length());

        ChatClient.Builder builder = modelSwitcher.activeChatClientBuilder();
        if (modelSwitcher.getActiveChatModel() instanceof AnthropicChatModel) {
            builder.defaultOptions(AnthropicChatOptions.builder()
                    .model(modelSwitcher.getActiveModel())
                    .thinkingEnabled(THINKING_BUDGET_TOKENS));
        }

        return builder.build()
                .prompt()
                .user(retroPrompt)
                .stream()
                .content()
                .onErrorResume(e -> {
                    log.warn("Retrospective generation failed: {}", e.getMessage());
                    return Flux.just("Couldn't generate the retrospective: " + e.getMessage());
                });
    }

    /**
     * Walk the conversation history backwards to find the most recent
     * user message and the assistant response that immediately followed it.
     * Returns null when no such pair exists.
     */
    LastPair findLastPair(String conversationId) {
        List<Message> history = chatMemory.get(conversationId);
        if (history == null || history.size() < 2) {
            return null;
        }

        // Walk backwards; the last assistant message should be preceded by
        // (possibly many tool messages and then) a user message.
        String assistantText = null;
        for (int i = history.size() - 1; i >= 0; i--) {
            Message m = history.get(i);
            if (m instanceof AssistantMessage am && assistantText == null) {
                String text = am.getText();
                if (text != null && !text.isBlank()) {
                    assistantText = text;
                }
            } else if (m instanceof UserMessage um && assistantText != null) {
                String userText = um.getText();
                if (userText != null && !userText.isBlank()) {
                    return new LastPair(userText, assistantText);
                }
            }
        }
        return null;
    }

    String renderPrompt(String userText, String assistantText) {
        return promptTemplate
                .replace("{{user_message}}", userText)
                .replace("{{assistant_response}}", assistantText);
    }

    private static String loadPromptTemplate() {
        try (var in = new ClassPathResource(PROMPT_RESOURCE).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to load retrospective prompt template: {}", e.getMessage());
            return "Explain why you made the choices in your previous turn. "
                    + "User said: {{user_message}}\nYou responded: {{assistant_response}}";
        }
    }

    /** Package-visible record for the last user+assistant pair. */
    record LastPair(String userText, String assistantText) {}
}
