package com.herald.telegram;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class MessageFormatter {

    static final int TELEGRAM_MAX_LENGTH = 4096;

    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("(?<=[.!?])\\s+");
    private static final Pattern MARKDOWN_V2_SPECIAL = Pattern.compile("([_*\\[\\]()~`>#+\\-=|{}.!])");

    List<String> split(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        if (text.length() <= TELEGRAM_MAX_LENGTH) {
            return List.of(text);
        }

        List<String> chunks = new ArrayList<>();
        String remaining = text;

        while (remaining.length() > TELEGRAM_MAX_LENGTH) {
            int splitAt = findSplitPoint(remaining, TELEGRAM_MAX_LENGTH);
            chunks.add(remaining.substring(0, splitAt).stripTrailing());
            remaining = remaining.substring(splitAt).stripLeading();
        }
        if (!remaining.isEmpty()) {
            chunks.add(remaining);
        }
        return chunks;
    }

    String escapeMarkdownV2(String text) {
        if (text == null) {
            return "";
        }
        Matcher matcher = MARKDOWN_V2_SPECIAL.matcher(text);
        return matcher.replaceAll("\\\\$1");
    }

    private int findSplitPoint(String text, int maxLength) {
        String window = text.substring(0, maxLength);

        // Try to split at a sentence boundary
        int lastSentenceEnd = -1;
        Matcher matcher = SENTENCE_BOUNDARY.matcher(window);
        while (matcher.find()) {
            lastSentenceEnd = matcher.start();
        }
        if (lastSentenceEnd > maxLength / 2) {
            return lastSentenceEnd;
        }

        // Fall back to newline boundary
        int lastNewline = window.lastIndexOf('\n');
        if (lastNewline > maxLength / 2) {
            return lastNewline + 1;
        }

        // Fall back to space
        int lastSpace = window.lastIndexOf(' ');
        if (lastSpace > maxLength / 2) {
            return lastSpace + 1;
        }

        // Hard split as last resort
        return maxLength;
    }
}
