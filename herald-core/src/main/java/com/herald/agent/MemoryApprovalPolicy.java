package com.herald.agent;

import java.util.Map;

/**
 * Per-type approval policy controlling whether a memory mutation gets a
 * confirm-diff prompt before it lands. See issue #317.
 *
 * <p>Two axes:</p>
 * <ul>
 *   <li><b>Type policy</b> — keyed by the page's {@code type:} frontmatter
 *       field ({@code concept}, {@code entity}, {@code source}, {@code user},
 *       {@code feedback}, {@code project}, {@code reference}). Default
 *       {@code auto} (write silently); set to {@code confirm-diff} to gate.</li>
 *   <li><b>Operation override</b> — {@code deleteAny} / {@code renameAny} apply
 *       to those operations regardless of type, since they're destructive.</li>
 * </ul>
 *
 * <p>Defaults optimize for "trust the agent on low-stakes types, confirm on
 * durable knowledge plus any delete/rename": concept/entity/source/delete/rename
 * gate by default; user/feedback/project/reference auto.</p>
 */
public record MemoryApprovalPolicy(
        Map<String, Mode> byType,
        Mode deleteAny,
        Mode renameAny,
        Mode defaultMode,
        int timeoutSeconds) {

    public enum Mode {
        /** Silently apply; no diff. Default for low-stakes types. */
        AUTO,
        /** Render the diff and block until {@code /confirm} response or timeout. */
        CONFIRM_DIFF;

        public static Mode parse(String raw) {
            if (raw == null) return null;
            String n = raw.trim().toLowerCase();
            return switch (n) {
                case "auto" -> AUTO;
                case "confirm-diff", "confirm_diff", "confirm" -> CONFIRM_DIFF;
                default -> null;
            };
        }
    }

    /**
     * Sane defaults that match the issue's "trust low-stakes, confirm durable
     * knowledge + destructive ops" guidance. Used when the user hasn't set
     * any {@code herald.memory.approval} block.
     */
    public static MemoryApprovalPolicy defaults() {
        return new MemoryApprovalPolicy(
                Map.of(
                        "concept", Mode.CONFIRM_DIFF,
                        "entity", Mode.CONFIRM_DIFF,
                        "source", Mode.CONFIRM_DIFF,
                        "user", Mode.AUTO,
                        "feedback", Mode.AUTO,
                        "project", Mode.AUTO,
                        "reference", Mode.AUTO),
                Mode.CONFIRM_DIFF,   // deleteAny
                Mode.CONFIRM_DIFF,   // renameAny
                Mode.AUTO,           // unknown types
                120);
    }

    /** Always-auto policy — disables every gate. Used in tests + task-agent mode. */
    public static MemoryApprovalPolicy disabled() {
        return new MemoryApprovalPolicy(Map.of(), Mode.AUTO, Mode.AUTO, Mode.AUTO, 0);
    }

    /**
     * Resolve the mode for a given mutation. {@code toolName} is the lowercase
     * memory tool name; {@code pageType} is the type from frontmatter (or null
     * if unknown). Operation overrides win for delete/rename.
     */
    public Mode resolveMode(String toolName, String pageType) {
        if (toolName == null) return defaultMode;
        String n = toolName.toLowerCase();
        if (n.equals("memorydelete")) return deleteAny;
        if (n.equals("memoryrename")) return renameAny;
        if (pageType == null) return defaultMode;
        return byType.getOrDefault(pageType.toLowerCase(), defaultMode);
    }
}
