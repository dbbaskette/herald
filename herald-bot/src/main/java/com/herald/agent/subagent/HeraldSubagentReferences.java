package com.herald.agent.subagent;

import org.springaicommunity.agent.common.task.subagent.SubagentReference;
import org.springaicommunity.agent.tools.task.claude.ClaudeSubagentReferences;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Loads {@link SubagentReference} definitions from markdown files in a
 * directory.  Delegates to the upstream loader while keeping Herald's
 * public API provider-neutral.
 */
public final class HeraldSubagentReferences {

    private HeraldSubagentReferences() {}

    public static List<SubagentReference> fromDirectory(String directory) {
        Path path = Path.of(directory);
        if (Files.isDirectory(path)) {
            return ClaudeSubagentReferences.fromRootDirectory(directory);
        }
        return List.of();
    }
}
