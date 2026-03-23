package com.herald.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits markdown content into chunks for vector embedding.
 * Small files (below token threshold) are returned whole.
 * Large files are split on heading boundaries, with fallback to paragraph boundaries.
 */
public class MarkdownChunker {

    private static final Pattern FRONTMATTER = Pattern.compile("\\A---\\n.*?\\n---\\n*", Pattern.DOTALL);
    private static final Pattern HEADING = Pattern.compile("(?m)^#{1,6} ");

    private final int smallFileThreshold; // approximate character count
    private final int maxChunkSize;       // approximate character count

    public MarkdownChunker(int smallFileThreshold, int maxChunkSize) {
        this.smallFileThreshold = smallFileThreshold;
        this.maxChunkSize = maxChunkSize;
    }

    public List<String> chunk(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }

        content = FRONTMATTER.matcher(content).replaceFirst("").trim();
        if (content.isEmpty()) {
            return List.of();
        }

        if (content.length() < smallFileThreshold) {
            return List.of(content);
        }

        List<String> sections = splitByHeadings(content);
        if (sections.size() <= 1) {
            return splitByParagraphs(content);
        }

        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String section : sections) {
            if (current.length() + section.length() > maxChunkSize && current.length() > 0) {
                chunks.add(current.toString().trim());
                current = new StringBuilder();
            }

            if (section.length() > maxChunkSize) {
                if (current.length() > 0) {
                    chunks.add(current.toString().trim());
                    current = new StringBuilder();
                }
                chunks.addAll(splitByParagraphs(section));
            } else {
                current.append(section).append("\n\n");
            }
        }

        if (current.length() > 0) {
            String remaining = current.toString().trim();
            if (!remaining.isEmpty()) {
                chunks.add(remaining);
            }
        }

        return chunks;
    }

    private List<String> splitByHeadings(String content) {
        List<String> sections = new ArrayList<>();
        Matcher matcher = HEADING.matcher(content);
        int lastStart = 0;

        while (matcher.find()) {
            if (matcher.start() > lastStart) {
                String section = content.substring(lastStart, matcher.start()).trim();
                if (!section.isEmpty()) {
                    sections.add(section);
                }
            }
            lastStart = matcher.start();
        }

        if (lastStart < content.length()) {
            String section = content.substring(lastStart).trim();
            if (!section.isEmpty()) {
                sections.add(section);
            }
        }

        return sections;
    }

    private List<String> splitByParagraphs(String content) {
        String[] paragraphs = content.split("\n\n+");
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String para : paragraphs) {
            if (current.length() + para.length() > maxChunkSize && current.length() > 0) {
                chunks.add(current.toString().trim());
                current = new StringBuilder();
            }
            current.append(para).append("\n\n");
        }

        if (current.length() > 0) {
            String remaining = current.toString().trim();
            if (!remaining.isEmpty()) {
                chunks.add(remaining);
            }
        }

        return chunks;
    }
}
