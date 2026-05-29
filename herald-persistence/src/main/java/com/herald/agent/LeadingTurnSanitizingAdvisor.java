package com.herald.agent;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;

/**
 * Ensures the conversation sent to the model starts (after any system messages)
 * with a USER turn, by dropping leading ASSISTANT / TOOL messages.
 *
 * <p>{@code MessageWindowChatMemory} keeps the last N messages, so when a long
 * conversation is trimmed the window can begin with an assistant turn. Lenient
 * providers (Anthropic, Gemini) accept that, but strict chat templates — notably
 * local models served through LM Studio / Ollama (Qwen, etc.) — reject a history
 * that opens with an assistant message and return a bare {@code 400}. Trimming
 * leading non-user turns is harmless for every provider (a conversation should
 * begin with the user), so this runs unconditionally.</p>
 *
 * <p>Idempotent — only touches the prefix before the first user message — so it's
 * safe to re-run on each pass of the tool-call loop.</p>
 */
public class LeadingTurnSanitizingAdvisor implements CallAdvisor {

    private static final Logger log = LoggerFactory.getLogger(LeadingTurnSanitizingAdvisor.class);

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        List<Message> messages = request.prompt().getInstructions();
        List<Message> fixed = trimLeadingNonUser(messages);
        if (fixed.size() != messages.size()) {
            log.debug("Dropped {} leading non-user message(s) so history starts with a user turn",
                    messages.size() - fixed.size());
            request = request.mutate()
                    .prompt(new Prompt(fixed, request.prompt().getOptions()))
                    .build();
        }
        return chain.nextCall(request);
    }

    /**
     * Keep all leading SYSTEM messages, then drop ASSISTANT/TOOL messages until the
     * first USER message, then keep everything from there on. If there's no user
     * message at all, return the input unchanged (nothing to anchor to).
     */
    static List<Message> trimLeadingNonUser(List<Message> messages) {
        int firstUser = -1;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).getMessageType() == MessageType.USER) {
                firstUser = i;
                break;
            }
        }
        if (firstUser < 0) return messages;

        List<Message> out = new ArrayList<>(messages.size());
        boolean droppedAny = false;
        for (int i = 0; i < messages.size(); i++) {
            MessageType type = messages.get(i).getMessageType();
            if (i < firstUser) {
                if (type == MessageType.SYSTEM) {
                    out.add(messages.get(i)); // system prompt stays at the front
                } else {
                    droppedAny = true; // assistant/tool before the first user — drop
                }
            } else {
                out.add(messages.get(i));
            }
        }
        return droppedAny ? out : messages;
    }

    @Override
    public String getName() {
        return "LeadingTurnSanitizingAdvisor";
    }

    @Override
    public int getOrder() {
        // Run late — after memory/compaction advisors have assembled the final
        // message list — so we sanitize what actually goes to the model.
        return Ordered.LOWEST_PRECEDENCE - 50;
    }
}
