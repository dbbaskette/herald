package com.herald.telegram;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MessageFormatterTest {

    private final MessageFormatter formatter = new MessageFormatter();

    @Test
    void shortMessageReturnedAsSingleChunk() {
        List<String> result = formatter.split("Hello world");
        assertThat(result).containsExactly("Hello world");
    }

    @Test
    void emptyMessageReturnsEmptyList() {
        assertThat(formatter.split("")).isEmpty();
        assertThat(formatter.split(null)).isEmpty();
    }

    @Test
    void messageAtExactLimitReturnedAsSingleChunk() {
        String text = "A".repeat(MessageFormatter.TELEGRAM_MAX_LENGTH);
        List<String> result = formatter.split(text);
        assertThat(result).hasSize(1);
        assertThat(result.getFirst()).hasSize(MessageFormatter.TELEGRAM_MAX_LENGTH);
    }

    @Test
    void longMessageSplitAtSentenceBoundary() {
        String sentence1 = "A".repeat(3000) + ". ";
        String sentence2 = "B".repeat(2000) + ".";
        String text = sentence1 + sentence2;

        List<String> result = formatter.split(text);

        assertThat(result).hasSize(2);
        assertThat(result.get(0)).endsWith(".");
        assertThat(result.get(1)).startsWith("B");
    }

    @Test
    void longMessageSplitAtNewlineWhenNoSentenceBoundary() {
        String line1 = "A".repeat(3000) + "\n";
        String line2 = "B".repeat(2000);
        String text = line1 + line2;

        List<String> result = formatter.split(text);

        assertThat(result).hasSize(2);
        assertThat(result.get(0)).doesNotContain("\n");
        assertThat(result.get(1)).startsWith("B");
    }

    @Test
    void escapeMarkdownV2SpecialCharacters() {
        String input = "Hello_world *bold* [link](url) `code`";
        String escaped = formatter.escapeMarkdownV2(input);

        assertThat(escaped).contains("\\_");
        assertThat(escaped).contains("\\*");
        assertThat(escaped).contains("\\[");
        assertThat(escaped).contains("\\]");
        assertThat(escaped).contains("\\(");
        assertThat(escaped).contains("\\)");
        assertThat(escaped).contains("\\`");
    }

    @Test
    void escapeMarkdownV2HandlesNullInput() {
        assertThat(formatter.escapeMarkdownV2(null)).isEmpty();
    }

    @Test
    void escapeMarkdownV2PreservesPlainText() {
        String input = "Hello world 123";
        assertThat(formatter.escapeMarkdownV2(input)).isEqualTo(input);
    }
}
