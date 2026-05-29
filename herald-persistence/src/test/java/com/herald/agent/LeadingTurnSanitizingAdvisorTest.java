package com.herald.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LeadingTurnSanitizingAdvisorTest {

    private static List<MessageType> types(List<Message> msgs) {
        return msgs.stream().map(Message::getMessageType).toList();
    }

    @Test
    void dropsLeadingAssistantAfterSystem() {
        List<Message> in = List.of(
                new SystemMessage("sys"),
                new AssistantMessage("orphaned reply"),
                new UserMessage("hello"),
                new AssistantMessage("hi"));
        List<Message> out = LeadingTurnSanitizingAdvisor.trimLeadingNonUser(in);
        assertThat(types(out)).containsExactly(MessageType.SYSTEM, MessageType.USER, MessageType.ASSISTANT);
    }

    @Test
    void dropsLeadingAssistantWithNoSystem() {
        List<Message> in = List.of(
                new AssistantMessage("orphaned"),
                new UserMessage("hello"));
        List<Message> out = LeadingTurnSanitizingAdvisor.trimLeadingNonUser(in);
        assertThat(types(out)).containsExactly(MessageType.USER);
    }

    @Test
    void leavesProperlyOrderedHistoryUnchanged() {
        List<Message> in = List.of(
                new SystemMessage("sys"),
                new UserMessage("one"),
                new AssistantMessage("ok"),
                new UserMessage("two"));
        List<Message> out = LeadingTurnSanitizingAdvisor.trimLeadingNonUser(in);
        assertThat(out).isSameAs(in); // no change → same reference
    }

    @Test
    void leavesHistoryWithNoUserMessageUnchanged() {
        List<Message> in = List.of(new SystemMessage("sys"), new AssistantMessage("only assistant"));
        List<Message> out = LeadingTurnSanitizingAdvisor.trimLeadingNonUser(in);
        assertThat(out).isSameAs(in);
    }
}
