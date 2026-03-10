package com.herald.agent;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.core.Ordered;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Advisor that resolves {@code {current_datetime}} and {@code {timezone}} placeholders
 * in the system prompt on each turn, ensuring the model always sees the current time
 * rather than a stale value captured at startup.
 *
 * <p>Must run before {@link MemoryBlockAdvisor} so that memory content appended later
 * cannot inject datetime template placeholders.</p>
 */
class DateTimePromptAdvisor implements CallAdvisor {

    private final ZoneId timezone;
    private final DateTimeFormatter formatter;

    DateTimePromptAdvisor(ZoneId timezone, DateTimeFormatter formatter) {
        this.timezone = timezone;
        this.formatter = formatter;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        ZonedDateTime now = ZonedDateTime.now(timezone);
        request = request.mutate()
                .prompt(request.prompt().augmentSystemMessage(
                        existing -> {
                            String text = existing.getText()
                                    .replace("{current_datetime}", now.format(formatter))
                                    .replace("{timezone}", timezone.getId());
                            return new SystemMessage(text);
                        }))
                .build();
        return chain.nextCall(request);
    }

    @Override
    public String getName() {
        return "DateTimePromptAdvisor";
    }

    @Override
    public int getOrder() {
        // Run before MemoryBlockAdvisor (HIGHEST_PRECEDENCE + 100) so that memory content
        // appended later cannot have datetime placeholders resolved.
        return Ordered.HIGHEST_PRECEDENCE + 50;
    }
}
