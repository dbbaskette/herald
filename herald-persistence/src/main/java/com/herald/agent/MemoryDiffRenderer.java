package com.herald.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders compact, Telegram-friendly previews of pending memory mutations
 * so the user can {@code /confirm} or decline before the write lands.
 *
 * <p>By design we don't pull in a full diff library — Telegram has a 4 KB
 * practical message limit and most edits are tiny. We render
 * {@code MemoryStrReplace} as an old/new block, {@code MemoryCreate} as the
 * full new content (truncated), {@code MemoryDelete} as a snapshot of what's
 * about to vanish, and {@code MemoryRename} as the path swap. See issue #317.</p>
 */
public final class MemoryDiffRenderer {

    /** Hard cap so a giant page doesn't blow past Telegram's message limit. */
    static final int MAX_BODY_PREVIEW = 1500;

    /** Match the {@code type:} field inside a YAML frontmatter block. */
    private static final Pattern TYPE_FIELD = Pattern.compile(
            "(?ms)^---\\s*\\n(.*?)^---\\s*\\n");
    private static final Pattern TYPE_LINE = Pattern.compile(
            "(?m)^type\\s*:\\s*([\\w-]+)\\s*$");

    private MemoryDiffRenderer() {
    }

    /**
     * Build the preview that gets shown in the approval prompt.
     *
     * @param toolName  one of {@code memoryCreate / memoryStrReplace / memoryInsert /
     *                  memoryDelete / memoryRename} (case-insensitive)
     * @param toolInput raw JSON tool input from Spring AI
     * @param memoriesRoot root memory directory for resolving relative paths
     */
    public static String render(String toolName, String toolInput, Path memoriesRoot) {
        String name = toolName == null ? "" : toolName.toLowerCase();
        String path = LoggingMemoryToolCallback.extractPath(toolInput);
        return switch (name) {
            case "memorystrreplace" -> renderStrReplace(toolInput, path);
            case "memorycreate" -> renderCreate(toolInput, path);
            case "memoryinsert" -> renderInsert(toolInput, path);
            case "memorydelete" -> renderDelete(toolInput, path, memoriesRoot);
            case "memoryrename" -> renderRename(toolInput);
            default -> "→ " + name + " on " + (path == null ? "<unknown>" : path);
        };
    }

    private static String renderStrReplace(String toolInput, String path) {
        String oldStr = extract(toolInput, "old_str");
        String newStr = extract(toolInput, "new_str");
        StringBuilder sb = new StringBuilder();
        sb.append("→ Edit ").append(safe(path)).append("\n\n");
        sb.append(prefixLines("  - ", truncate(oldStr, MAX_BODY_PREVIEW))).append("\n");
        sb.append(prefixLines("  + ", truncate(newStr, MAX_BODY_PREVIEW)));
        return sb.toString();
    }

    private static String renderCreate(String toolInput, String path) {
        String content = extract(toolInput, "content");
        StringBuilder sb = new StringBuilder();
        sb.append("→ Create ").append(safe(path)).append("\n\n");
        sb.append(prefixLines("  + ", truncate(content, MAX_BODY_PREVIEW)));
        return sb.toString();
    }

    private static String renderInsert(String toolInput, String path) {
        String content = extract(toolInput, "insert_str");
        if (content == null) content = extract(toolInput, "content");
        String line = extract(toolInput, "insert_line");
        StringBuilder sb = new StringBuilder();
        sb.append("→ Insert into ").append(safe(path));
        if (line != null) sb.append(" (after line ").append(line).append(")");
        sb.append("\n\n");
        sb.append(prefixLines("  + ", truncate(content, MAX_BODY_PREVIEW)));
        return sb.toString();
    }

    private static String renderDelete(String toolInput, String path, Path root) {
        StringBuilder sb = new StringBuilder();
        sb.append("→ Delete ").append(safe(path)).append("\n\n");
        if (path != null && root != null) {
            try {
                Path full = root.resolve(path);
                if (Files.exists(full) && Files.isRegularFile(full)) {
                    String existing = Files.readString(full);
                    sb.append(prefixLines("  - ", truncate(existing, MAX_BODY_PREVIEW)));
                    return sb.toString();
                }
            } catch (IOException ignored) {
                // fall through to "(file content unavailable)"
            }
        }
        sb.append("  (file content unavailable for preview)");
        return sb.toString();
    }

    private static String renderRename(String toolInput) {
        String oldPath = extract(toolInput, "old_path");
        String newPath = extract(toolInput, "new_path");
        return "→ Rename " + safe(oldPath) + " → " + safe(newPath);
    }

    /**
     * @return the page's {@code type:} field from frontmatter, or {@code null}
     *         if the file doesn't exist or has no frontmatter. Falls back to
     *         a path-prefix heuristic ({@code concepts/}, {@code entities/},
     *         {@code sources/}) when the file isn't readable.
     */
    public static String resolvePageType(Path memoriesRoot, String relativePath, String createContent) {
        if (relativePath == null) return null;
        // If we have new content (Create), parse from there.
        if (createContent != null) {
            String fromContent = parseTypeFromFrontmatter(createContent);
            if (fromContent != null) return fromContent;
        }
        if (memoriesRoot != null) {
            try {
                Path full = memoriesRoot.resolve(relativePath);
                if (Files.exists(full) && Files.isRegularFile(full)) {
                    return parseTypeFromFrontmatter(Files.readString(full));
                }
            } catch (IOException ignored) {
                // fall through to path heuristic
            }
        }
        // Path-prefix fallback — the convention used by wiki-ingest skill.
        if (relativePath.startsWith("concepts/")) return "concept";
        if (relativePath.startsWith("entities/")) return "entity";
        if (relativePath.startsWith("sources/")) return "source";
        return null;
    }

    static String parseTypeFromFrontmatter(String content) {
        if (content == null) return null;
        Matcher fm = TYPE_FIELD.matcher(content);
        if (!fm.find()) return null;
        Matcher tl = TYPE_LINE.matcher(fm.group(1));
        return tl.find() ? tl.group(1).toLowerCase() : null;
    }

    static String truncate(String s, int max) {
        if (s == null) return "(empty)";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "\n... [truncated, " + (s.length() - max) + " chars elided]";
    }

    static String prefixLines(String prefix, String text) {
        if (text == null || text.isEmpty()) return prefix + "(empty)";
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            sb.append(prefix).append(line).append('\n');
        }
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    /** Reuse LoggingMemoryToolCallback's package-private JSON extractor. */
    static String extract(String toolInput, String key) {
        return LoggingMemoryToolCallback.extractJsonString(toolInput, key);
    }

    private static String safe(String value) {
        return value == null ? "<unknown>" : value;
    }
}
